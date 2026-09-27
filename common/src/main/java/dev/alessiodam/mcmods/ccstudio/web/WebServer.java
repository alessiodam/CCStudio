package dev.alessiodam.mcmods.ccstudio.web;

import dev.alessiodam.mcmods.ccstudio.Log;
import dev.alessiodam.mcmods.ccstudio.StudioServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutorGroup;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class WebServer {
    static final int HTTP_IDLE_SECONDS = 60;
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final int MAX_INITIAL_LINE_BYTES = 4096;
    private static final int MAX_HEADER_BYTES = 8192;

    private final StudioServer studio;
    private final AtomicInteger connections = new AtomicInteger();
    private @Nullable EventLoopGroup bossGroup;
    private @Nullable EventLoopGroup workerGroup;
    private @Nullable EventExecutorGroup handlerGroup;
    private @Nullable Channel channel;

    public WebServer(StudioServer studio) {
        this.studio = studio;
    }

    public void start() throws Exception {
        var settings = studio.settings();
        var sslContext = settings.tlsEnabled() ? TlsContexts.create(settings, studio.platform().gameDirectory()) : null;
        var resources = new WebResources(studio.platform().modVersion());
        var gzipCache = new GzipCache();
        var maxConnections = settings.maxConnections();

        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("CCStudio-Web-Boss", true));
        workerGroup = new NioEventLoopGroup(2, new DefaultThreadFactory("CCStudio-Web-IO", true));
        handlerGroup = new DefaultEventExecutorGroup(4, new DefaultThreadFactory("CCStudio-Web", true));
        var handlers = handlerGroup;

        var bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(256 * 1024, 1024 * 1024))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        var pipeline = ch.pipeline();
                        pipeline.addLast("limiter", new ConnectionLimiter(connections, maxConnections));
                        if (sslContext != null) pipeline.addLast("ssl", sslContext.newHandler(ch.alloc()));
                        pipeline.addLast("http", new HttpServerCodec(MAX_INITIAL_LINE_BYTES, MAX_HEADER_BYTES, 8192));
                        pipeline.addLast("idle", new IdleStateHandler(0, 0, HTTP_IDLE_SECONDS));
                        pipeline.addLast("aggregator", new HttpObjectAggregator(MAX_REQUEST_BYTES));
                        pipeline.addLast("chunked", new ChunkedWriteHandler());
                        pipeline.addLast(handlers, "studio-http", new HttpHandler(studio, resources, gzipCache, handlers, sslContext != null));
                    }
                });

        var bindAddress = settings.bindAddress().trim();
        var port = settings.port();
        var host = bindAddress.isEmpty() ? "0.0.0.0" : bindAddress;
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try {
            channel = bootstrap.bind(new InetSocketAddress(host, port)).sync().channel();
        } catch (Exception e) {
            stop();
            throw e;
        }
    }

    public void stop() {
        if (channel != null) {
            channel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
            channel = null;
        }
        if (bossGroup != null) bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        if (workerGroup != null) workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        if (handlerGroup != null) handlerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        bossGroup = null;
        workerGroup = null;
        handlerGroup = null;
        Log.LOGGER.debug("CC: Studio web server stopped");
    }
}
