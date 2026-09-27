package dev.alessiodam.mcmods.ccstudio.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.alessiodam.mcmods.ccstudio.Log;
import dev.alessiodam.mcmods.ccstudio.session.FsException;
import dev.alessiodam.mcmods.ccstudio.session.Session;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.timeout.IdleStateEvent;
import org.jspecify.annotations.Nullable;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class StudioSocket extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final int CLOSE_SESSION_ENDED = 4000;
    private static final int CLOSE_TOO_MANY = 4429;
    private static final int CLOSE_POLICY = 1008;
    private static final long MAX_PENDING_BYTES = 4L * 1024 * 1024;
    private static final int MAX_SMALL_MESSAGE_BYTES = 64 * 1024;
    private static final Pattern LARGE_MESSAGE_PREFIX = Pattern.compile("^\\{\\s*\"op\"\\s*:\\s*\"writeFile\"");

    private final Session session;
    private final WebSocketServerHandshaker handshaker;
    private final AtomicBoolean frameRequested = new AtomicBoolean();
    private volatile @Nullable Channel channel;
    private volatile boolean connected;
    private volatile boolean terminal;

    StudioSocket(Session session, WebSocketServerHandshaker handshaker) {
        this.session = session;
        this.handshaker = handshaker;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        if (!session.connect(this)) {
            closeWith(CLOSE_TOO_MANY, session.isClosed() ? "This editor session has ended." : "Too many browser tabs are connected to this session.");
            return;
        }
        connected = true;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        disconnect();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        disconnect();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        var current = ctx.channel();
        current.config().setAutoRead(current.isWritable());
        if (current.isWritable()) frameRequested.set(true);
        super.channelWritabilityChanged(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        switch (frame) {
            case CloseWebSocketFrame close -> handshaker.close(ctx.channel(), close.retain());
            case PingWebSocketFrame ping -> ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            case TextWebSocketFrame text -> {
                var size = text.content().readableBytes();
                if (!session.allowFrame(size)) {
                    closeWith(CLOSE_POLICY, "Too much traffic from this browser.");
                    return;
                }
                var message = text.text();
                if (size > MAX_SMALL_MESSAGE_BYTES && !LARGE_MESSAGE_PREFIX.matcher(message.substring(0, Math.min(64, message.length()))).find()) {
                    closeWith(CLOSE_POLICY, "Message too large.");
                    return;
                }
                handle(message);
            }
            default -> {
            }
        }
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
        Log.LOGGER.debug("Closing editor connection after an error", cause);
        ctx.close();
    }

    public boolean wantsTerminal() {
        return terminal;
    }

    public boolean consumeFrameRequest() {
        return frameRequested.getAndSet(false);
    }

    public void send(String json) {
        var current = channel;
        if (current == null || !current.isActive()) return;
        if (!current.isWritable() && current.bytesBeforeWritable() > MAX_PENDING_BYTES) {
            current.close();
            return;
        }
        current.writeAndFlush(new TextWebSocketFrame(json));
    }

    public void sendFrame(String json) {
        var current = channel;
        if (current == null || !current.isActive()) return;
        if (!current.isWritable()) {
            frameRequested.set(true);
            return;
        }
        current.writeAndFlush(new TextWebSocketFrame(json));
    }

    public void closeSession(String reason) {
        connected = false;
        closeWith(CLOSE_SESSION_ENDED, reason);
    }

    private void disconnect() {
        if (!connected) return;
        connected = false;
        session.disconnect(this);
    }

    private void closeWith(int code, String reason) {
        var current = channel;
        if (current == null || !current.isActive()) return;
        var message = new JsonObject();
        message.addProperty("event", "closed");
        message.addProperty("reason", reason);
        current.writeAndFlush(new TextWebSocketFrame(Json.write(message)));
        current.writeAndFlush(new CloseWebSocketFrame(code, "Session closed")).addListener(ChannelFutureListener.CLOSE);
    }

    private void handle(String text) {
        if (!connected) return;

        JsonObject message;
        try {
            message = Json.parseObject(text);
        } catch (JsonParseException | IllegalStateException | StackOverflowError e) {
            return;
        }

        var id = message.get("id");
        var op = Json.string(message, "op", "");
        if (op.equals("input")) {
            var events = message.get("events");
            if (events != null && events.isJsonArray()) session.input(events.getAsJsonArray());
            return;
        }
        if (!session.allowRequest()) {
            fail(id, "Unavailable", "Too many requests, slow down.");
            return;
        }
        var current = channel;
        if (current != null && !current.isWritable() && !op.equals("ping")) {
            fail(id, "Unavailable", "The browser is not reading responses fast enough.");
            return;
        }

        var fs = session.fileSystem();
        try {
            switch (op) {
                case "ping" -> reply(id, JsonNull.INSTANCE);
                case "stat" -> reply(id, fs.stat(path(message, "path")));
                case "readDirectory" -> reply(id, fs.readDirectory(path(message, "path")));
                case "readFile" -> {
                    var result = new JsonObject();
                    result.addProperty("data", Base64.getEncoder().encodeToString(fs.readFile(path(message, "path"))));
                    reply(id, result);
                }
                case "writeFile" -> {
                    var data = Base64.getDecoder().decode(Json.string(message, "data", ""));
                    fs.writeFile(path(message, "path"), data, Json.bool(message, "create", true), Json.bool(message, "overwrite", true));
                    reply(id, JsonNull.INSTANCE);
                }
                case "createDirectory" -> {
                    fs.createDirectory(path(message, "path"));
                    reply(id, JsonNull.INSTANCE);
                }
                case "delete" -> {
                    fs.delete(path(message, "path"), Json.bool(message, "recursive", false));
                    reply(id, JsonNull.INSTANCE);
                }
                case "rename" -> {
                    fs.rename(path(message, "from"), path(message, "to"), Json.bool(message, "overwrite", false));
                    reply(id, JsonNull.INSTANCE);
                }
                case "listFiles" -> reply(id, fs.listFiles());
                case "terminal" -> {
                    terminal = Json.bool(message, "enabled", true);
                    if (terminal) frameRequested.set(true);
                    reply(id, JsonNull.INSTANCE);
                }
                case "control" -> {
                    var action = Json.string(message, "action", "");
                    var program = message.has("path") ? Json.string(message, "path", "") : null;
                    session.control(action, program, error -> {
                        if (error == null) {
                            reply(id, JsonNull.INSTANCE);
                        } else {
                            fail(id, "Unavailable", error);
                        }
                    });
                }
                default -> fail(id, "Unknown", "Unknown operation.");
            }
        } catch (FsException e) {
            fail(id, e.code(), e.getMessage());
        } catch (IllegalArgumentException | UnsupportedOperationException | IllegalStateException e) {
            fail(id, "Unavailable", "Malformed request.");
        } catch (RuntimeException e) {
            Log.LOGGER.warn("Failed to handle editor request '{}'", op, e);
            fail(id, "Unavailable", "Internal error, see the server log.");
        }
    }

    private static String path(JsonObject message, String key) {
        var value = Json.string(message, key, null);
        if (value == null) throw new IllegalArgumentException("Missing '" + key + "'");
        return value;
    }

    private void reply(@Nullable JsonElement id, JsonElement result) {
        if (!isValidId(id)) return;
        var response = new JsonObject();
        response.add("id", id);
        response.addProperty("ok", true);
        response.add("result", result);
        send(Json.write(response));
    }

    private void fail(@Nullable JsonElement id, String code, String message) {
        if (!isValidId(id)) return;
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        var response = new JsonObject();
        response.add("id", id);
        response.addProperty("ok", false);
        response.add("error", error);
        send(Json.write(response));
    }

    private static boolean isValidId(@Nullable JsonElement id) {
        return id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber();
    }
}
