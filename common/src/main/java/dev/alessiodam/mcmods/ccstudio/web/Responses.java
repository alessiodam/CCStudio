package dev.alessiodam.mcmods.ccstudio.web;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class Responses {
    static final String NO_STORE = "no-store";
    static final String NO_CACHE = "no-cache";
    static final String PRIVATE_IMMUTABLE = "private, max-age=31536000, immutable";

    private Responses() {
    }

    static ChannelFuture html(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, String html, List<String> cookies) {
        var response = full(status, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8", NO_STORE);
        response.headers()
                .set("X-Frame-Options", "SAMEORIGIN")
                .set("Content-Security-Policy", "frame-ancestors 'self'");
        for (var cookie : cookies) response.headers().add(HttpHeaderNames.SET_COOKIE, cookie);
        return send(ctx, request, response);
    }

    static ChannelFuture json(ChannelHandlerContext ctx, FullHttpRequest request, String json, List<String> cookies) {
        var response = full(HttpResponseStatus.OK, json.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", NO_STORE);
        for (var cookie : cookies) response.headers().add(HttpHeaderNames.SET_COOKIE, cookie);
        return send(ctx, request, response);
    }

    static ChannelFuture text(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, String text) {
        return send(ctx, request, full(status, text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", NO_STORE));
    }

    static ChannelFuture bytes(ChannelHandlerContext ctx, FullHttpRequest request, byte[] body, byte @Nullable [] gzipped, String contentType, String etag) {
        var gzip = gzipped != null && acceptsGzip(request);
        var tag = gzip ? etag.substring(0, etag.length() - 1) + "-gz\"" : etag;
        if (tag.equals(request.headers().get(HttpHeaderNames.IF_NONE_MATCH))) return notModified(ctx, request, NO_CACHE, tag);
        var response = full(HttpResponseStatus.OK, gzip ? gzipped : body, contentType, NO_CACHE);
        response.headers().set(HttpHeaderNames.ETAG, tag).set(HttpHeaderNames.VARY, HttpHeaderNames.ACCEPT_ENCODING);
        if (gzip) response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
        return send(ctx, request, response);
    }

    static ChannelFuture redirect(ChannelHandlerContext ctx, FullHttpRequest request, String location) {
        var response = full(HttpResponseStatus.MOVED_PERMANENTLY, new byte[0], "text/plain; charset=utf-8", NO_STORE);
        response.headers().set(HttpHeaderNames.LOCATION, location);
        return send(ctx, request, response);
    }

    static ChannelFuture file(ChannelHandlerContext ctx, FullHttpRequest request, Path path, @Nullable Path gzipped, String contentType, String etag) throws IOException {
        var gzip = gzipped != null && acceptsGzip(request);
        var tag = gzip ? etag.substring(0, etag.length() - 1) + "-gz\"" : etag;
        if (tag.equals(request.headers().get(HttpHeaderNames.IF_NONE_MATCH))) return notModified(ctx, request, PRIVATE_IMMUTABLE, tag);

        var file = new RandomAccessFile((gzip ? gzipped : path).toFile(), "r");
        var length = file.length();
        var response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        HttpUtil.setContentLength(response, length);
        headers(response, contentType, PRIVATE_IMMUTABLE);
        response.headers().set(HttpHeaderNames.ETAG, tag).set(HttpHeaderNames.VARY, HttpHeaderNames.ACCEPT_ENCODING);
        if (gzip) response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
        var keepAlive = HttpUtil.isKeepAlive(request);
        response.headers().set(HttpHeaderNames.CONNECTION, keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);

        ctx.write(response);
        ChannelFuture last;
        if (request.method().equals(HttpMethod.HEAD)) {
            file.close();
            last = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        } else {
            last = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(file, 0, length, 16384)));
        }
        if (!keepAlive) last.addListener(ChannelFutureListener.CLOSE);
        return last;
    }

    private static boolean acceptsGzip(FullHttpRequest request) {
        var header = request.headers().get(HttpHeaderNames.ACCEPT_ENCODING);
        if (header == null) return false;
        for (var part : header.toLowerCase(Locale.ROOT).split(",")) {
            var pieces = part.split(";");
            if (!pieces[0].trim().equals("gzip")) continue;
            for (var i = 1; i < pieces.length; i++) {
                var parameter = pieces[i].trim();
                if (!parameter.startsWith("q=")) continue;
                try {
                    return Double.parseDouble(parameter.substring(2)) > 0;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static ChannelFuture notModified(ChannelHandlerContext ctx, FullHttpRequest request, String cacheControl, String etag) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, cacheControl).set(HttpHeaderNames.ETAG, etag);
        return send(ctx, request, response);
    }

    private static DefaultFullHttpResponse full(HttpResponseStatus status, byte[] body, String contentType, String cacheControl) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        headers(response, contentType, cacheControl);
        HttpUtil.setContentLength(response, body.length);
        return response;
    }

    private static void headers(HttpResponse response, String contentType, String cacheControl) {
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                .set(HttpHeaderNames.CACHE_CONTROL, cacheControl)
                .set("X-Content-Type-Options", "nosniff")
                .set("Referrer-Policy", "no-referrer");
    }

    private static ChannelFuture send(ChannelHandlerContext ctx, FullHttpRequest request, DefaultFullHttpResponse response) {
        var keepAlive = HttpUtil.isKeepAlive(request);
        response.headers().set(HttpHeaderNames.CONNECTION, keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
        var future = ctx.writeAndFlush(response);
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE);
        return future;
    }
}
