package dev.alessiodam.mcmods.ccstudio.neoforge;

import dev.alessiodam.mcmods.ccstudio.platform.Settings;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Locale;

public final class NeoForgeConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.ConfigValue<String> BIND_ADDRESS;
    public static final ModConfigSpec.IntValue PORT;
    public static final ModConfigSpec.ConfigValue<String> PUBLIC_URL;
    public static final ModConfigSpec.IntValue MAX_CONNECTIONS;

    public static final ModConfigSpec.BooleanValue TLS_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> TLS_CERTIFICATE;
    public static final ModConfigSpec.ConfigValue<String> TLS_PRIVATE_KEY;
    public static final ModConfigSpec.ConfigValue<String> TLS_PASSWORD;

    public static final ModConfigSpec.ConfigValue<String> TUNNEL_TOKEN;
    public static final ModConfigSpec.ConfigValue<String> CLOUDFLARED_PATH;

    public static final ModConfigSpec.IntValue MAX_SESSIONS;
    public static final ModConfigSpec.IntValue MAX_CONNECTIONS_PER_SESSION;
    public static final ModConfigSpec.IntValue IDLE_TIMEOUT_MINUTES;
    public static final ModConfigSpec.IntValue MAX_LIFETIME_HOURS;
    public static final ModConfigSpec.BooleanValue CHAT_LINKS;
    public static final ModConfigSpec.BooleanValue SHOW_ROM;
    public static final ModConfigSpec.BooleanValue ALLOW_COMMAND_COMPUTERS;

    public static final ModConfigSpec.ConfigValue<String> VSCODE_URL;
    public static final ModConfigSpec.ConfigValue<String> VSCODE_SHA256;
    public static final ModConfigSpec.BooleanValue OPEN_VSX;

    static {
        var builder = new ModConfigSpec.Builder();

        builder.push("web");
        ENABLED = builder
                .comment("Enable the CC: Studio web server.")
                .define("enabled", true);
        BIND_ADDRESS = builder
                .comment("Address the web server listens on. Keep 127.0.0.1 for singleplayer, use 0.0.0.0 to accept connections from other machines.")
                .define("bindAddress", "127.0.0.1");
        PORT = builder
                .comment("Port the web server listens on.")
                .defineInRange("port", 8765, 1, 65535);
        PUBLIC_URL = builder
                .comment("Base URL players use to reach the web server, e.g. https://studio.example.com when it sits behind a reverse proxy or tunnel. Leave empty to build it from bindAddress and port.")
                .define("publicUrl", "", NeoForgeConfig::isValidPublicUrl);
        MAX_CONNECTIONS = builder
                .comment("Maximum number of simultaneous HTTP and WebSocket connections to the web server.")
                .defineInRange("maxConnections", 256, 16, 65536);
        builder.pop();

        builder.push("tls");
        TLS_ENABLED = builder
                .comment("Serve HTTPS directly from the web server.")
                .define("enabled", false);
        TLS_CERTIFICATE = builder
                .comment("Certificate chain as a PEM file, or a PKCS#12 keystore (.p12/.pfx). Relative paths resolve against the game directory.")
                .define("certificate", "");
        TLS_PRIVATE_KEY = builder
                .comment("PKCS#8 PEM private key for the certificate. Not used with PKCS#12 keystores. Convert other formats with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem")
                .define("privateKey", "");
        TLS_PASSWORD = builder
                .comment("Password for an encrypted private key or keystore. Leave empty if there is none.")
                .define("password", "");
        builder.pop();

        builder.push("tunnel");
        TUNNEL_TOKEN = builder
                .comment("Cloudflare Tunnel token (Zero Trust dashboard > Networks > Tunnels > your tunnel > install command). When set, the server runs cloudflared and players reach the editor through the tunnel. Give the tunnel a public hostname whose service is http://localhost:<web.port>. Keep this value secret.")
                .define("cloudflareToken", "");
        CLOUDFLARED_PATH = builder
                .comment("Path to an existing cloudflared executable. Leave empty to download a verified copy into <game directory>/ccstudio/cloudflared.")
                .define("cloudflaredPath", "");
        builder.pop();

        builder.push("sessions");
        MAX_SESSIONS = builder
                .comment("Maximum number of editor sessions open at the same time.")
                .defineInRange("maxSessions", 64, 1, 4096);
        MAX_CONNECTIONS_PER_SESSION = builder
                .comment("Maximum number of browser tabs connected to one session.")
                .defineInRange("maxConnectionsPerSession", 8, 1, 64);
        IDLE_TIMEOUT_MINUTES = builder
                .comment("Close a session after this many minutes without any browser connected.")
                .defineInRange("idleTimeoutMinutes", 30, 1, 10080);
        MAX_LIFETIME_HOURS = builder
                .comment("Close a session after this many hours even if a browser is still connected.")
                .defineInRange("maxLifetimeHours", 12, 1, 720);
        CHAT_LINKS = builder
                .comment("Send a clickable chat link to the players looking at the computer's screen when a session opens.")
                .define("chatLinks", true);
        SHOW_ROM = builder
                .comment("Show the read-only /rom folder in the editor.")
                .define("showRom", true);
        ALLOW_COMMAND_COMPUTERS = builder
                .comment("Allow editor sessions on command computers. Anyone holding a session link could run server commands through them, so only enable this if you trust everyone who can use command computers.")
                .define("allowCommandComputers", false);
        builder.pop();

        builder.push("vscode");
        VSCODE_URL = builder
                .comment("Download URL of the VS Code Web build (vscode-web.tar.gz). It is downloaded once and cached in <game directory>/ccstudio/vscode-web.")
                .define("downloadUrl", Settings.DEFAULT_VSCODE_URL);
        VSCODE_SHA256 = builder
                .comment("Expected SHA-256 of the download. Leave empty to skip verification when using a custom URL.")
                .define("sha256", Settings.DEFAULT_VSCODE_SHA256);
        OPEN_VSX = builder
                .comment("Let the editor install web extensions from open-vsx.org.")
                .define("openVsx", true);
        builder.pop();

        SPEC = builder.build();
    }

    private NeoForgeConfig() {
    }

    public static Settings snapshot() {
        return new Settings(
                BIND_ADDRESS.get().trim(),
                PORT.get(),
                PUBLIC_URL.get().trim(),
                MAX_CONNECTIONS.get(),
                TLS_ENABLED.get(),
                TLS_CERTIFICATE.get(),
                TLS_PRIVATE_KEY.get(),
                TLS_PASSWORD.get(),
                TUNNEL_TOKEN.get(),
                CLOUDFLARED_PATH.get(),
                MAX_SESSIONS.get(),
                MAX_CONNECTIONS_PER_SESSION.get(),
                IDLE_TIMEOUT_MINUTES.get(),
                MAX_LIFETIME_HOURS.get(),
                CHAT_LINKS.get(),
                SHOW_ROM.get(),
                ALLOW_COMMAND_COMPUTERS.get(),
                VSCODE_URL.get(),
                VSCODE_SHA256.get(),
                OPEN_VSX.get()
        );
    }

    private static boolean isValidPublicUrl(Object value) {
        if (!(value instanceof String url)) return false;
        var trimmed = url.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() || trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }
}
