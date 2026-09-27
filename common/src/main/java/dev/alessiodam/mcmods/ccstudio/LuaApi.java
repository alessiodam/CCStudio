package dev.alessiodam.mcmods.ccstudio;

import dev.alessiodam.mcmods.ccstudio.platform.ComputerHandle;
import dev.alessiodam.mcmods.ccstudio.session.Session;
import dev.alessiodam.mcmods.ccstudio.vscode.VSCodeWeb;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LuaApi {
    private LuaApi() {
    }

    public static Map<String, Object> open(ComputerHandle computer) throws StudioException {
        var studio = StudioServer.get();
        if (studio == null) throw new StudioException("CC: Studio is not running");
        var error = studio.webError();
        if (error != null) throw new StudioException("CC: Studio web server is not running: " + error);

        var existed = studio.sessions().get(computer.id()) != null;
        var session = studio.sessions().open(computer);
        var url = studio.sessionUrl(session);
        var notified = studio.settings().chatLinks() && studio.sessions().tryChatLink(computer.id())
                ? studio.platform().sendSessionLink(computer, url)
                : 0;

        var result = describe(studio, session);
        result.put("created", !existed);
        result.put("notified", notified);
        return result;
    }

    public static List<Map<String, Object>> pending(int computerId) {
        var session = session(computerId);
        if (session == null) return List.of();
        var now = System.currentTimeMillis();
        var result = new ArrayList<Map<String, Object>>();
        for (var pairing : session.pendingPairings()) {
            var entry = new HashMap<String, Object>();
            entry.put("code", pairing.code());
            entry.put("address", pairing.address());
            entry.put("browser", pairing.browser());
            entry.put("age", (now - pairing.created()) / 1000);
            result.add(entry);
        }
        return result;
    }

    public static boolean trust(int computerId, String code) {
        var session = session(computerId);
        return session != null && session.approve(code);
    }

    public static boolean close(int computerId) {
        var studio = StudioServer.get();
        return studio != null && studio.sessions().close(computerId, "The session was closed from the computer.");
    }

    public static @Nullable Map<String, Object> status(int computerId) {
        var studio = StudioServer.get();
        if (studio == null) return null;
        var session = studio.sessions().get(computerId);
        return session == null ? null : describe(studio, session);
    }

    public static boolean isAvailable() {
        var studio = StudioServer.get();
        return studio != null && studio.webError() == null;
    }

    private static @Nullable Session session(int computerId) {
        var studio = StudioServer.get();
        return studio == null ? null : studio.sessions().get(computerId);
    }

    private static Map<String, Object> describe(StudioServer studio, Session session) {
        var editor = VSCodeWeb.INSTANCE.status();
        var result = new HashMap<String, Object>();
        result.put("url", studio.sessionUrl(session));
        result.put("clients", session.connectionCount());
        result.put("idleTimeout", studio.settings().idleTimeoutMinutes());
        result.put("editor", editor.phase().id());
        result.put("editorMessage", editor.message());
        if (editor.progress() >= 0) result.put("editorProgress", editor.progress());
        var tunnel = studio.tunnelStatus();
        if (tunnel != null) result.put("tunnel", tunnel);
        return result;
    }
}
