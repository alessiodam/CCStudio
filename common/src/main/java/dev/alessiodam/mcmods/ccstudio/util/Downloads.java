package dev.alessiodam.mcmods.ccstudio.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

public final class Downloads {
    private Downloads() {
    }

    public static String download(String url, Path target, long maxBytes, Progress progress) throws IOException, InterruptedException {
        var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "CCStudio")
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Download failed with HTTP " + response.statusCode());
        }
        var total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (total > maxBytes) {
            response.body().close();
            throw new IOException("The download is larger than " + (maxBytes >> 20) + " MB");
        }

        var digest = sha256();
        var downloaded = 0L;
        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(target)) {
            var buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                downloaded += read;
                if (downloaded > maxBytes) throw new IOException("The download is larger than " + (maxBytes >> 20) + " MB");
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                progress.update(downloaded, total);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(Path file) throws IOException {
        var digest = sha256();
        try (var input = Files.newInputStream(file)) {
            var buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    public interface Progress {
        void update(long downloaded, long total);
    }
}
