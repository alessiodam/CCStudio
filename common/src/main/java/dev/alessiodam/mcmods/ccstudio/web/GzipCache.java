package dev.alessiodam.mcmods.ccstudio.web;

import dev.alessiodam.mcmods.ccstudio.Log;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

final class GzipCache {
    static final String SUFFIX = ".ccstudio.gz";
    private static final long MIN_SIZE = 1024;

    private final Map<Path, Object> locks = new ConcurrentHashMap<>();

    @Nullable Path compressed(Path file, String contentType) {
        if (!isCompressible(contentType)) return null;
        try {
            if (Files.size(file) < MIN_SIZE) return null;
            var target = file.resolveSibling(file.getFileName() + SUFFIX);
            if (Files.isRegularFile(target)) return target;
            synchronized (locks.computeIfAbsent(file, key -> new Object())) {
                if (Files.isRegularFile(target)) return target;
                var temp = Files.createTempFile(file.getParent(), ".ccstudio-", ".tmp");
                try {
                    try (var input = Files.newInputStream(file); var output = new GZIPOutputStream(Files.newOutputStream(temp), 65536)) {
                        input.transferTo(output);
                    }
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temp);
                }
                return target;
            }
        } catch (IOException e) {
            Log.LOGGER.debug("Failed to compress {}", file, e);
            return null;
        }
    }

    static byte @Nullable [] compress(byte[] data, String contentType) {
        if (!isCompressible(contentType) || data.length < MIN_SIZE) return null;
        var output = new ByteArrayOutputStream(data.length / 3);
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(data);
        } catch (IOException e) {
            return null;
        }
        return output.toByteArray();
    }

    static boolean isCompressible(String contentType) {
        var type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/")
                || type.startsWith("application/json")
                || type.startsWith("image/svg+xml")
                || type.startsWith("font/ttf")
                || type.startsWith("application/wasm");
    }
}
