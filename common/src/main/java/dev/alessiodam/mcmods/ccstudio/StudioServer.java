package dev.alessiodam.mcmods.ccstudio;

import dev.alessiodam.mcmods.ccstudio.platform.Platform;
import dev.alessiodam.mcmods.ccstudio.platform.Settings;
import dev.alessiodam.mcmods.ccstudio.session.Session;
import dev.alessiodam.mcmods.ccstudio.session.SessionManager;
import dev.alessiodam.mcmods.ccstudio.tunnel.CloudflareTunnel;
import dev.alessiodam.mcmods.ccstudio.vscode.VSCodeWeb;
import dev.alessiodam.mcmods.ccstudio.web.WebServer;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

public final class StudioServer {
    private static volatile @Nullable StudioServer current;

    private final Platform platform;
    private final Settings settings;
    private final SessionManager sessions;
    private final WebServer web;
    private volatile @Nullable String webError;
    private @Nullable CloudflareTunnel tunnel;

    private StudioServer(Platform platform, Settings settings) {
        this.platform = platform;
        this.settings = settings;
        this.sessions = new SessionManager(this);
        this.web = new WebServer(this);
    }

    public static synchronized void start(Platform platform, Settings settings) {
        stop();
        try {
            VSCodeWeb.INSTANCE.prepare(settings, platform.gameDirectory());
        } catch (RuntimeException e) {
            Log.LOGGER.error("Failed to prepare VS Code Web", e);
        }

        var studio = new StudioServer(platform, settings);
        try {
            studio.web.start();
            Log.LOGGER.info("CC: Studio web server listening on {}:{}, sessions will use {}", settings.bindAddress(), settings.port(), studio.baseUrl());
        } catch (Exception e) {
            studio.webError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.LOGGER.error("Failed to start the CC: Studio web server on {}:{}", settings.bindAddress(), settings.port(), e);
        }
        if (studio.webError == null) studio.startTunnel();
        current = studio;
    }

    public static synchronized void stop() {
        var studio = current;
        current = null;
        if (studio == null) return;
        studio.sessions.shutdown();
        if (studio.tunnel != null) studio.tunnel.stop();
        studio.web.stop();
    }

    public static @Nullable StudioServer get() {
        return current;
    }

    public Platform platform() {
        return platform;
    }

    public Settings settings() {
        return settings;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public @Nullable String webError() {
        return webError;
    }

    public @Nullable String tunnelStatus() {
        return tunnel == null ? null : tunnel.status();
    }

    public void tick() {
        sessions.tick();
    }

    public String baseUrl() {
        var configured = settings.publicUrl().trim();
        if (!configured.isEmpty()) return stripTrailingSlash(configured);
        var tunnelHost = tunnel == null ? null : tunnel.hostname();
        if (tunnelHost != null) return "https://" + tunnelHost;

        var host = settings.bindAddress().trim();
        if (host.isEmpty() || host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")) {
            var serverIp = platform.serverAddress();
            host = serverIp == null || serverIp.isBlank() ? "localhost" : serverIp.trim();
        }
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        var scheme = settings.tlsEnabled() ? "https" : "http";
        var defaultPort = settings.tlsEnabled() ? 443 : 80;
        return settings.port() == defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + settings.port();
    }

    public String sessionUrl(Session session) {
        return baseUrl() + "/s/" + session.token() + "/";
    }

    private void startTunnel() {
        var token = settings.tunnelToken().trim();
        if (token.isEmpty()) return;
        var problem = CloudflareTunnel.validateToken(token);
        if (problem != null) {
            Log.LOGGER.error("Not starting the Cloudflare Tunnel: {}", problem);
            return;
        }
        var bind = settings.bindAddress().trim();
        if (!bind.equals("127.0.0.1") && !bind.equals("localhost") && !bind.equals("::1")) {
            Log.LOGGER.warn("The Cloudflare Tunnel is enabled but web.bindAddress is {}. Set it to 127.0.0.1 so the editor is only reachable through the tunnel.", bind);
        }
        tunnel = new CloudflareTunnel(token, settings.cloudflaredPath(), settings.port(), platform.gameDirectory());
        tunnel.start();
    }

    private static String stripTrailingSlash(String url) {
        var result = url;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result.toLowerCase(Locale.ROOT).startsWith("http") ? result : "http://" + result;
    }
}
