package dev.alessiodam.mcmods.ccstudio.tunnel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.alessiodam.mcmods.ccstudio.Log;
import dev.alessiodam.mcmods.ccstudio.util.Downloads;
import dev.alessiodam.mcmods.ccstudio.util.TarGz;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class CloudflareTunnel {
    private static final String VERSION = "2026.9.3";
    private static final long MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024;
    private static final long MIN_HEALTHY_RUN_MILLIS = 60_000;
    private static final long MAX_RETRY_DELAY_MILLIS = 60_000;
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();
    private static final Thread SHUTDOWN_HOOK = new Thread(() -> LIVE.forEach(Process::destroyForcibly), "CCStudio-Tunnel-Shutdown");
    private static boolean hookRegistered;
    private static final Pattern HOSTNAME = Pattern.compile("(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");

    private final String token;
    private final String configuredPath;
    private final int port;
    private final Path gameDirectory;
    private volatile boolean stopped;
    private volatile @Nullable Process process;
    private volatile @Nullable String hostname;
    private volatile String status = "starting";
    private @Nullable Thread supervisor;

    public CloudflareTunnel(String token, String configuredPath, int port, Path gameDirectory) {
        this.token = token.trim();
        this.configuredPath = configuredPath.trim();
        this.port = port;
        this.gameDirectory = gameDirectory;
    }

    public static @Nullable String validateToken(String token) {
        var trimmed = token.trim();
        if (trimmed.length() > 4096) return "the token is too long";
        try {
            var decoded = trimmed.contains("-") || trimmed.contains("_") ? Base64.getUrlDecoder().decode(trimmed) : Base64.getDecoder().decode(trimmed);
            var json = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var key : new String[]{ "a", "t", "s" }) {
                if (!json.has(key) || !json.get(key).isJsonPrimitive()) return "the token is missing its '" + key + "' field";
            }
            return null;
        } catch (RuntimeException e) {
            return "the token is not a valid Cloudflare Tunnel token";
        }
    }

    public synchronized void start() {
        if (supervisor != null) return;
        synchronized (CloudflareTunnel.class) {
            if (!hookRegistered) {
                Runtime.getRuntime().addShutdownHook(SHUTDOWN_HOOK);
                hookRegistered = true;
            }
        }
        supervisor = Thread.ofPlatform().name("CCStudio-Tunnel").daemon().start(this::supervise);
    }

    public synchronized void stop() {
        stopped = true;
        if (supervisor != null) supervisor.interrupt();
        var current = process;
        if (current == null) return;
        current.descendants().forEach(ProcessHandle::destroy);
        current.destroy();
        try {
            if (!current.waitFor(5, TimeUnit.SECONDS)) current.destroyForcibly();
        } catch (InterruptedException e) {
            current.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    public @Nullable String hostname() {
        return hostname;
    }

    public String status() {
        return status;
    }

    private void supervise() {
        var delay = 5_000L;
        while (!stopped) {
            var started = System.currentTimeMillis();
            try {
                var exitCode = run(executable());
                if (stopped) return;
                status = "restarting";
                Log.LOGGER.warn("cloudflared exited with code {}, restarting", exitCode);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (stopped) return;
                status = "failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                Log.LOGGER.error("Cloudflare Tunnel failed: {}", e.getMessage());
            }
            if (System.currentTimeMillis() - started > MIN_HEALTHY_RUN_MILLIS) delay = 5_000L;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
            delay = Math.min(delay * 2, MAX_RETRY_DELAY_MILLIS);
        }
    }

    private int run(Path executable) throws IOException, InterruptedException {
        var builder = new ProcessBuilder(executable.toString(), "tunnel", "--no-autoupdate", "--metrics", "127.0.0.1:0", "run");
        builder.environment().put("TUNNEL_TOKEN", token);
        builder.environment().remove("TUNNEL_TOKEN_FILE");
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));

        synchronized (this) {
            if (stopped) return 0;
            process = builder.start();
        }
        var current = process;
        LIVE.add(current);
        status = "connecting";
        Log.LOGGER.info("Started cloudflared {}", VERSION);
        try (var reader = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) handleLine(line);
        }
        try {
            return current.waitFor();
        } finally {
            LIVE.remove(current);
            process = null;
        }
    }

    private void handleLine(String raw) {
        var line = ANSI.matcher(raw).replaceAll("");
        if (!token.isEmpty()) line = line.replace(token, "<token>");
        if (line.length() > 2000) line = line.substring(0, 2000);

        if (line.contains("Registered tunnel connection")) {
            if (!"connected".equals(status)) Log.LOGGER.info("Cloudflare Tunnel connected");
            status = "connected";
        } else if (line.contains("Updated to new configuration")) {
            updateHostname(line);
        }

        if (line.contains(" ERR ")) {
            Log.LOGGER.warn("cloudflared: {}", line);
        } else {
            Log.LOGGER.debug("cloudflared: {}", line);
        }
    }

    private void updateHostname(String line) {
        var start = line.indexOf("config=\"");
        var end = line.lastIndexOf("\" version=");
        if (start < 0 || end <= start) return;
        var json = line.substring(start + 8, end).replace("\\\"", "\"").replace("\\\\", "\\");
        try {
            var ingress = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("ingress");
            String fallback = null;
            for (var element : ingress) {
                if (!element.isJsonObject()) continue;
                var rule = element.getAsJsonObject();
                var candidate = string(rule, "hostname");
                if (candidate == null || !HOSTNAME.matcher(candidate).matches() || rule.has("path")) continue;
                var service = string(rule, "service");
                if (service != null && pointsHere(service)) {
                    setHostname(candidate);
                    return;
                }
                if (fallback == null) fallback = candidate;
            }
            if (fallback != null) setHostname(fallback);
        } catch (RuntimeException e) {
            Log.LOGGER.debug("Could not read the tunnel ingress configuration", e);
        }
    }

    private boolean pointsHere(String service) {
        var lower = service.toLowerCase(Locale.ROOT);
        return lower.endsWith("localhost:" + port) || lower.endsWith("127.0.0.1:" + port) || lower.endsWith("[::1]:" + port)
                || lower.contains("localhost:" + port + "/") || lower.contains("127.0.0.1:" + port + "/");
    }

    private void setHostname(String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        if (!lower.equals(hostname)) Log.LOGGER.info("Cloudflare Tunnel serves CC: Studio at https://{}", lower);
        hostname = lower;
    }

    private static @Nullable String string(JsonObject object, String key) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private Path executable() throws IOException, InterruptedException {
        if (!configuredPath.isEmpty()) {
            var path = gameDirectory.resolve(configuredPath).normalize();
            if (!Files.isRegularFile(path) || !Files.isExecutable(path)) throw new IOException("tunnel.cloudflaredPath is not an executable file: " + path);
            return path;
        }

        var asset = Asset.current();
        var directory = gameDirectory.resolve("ccstudio").resolve("cloudflared").resolve(VERSION);
        Files.createDirectories(directory);
        var download = directory.resolve(asset.file);
        if (!Files.isRegularFile(download) || !asset.sha256.equals(Downloads.sha256(download))) {
            status = "downloading cloudflared";
            Log.LOGGER.info("Downloading cloudflared {} ({})", VERSION, asset.file);
            var temp = directory.resolve(asset.file + ".download");
            try {
                var digest = Downloads.download("https://github.com/cloudflare/cloudflared/releases/download/" + VERSION + "/" + asset.file, temp, MAX_DOWNLOAD_BYTES, (done, total) -> {
                });
                if (!asset.sha256.equals(digest)) throw new IOException("cloudflared checksum mismatch, refusing to run it");
                Files.move(temp, download, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        }

        Path binary;
        if (asset.archive) {
            var extracted = directory.resolve("bin");
            deleteDirectory(extracted);
            TarGz.extract(download, extracted, 0, MAX_DOWNLOAD_BYTES, 16);
            binary = extracted.resolve("cloudflared");
            if (!Files.isRegularFile(binary)) throw new IOException("The cloudflared archive did not contain the executable");
        } else {
            binary = download;
        }
        restrictPermissions(binary);
        return binary;
    }

    private static void restrictPermissions(Path binary) throws IOException {
        try {
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            if (!binary.toFile().setExecutable(true, true)) throw new IOException("Could not make cloudflared executable");
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.walk(directory)) {
            for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    private static File nullDevice() {
        return new File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null");
    }

    private enum Asset {
        LINUX_AMD64("cloudflared-linux-amd64", "77e26d8d900e0b8469f416239d14b5f296525fdf79fee6f511ef55609e3fbac2", false),
        LINUX_ARM64("cloudflared-linux-arm64", "aaeb2d7d0da3614634c7e03ab13487a1522c2e79165ed2929cfe23d5e95b326d", false),
        LINUX_ARM("cloudflared-linux-arm", "967dc371a3fedbf09e881c13ee7ba317155ebc336cbd4afb756b46fc6785e5af", false),
        WINDOWS_AMD64("cloudflared-windows-amd64.exe", "f096265ec2fcbe9bb6e2d64268db167ced3fcbb83d894bdb9e2fcdb26f2ea7e2", false),
        DARWIN_AMD64("cloudflared-darwin-amd64.tgz", "d1155d0837487f261183b15c1eab6c4ebcad9dc49b94675f1524c3564cea3977", true),
        DARWIN_ARM64("cloudflared-darwin-arm64.tgz", "587c2cfb1c230fe36c7fa7727da78be459dae028cabe8c001291999350f07095", true);

        private final String file;
        private final String sha256;
        private final boolean archive;

        Asset(String file, String sha256, boolean archive) {
            this.file = file;
            this.sha256 = sha256;
            this.archive = archive;
        }

        static Asset current() throws IOException {
            var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            var amd64 = arch.equals("amd64") || arch.equals("x86_64");
            var arm64 = arch.equals("aarch64") || arch.equals("arm64");
            if (os.startsWith("windows")) return WINDOWS_AMD64;
            if (os.contains("mac") || os.contains("darwin")) {
                if (arm64) return DARWIN_ARM64;
                if (amd64) return DARWIN_AMD64;
            }
            if (os.contains("linux")) {
                if (amd64) return LINUX_AMD64;
                if (arm64) return LINUX_ARM64;
                if (arch.startsWith("arm")) return LINUX_ARM;
            }
            throw new IOException("No bundled cloudflared for " + os + "/" + arch + ", set tunnel.cloudflaredPath to an installed cloudflared");
        }
    }
}
