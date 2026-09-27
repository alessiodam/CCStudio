package dev.alessiodam.mcmods.ccstudio.web;

import com.google.gson.JsonObject;
import dev.alessiodam.mcmods.ccstudio.Log;
import dev.alessiodam.mcmods.ccstudio.StudioServer;
import dev.alessiodam.mcmods.ccstudio.session.Pairing;
import dev.alessiodam.mcmods.ccstudio.session.Session;
import dev.alessiodam.mcmods.ccstudio.session.Tokens;
import dev.alessiodam.mcmods.ccstudio.vscode.VSCodeWeb;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.EventExecutorGroup;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String ASSET_COOKIE = "ccstudio_a";
    private static final String DEVICE_COOKIE = "ccstudio_d";
    private static final String PAIRING_COOKIE = "ccstudio_p";
    private static final int SOCKET_IDLE_SECONDS = 90;
    private static final long PAIRING_COOKIE_SECONDS = 300;
    private static final Pattern IP_ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final StudioServer studio;
    private final WebResources resources;
    private final GzipCache gzipCache;
    private final EventExecutorGroup handlers;
    private final boolean tls;
    private volatile boolean busy;

    HttpHandler(StudioServer studio, WebResources resources, GzipCache gzipCache, EventExecutorGroup handlers, boolean tls) {
        this.studio = studio;
        this.resources = resources;
        this.gzipCache = gzipCache;
        this.handlers = handlers;
        this.tls = tls;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        if (busy) {
            ctx.close();
            return;
        }
        busy = true;
        ChannelFuture future;
        try {
            future = handle(ctx, request);
        } catch (Exception e) {
            busy = false;
            throw e;
        }
        if (future != null) future.addListener(done -> busy = false);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent) {
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, event);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Log.LOGGER.debug("Closing CC: Studio web connection after an error", cause);
        ctx.close();
    }

    private @Nullable ChannelFuture handle(ChannelHandlerContext ctx, FullHttpRequest request) throws IOException {
        if (!request.decoderResult().isSuccess() || hasBody(request)) return badRequest(ctx, request);

        String path;
        try {
            path = new QueryStringDecoder(request.uri()).path();
        } catch (IllegalArgumentException e) {
            return badRequest(ctx, request);
        }
        var segments = segments(path);

        if (isWebSocketUpgrade(request)) return upgrade(ctx, request, segments);
        if (!request.method().equals(HttpMethod.GET) && !request.method().equals(HttpMethod.HEAD)) {
            HttpUtil.setKeepAlive(request, false);
            return Responses.text(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        }

        if (segments.isEmpty()) return landing(ctx, request);
        return switch (segments.getFirst()) {
            case "favicon.ico" -> segments.size() == 1 ? vscodeFile(ctx, request, "favicon.ico") : notFound(ctx, request);
            case "vscode" -> vscode(ctx, request, segments);
            case "s" -> session(ctx, request, path, segments);
            default -> notFound(ctx, request);
        };
    }

    private ChannelFuture landing(ChannelHandlerContext ctx, FullHttpRequest request) {
        return page(ctx, request, HttpResponseStatus.OK, "CC: Studio",
                "Run <code>code</code> on a ComputerCraft computer to open it in VS Code.", "", false, List.of());
    }

    private ChannelFuture session(ChannelHandlerContext ctx, FullHttpRequest request, String path, List<String> segments) {
        if (segments.size() < 2 || !Tokens.isToken(segments.get(1))) return notFound(ctx, request);
        var session = studio.sessions().get(segments.get(1));
        if (segments.size() == 2) {
            if (session == null) return sessionNotFound(ctx, request);
            if (!path.endsWith("/")) return Responses.redirect(ctx, request, session.token() + "/");
            return entry(ctx, request, session);
        }
        if (session == null) return notFound(ctx, request);

        if (segments.size() == 3 && segments.get(2).equals("pair")) return pairingStatus(ctx, request, session);
        if (segments.size() >= 4 && segments.get(2).equals("extension")) {
            if (!isTrusted(request, session)) return notFound(ctx, request);
            var file = resources.extensionFile(String.join("/", segments.subList(3, segments.size())));
            if (file == null) return notFound(ctx, request);
            return Responses.bytes(ctx, request, file.data(), file.gzipped(), file.contentType(), file.etag());
        }
        return notFound(ctx, request);
    }

    private ChannelFuture entry(ChannelHandlerContext ctx, FullHttpRequest request, Session session) {
        if (isTrusted(request, session)) return workbench(ctx, request, session, List.of());

        var pairingId = cookie(request, PAIRING_COOKIE + session.computerId());
        var deviceToken = session.completePairing(pairingId);
        if (deviceToken != null) return workbench(ctx, request, session, trustCookies(request, session, deviceToken));

        var pairing = session.pairing(pairingId);
        var cookies = new ArrayList<String>();
        if (pairing == null) {
            var created = session.startPairing(address(ctx, request), browser(request));
            if (created == null) {
                return page(ctx, request, HttpResponseStatus.TOO_MANY_REQUESTS, "Too many requests",
                        "Several browsers are already waiting for approval. Approve one with <code>code trust</code> on the computer, or try again in a few minutes.",
                        "", false, List.of());
            }
            pairing = created.pairing();
            cookies.add(cookie(PAIRING_COOKIE + session.computerId(), created.pairingId(), sessionPath(session), PAIRING_COOKIE_SECONDS, request));
        }
        return pairingPage(ctx, request, session, pairing, cookies);
    }

    private ChannelFuture pairingPage(ChannelHandlerContext ctx, FullHttpRequest request, Session session, Pairing pairing, List<String> cookies) {
        return Responses.html(ctx, request, HttpResponseStatus.OK, resources.pairing(Map.of(
                "TITLE", WebResources.escape("Trust this browser - CC: Studio"),
                "COMPUTER", WebResources.escape(session.title()),
                "COMMAND", WebResources.escape("code trust " + pairing.code())
        )), cookies);
    }

    private ChannelFuture pairingStatus(ChannelHandlerContext ctx, FullHttpRequest request, Session session) {
        var result = new JsonObject();
        if (isTrusted(request, session)) {
            result.addProperty("state", "trusted");
            return Responses.json(ctx, request, Json.write(result), List.of());
        }
        var pairingId = cookie(request, PAIRING_COOKIE + session.computerId());
        var deviceToken = session.completePairing(pairingId);
        if (deviceToken != null) {
            result.addProperty("state", "trusted");
            return Responses.json(ctx, request, Json.write(result), trustCookies(request, session, deviceToken));
        }
        result.addProperty("state", session.pairing(pairingId) == null ? "expired" : "pending");
        return Responses.json(ctx, request, Json.write(result), List.of());
    }

    private ChannelFuture workbench(ChannelHandlerContext ctx, FullHttpRequest request, Session session, List<String> extraCookies) {
        var key = VSCodeWeb.INSTANCE.key();
        if (key == null) {
            var status = VSCodeWeb.INSTANCE.status();
            var failed = status.phase() == VSCodeWeb.Phase.FAILED;
            return page(ctx, request, failed ? HttpResponseStatus.SERVICE_UNAVAILABLE : HttpResponseStatus.OK,
                    failed ? "Editor unavailable" : "Preparing the editor",
                    failed ? "The server could not install VS Code Web. Check the server log for details."
                            : "The server is setting up VS Code Web. This only happens once and the page reloads by itself.",
                    failed ? "" : WebResources.escape(status.message()), !failed, extraCookies);
        }

        var cookies = new ArrayList<>(extraCookies);
        cookies.add(cookie(ASSET_COOKIE + session.computerId(), session.assetToken(), "/", -1, request));
        return Responses.html(ctx, request, HttpResponseStatus.OK, resources.workbench(Map.of(
                "TITLE", WebResources.escape(session.title() + " - CC: Studio"),
                "VSCODE_BASE", "../../vscode/" + key,
                "CONFIG", Json.writeHtmlSafe(workbenchConfig(session, key))
        )), cookies);
    }

    private JsonObject workbenchConfig(Session session, String key) {
        var config = new JsonObject();
        config.addProperty("vscodeBase", "../../vscode/" + key);
        config.addProperty("computerId", session.computerId());
        config.addProperty("title", session.title());
        config.addProperty("authority", "computer-" + session.computerId());
        config.addProperty("root", "Computer " + session.computerId());
        config.addProperty("openVsx", studio.settings().openVsx());
        config.addProperty("version", studio.platform().modVersion());
        return config;
    }

    private ChannelFuture vscode(ChannelHandlerContext ctx, FullHttpRequest request, List<String> segments) throws IOException {
        var key = VSCodeWeb.INSTANCE.key();
        if (segments.size() < 3 || !segments.get(1).equals(key)) return notFound(ctx, request);
        return vscodeFile(ctx, request, String.join("/", segments.subList(2, segments.size())));
    }

    private ChannelFuture vscodeFile(ChannelHandlerContext ctx, FullHttpRequest request, String relative) throws IOException {
        var key = VSCodeWeb.INSTANCE.key();
        if (key == null || !hasAssetAccess(request)) return notFound(ctx, request);
        var file = VSCodeWeb.INSTANCE.resolve(relative);
        if (file == null) return notFound(ctx, request);
        var type = MimeTypes.of(relative);
        var etag = "\"" + key + "-" + Files.size(file) + "\"";
        return Responses.file(ctx, request, file, gzipCache.compressed(file, type), type, etag);
    }

    private @Nullable ChannelFuture upgrade(ChannelHandlerContext ctx, FullHttpRequest request, List<String> segments) {
        if (segments.size() != 3 || !segments.get(0).equals("s") || !segments.get(2).equals("ws")) return notFound(ctx, request);
        var session = studio.sessions().get(segments.get(1));
        if (session == null || !isTrusted(request, session)) return notFound(ctx, request);
        if (!"13".equals(request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_VERSION))) {
            return WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        }

        var location = (tls ? "wss://" : "ws://") + "localhost/s/" + session.token() + "/ws";
        var handshaker = new WebSocketServerHandshakerFactory(location, null, false, session.maxMessageBytes()).newHandshaker(request);
        if (handshaker == null) return WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());

        var channel = ctx.channel();
        handshaker.handshake(channel, request).addListener((ChannelFutureListener) future -> {
            var pipeline = channel.pipeline();
            if (!future.isSuccess() || !channel.isActive() || pipeline.context(this) == null) {
                channel.close();
                return;
            }
            pipeline.remove(this);
            if (pipeline.get("chunked") != null) pipeline.remove("chunked");
            pipeline.replace("idle", "idle", new IdleStateHandler(SOCKET_IDLE_SECONDS, 0, 0));
            pipeline.addLast("ws-aggregator", new WebSocketFrameAggregator(session.maxMessageBytes()));
            pipeline.addLast(handlers, "studio-socket", new StudioSocket(session, handshaker));
        });
        return null;
    }

    private boolean isTrusted(FullHttpRequest request, Session session) {
        return session.isTrusted(cookie(request, DEVICE_COOKIE + session.computerId()));
    }

    private boolean hasAssetAccess(FullHttpRequest request) {
        for (var header : request.headers().getAll(HttpHeaderNames.COOKIE)) {
            for (var cookie : ServerCookieDecoder.LAX.decode(header)) {
                if (cookie.name().startsWith(ASSET_COOKIE) && studio.sessions().isAssetToken(cookie.value())) return true;
            }
        }
        return false;
    }

    private List<String> trustCookies(FullHttpRequest request, Session session, String deviceToken) {
        return List.of(
                cookie(DEVICE_COOKIE + session.computerId(), deviceToken, sessionPath(session), -1, request),
                cookie(PAIRING_COOKIE + session.computerId(), "", sessionPath(session), 0, request)
        );
    }

    private String cookie(String name, String value, String path, long maxAge, FullHttpRequest request) {
        var cookie = new DefaultCookie(name, value);
        cookie.setPath(path);
        cookie.setHttpOnly(true);
        cookie.setSameSite(CookieHeaderNames.SameSite.Strict);
        cookie.setSecure(tls || "https".equalsIgnoreCase(request.headers().get("X-Forwarded-Proto")));
        if (maxAge >= 0) cookie.setMaxAge(maxAge);
        return ServerCookieEncoder.STRICT.encode(cookie);
    }

    private static @Nullable String cookie(FullHttpRequest request, String name) {
        for (var header : request.headers().getAll(HttpHeaderNames.COOKIE)) {
            for (var cookie : ServerCookieDecoder.LAX.decode(header)) {
                if (cookie.name().equals(name)) return cookie.value();
            }
        }
        return null;
    }

    private static String sessionPath(Session session) {
        return "/s/" + session.token() + "/";
    }

    private static String address(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!(ctx.channel().remoteAddress() instanceof InetSocketAddress socket) || socket.getAddress() == null) return "an unknown address";
        var remote = socket.getAddress();
        if (remote.isLoopbackAddress()) {
            for (var header : new String[]{ "CF-Connecting-IP", "X-Real-IP", "X-Forwarded-For" }) {
                var value = request.headers().get(header);
                if (value == null) continue;
                var candidate = value.split(",")[0].trim();
                if (IP_ADDRESS.matcher(candidate).matches()) return candidate + " (through a proxy)";
            }
        }
        return remote.getHostAddress();
    }

    private static String browser(FullHttpRequest request) {
        var agent = request.headers().get(HttpHeaderNames.USER_AGENT, "");
        var name = agent.contains("Firefox/") ? "Firefox"
                : agent.contains("Edg/") ? "Edge"
                : agent.contains("OPR/") ? "Opera"
                : agent.contains("Chrome/") ? "Chrome"
                : agent.contains("Safari/") ? "Safari"
                : "An unknown browser";
        var system = agent.contains("Windows") ? "Windows"
                : agent.contains("Android") ? "Android"
                : agent.contains("iPhone") || agent.contains("iPad") ? "iOS"
                : agent.contains("Mac OS X") ? "macOS"
                : agent.contains("Linux") ? "Linux"
                : "an unknown system";
        return name + " on " + system;
    }

    private ChannelFuture page(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, String heading, String message, String detail, boolean refresh, List<String> cookies) {
        return Responses.html(ctx, request, status, resources.page(Map.of(
                "TITLE", WebResources.escape(heading + " - CC: Studio"),
                "HEADING", WebResources.escape(heading),
                "MESSAGE", message,
                "DETAIL", detail,
                "REFRESH", refresh ? "<meta http-equiv=\"refresh\" content=\"2\">" : ""
        )), cookies);
    }

    private ChannelFuture sessionNotFound(ChannelHandlerContext ctx, FullHttpRequest request) {
        return page(ctx, request, HttpResponseStatus.NOT_FOUND, "Session not found",
                "This editor session does not exist or has expired. Run <code>code</code> on the computer to start a new one.",
                "", false, List.of());
    }

    private static ChannelFuture notFound(ChannelHandlerContext ctx, FullHttpRequest request) {
        return Responses.text(ctx, request, HttpResponseStatus.NOT_FOUND, "Not found");
    }

    private static ChannelFuture badRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        HttpUtil.setKeepAlive(request, false);
        return Responses.text(ctx, request, HttpResponseStatus.BAD_REQUEST, "Bad request");
    }

    private static boolean hasBody(FullHttpRequest request) {
        return HttpUtil.isTransferEncodingChunked(request)
                || HttpUtil.getContentLength(request, 0L) != 0
                || request.content().isReadable();
    }

    private static boolean isWebSocketUpgrade(FullHttpRequest request) {
        return request.headers().containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true)
                && request.headers().contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true);
    }

    private static List<String> segments(String path) {
        var result = new ArrayList<String>();
        for (var part : path.split("/")) {
            if (!part.isEmpty()) result.add(part);
        }
        return result;
    }
}
