package dev.alessiodam.mcmods.ccstudio.vscode;

import com.google.gson.JsonParser;
import dev.alessiodam.mcmods.ccstudio.Log;
import dev.alessiodam.mcmods.ccstudio.platform.Settings;
import dev.alessiodam.mcmods.ccstudio.util.Downloads;
import dev.alessiodam.mcmods.ccstudio.util.TarGz;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Stream;

public final class VSCodeWeb {
    public static final VSCodeWeb INSTANCE = new VSCodeWeb();

    private static final String COMPLETE_MARKER = ".ccstudio-complete";
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;

    private volatile Phase phase = Phase.IDLE;
    private volatile long downloaded;
    private volatile long total = -1;
    private volatile @Nullable String error;
    private volatile @Nullable Path root;
    private volatile @Nullable String key;
    private volatile @Nullable String version;
    private @Nullable Thread worker;
    private @Nullable Path cacheDirectory;

    private VSCodeWeb() {
    }

    public synchronized void prepare(Settings settings, Path gameDirectory) {
        cacheDirectory = gameDirectory.resolve("ccstudio").resolve("vscode-web");
        var url = settings.vscodeUrl().trim();
        var sha256 = settings.vscodeSha256().trim().toLowerCase(Locale.ROOT);
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://") && sha256.isEmpty()) {
            phase = Phase.FAILED;
            error = "vscode.downloadUrl must use https unless vscode.sha256 is set";
            Log.LOGGER.error("Refusing to download VS Code Web: {}", error);
            return;
        }
        if (!sha256.isEmpty() && !sha256.matches("[0-9a-f]{64}")) {
            phase = Phase.FAILED;
            error = "vscode.sha256 is not a valid SHA-256 hex digest";
            Log.LOGGER.error("Refusing to download VS Code Web: {}", error);
            return;
        }
        var nextKey = cacheKey(url, sha256);
        if (nextKey.equals(key) && (phase == Phase.READY || worker != null && worker.isAlive())) return;

        key = nextKey;
        root = null;
        error = null;
        downloaded = 0;
        total = -1;

        var directory = cacheDirectory().resolve(nextKey);
        if (Files.isRegularFile(directory.resolve(COMPLETE_MARKER))) {
            ready(directory);
            return;
        }

        phase = Phase.DOWNLOADING;
        worker = Thread.ofPlatform().name("CCStudio-VSCodeWeb").daemon().start(() -> install(url, sha256, directory));
    }

    public Status status() {
        var currentPhase = phase;
        var progress = currentPhase == Phase.DOWNLOADING && total > 0 ? Math.min(1.0, (double) downloaded / total) : -1;
        var message = switch (currentPhase) {
            case IDLE -> "Waiting to download VS Code Web";
            case DOWNLOADING -> total > 0
                    ? String.format(Locale.ROOT, "Downloading VS Code Web (%d%%, %.1f / %.1f MB)", Math.round(progress * 100), downloaded / 1048576.0, total / 1048576.0)
                    : String.format(Locale.ROOT, "Downloading VS Code Web (%.1f MB)", downloaded / 1048576.0);
            case EXTRACTING -> "Unpacking VS Code Web";
            case READY -> "VS Code Web " + (version == null ? "" : version + " ") + "is ready";
            case FAILED -> "VS Code Web could not be installed: " + error;
        };
        return new Status(currentPhase, message, progress);
    }

    public @Nullable String key() {
        return phase == Phase.READY ? key : null;
    }

    public @Nullable Path resolve(String relative) {
        var base = root;
        if (base == null || phase != Phase.READY) return null;
        try {
            var target = base.resolve(relative).normalize();
            if (!target.startsWith(base) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return null;
            return target;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private void install(String url, String sha256, Path directory) {
        var cache = cacheDirectory();
        var archive = cache.resolve(directory.getFileName() + ".download");
        var staging = cache.resolve(directory.getFileName() + ".staging");
        try {
            Files.createDirectories(cache);
            Log.LOGGER.info("Downloading VS Code Web from {}", url);
            var digest = Downloads.download(url, archive, MAX_DOWNLOAD_BYTES, (done, size) -> {
                downloaded = done;
                total = size;
            });
            if (!sha256.isEmpty() && !sha256.equals(digest)) {
                throw new IOException("Checksum mismatch, expected " + sha256 + " but got " + digest);
            }

            phase = Phase.EXTRACTING;
            deleteRecursively(staging);
            TarGz.extract(archive, staging, 1, MAX_EXTRACTED_BYTES, MAX_ENTRIES);
            if (!Files.isRegularFile(staging.resolve("out/vs/workbench/workbench.web.main.internal.js"))) {
                throw new IOException("The archive does not contain a VS Code Web build");
            }
            Files.writeString(staging.resolve(COMPLETE_MARKER), url + "\n" + digest + "\n");
            deleteRecursively(directory);
            Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
            ready(directory);
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            phase = Phase.FAILED;
            Log.LOGGER.error("Failed to install VS Code Web", e);
        } finally {
            try {
                Files.deleteIfExists(archive);
                deleteRecursively(staging);
            } catch (IOException e) {
                Log.LOGGER.debug("Failed to clean up VS Code Web download", e);
            }
        }
    }

    private void ready(Path directory) {
        root = directory.toAbsolutePath().normalize();
        version = readVersion(directory);
        phase = Phase.READY;
        Log.LOGGER.info("VS Code Web {} is ready", version == null ? "(unknown version)" : version);
    }

    private static @Nullable String readVersion(Path directory) {
        try {
            var json = JsonParser.parseString(Files.readString(directory.resolve("package.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            return json.has("version") ? json.get("version").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> files = Files.walk(path)) {
            for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    private Path cacheDirectory() {
        var directory = cacheDirectory;
        if (directory == null) throw new IllegalStateException("VS Code Web has not been prepared");
        return directory;
    }

    private static String cacheKey(String url, String sha256) {
        if (sha256.length() >= 16) return sha256.substring(0, 16);
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public enum Phase {
        IDLE("idle"),
        DOWNLOADING("downloading"),
        EXTRACTING("extracting"),
        READY("ready"),
        FAILED("failed");

        private final String id;

        Phase(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record Status(Phase phase, String message, double progress) {
    }
}
