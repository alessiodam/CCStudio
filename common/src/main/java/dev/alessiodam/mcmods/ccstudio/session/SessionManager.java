package dev.alessiodam.mcmods.ccstudio.session;

import dev.alessiodam.mcmods.ccstudio.Log;
import dev.alessiodam.mcmods.ccstudio.StudioException;
import dev.alessiodam.mcmods.ccstudio.StudioServer;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerHandle;
import dev.alessiodam.mcmods.ccstudio.util.Names;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class SessionManager {
    private static final long CREATE_INTERVAL_MILLIS = 5_000;
    private static final long CHAT_LINK_INTERVAL_MILLIS = 10_000;

    private final StudioServer studio;
    private final Map<String, Session> byToken = new ConcurrentHashMap<>();
    private final Map<Integer, Session> byComputer = new ConcurrentHashMap<>();
    private final Map<String, Session> byAssetToken = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastCreated = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastChatLink = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watcher;
    private int ticks;

    public SessionManager(StudioServer studio) {
        this.studio = studio;
        this.watcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "CCStudio-Watcher");
            thread.setDaemon(true);
            return thread;
        });
        watcher.scheduleWithFixedDelay(this::pollChanges, 1, 1, TimeUnit.SECONDS);
    }

    public Session open(ComputerHandle computer) throws StudioException {
        if (computer.isCommandComputer() && !studio.settings().allowCommandComputers()) {
            throw new StudioException("Editor sessions are disabled for command computers on this server");
        }
        var existing = byComputer.get(computer.id());
        if (existing != null && !existing.isClosed()) {
            existing.attach(computer);
            existing.touch();
            return existing;
        }

        var now = System.currentTimeMillis();
        var previous = lastCreated.get(computer.id());
        if (previous != null && now - previous < CREATE_INTERVAL_MILLIS) throw new StudioException("Please wait a few seconds before starting a new session");
        if (byToken.size() >= studio.settings().maxSessions()) throw new StudioException("Too many editor sessions are open on this server");
        lastCreated.put(computer.id(), now);

        var session = new Session(studio, computer);
        byToken.put(session.token(), session);
        byComputer.put(session.computerId(), session);
        byAssetToken.put(session.assetToken(), session);
        Log.LOGGER.debug("Opened editor session for {}", Names.sanitize(session.title()));
        return session;
    }

    public boolean tryChatLink(int computerId) {
        var now = System.currentTimeMillis();
        var previous = lastChatLink.get(computerId);
        if (previous != null && now - previous < CHAT_LINK_INTERVAL_MILLIS) return false;
        lastChatLink.put(computerId, now);
        return true;
    }

    public @Nullable Session get(String token) {
        if (!Tokens.isToken(token)) return null;
        var session = byToken.get(token);
        return session == null || session.isClosed() ? null : session;
    }

    public boolean isAssetToken(String token) {
        if (!Tokens.isToken(token)) return false;
        var session = byAssetToken.get(token);
        return session != null && !session.isClosed();
    }

    public @Nullable Session get(int computerId) {
        var session = byComputer.get(computerId);
        return session == null || session.isClosed() ? null : session;
    }

    public boolean close(int computerId, String reason) {
        var session = byComputer.get(computerId);
        if (session == null) return false;
        remove(session, reason);
        return true;
    }

    public void tick() {
        ticks++;
        for (var session : byToken.values()) session.tick(ticks);
        if (ticks % 20 == 0) expire();
    }

    public void shutdown() {
        watcher.shutdownNow();
        for (var session : new ArrayList<>(byToken.values())) remove(session, "The Minecraft server is shutting down.");
    }

    private void expire() {
        var now = System.currentTimeMillis();
        var idle = TimeUnit.MINUTES.toMillis(studio.settings().idleTimeoutMinutes());
        var lifetime = TimeUnit.HOURS.toMillis(studio.settings().maxLifetimeHours());
        for (var session : new ArrayList<>(byToken.values())) {
            if (session.isExpired(now, idle, lifetime)) remove(session, "The editor session expired. Run \"code\" on the computer to start a new one.");
        }
        lastCreated.values().removeIf(time -> now - time > CREATE_INTERVAL_MILLIS);
        lastChatLink.values().removeIf(time -> now - time > CHAT_LINK_INTERVAL_MILLIS);
    }

    private void remove(Session session, String reason) {
        byToken.remove(session.token(), session);
        byComputer.remove(session.computerId(), session);
        byAssetToken.remove(session.assetToken(), session);
        session.close(reason);
        Log.LOGGER.debug("Closed editor session for {}: {}", Names.sanitize(session.title()), reason);
    }

    private void pollChanges() {
        for (var session : byToken.values()) {
            try {
                session.pollChanges();
            } catch (Exception e) {
                Log.LOGGER.debug("Failed to poll file changes for computer #{}", session.computerId(), e);
            }
        }
    }
}
