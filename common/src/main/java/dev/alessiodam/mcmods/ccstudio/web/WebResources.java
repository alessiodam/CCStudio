package dev.alessiodam.mcmods.ccstudio.web;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WebResources {
    private static final String ROOT = "/ccstudio/web/";
    private static final String LOGO = "/ccstudio/logo.png";
    private static final String[] EXTENSION_FILES = { "package.json", "package.nls.json", "dist/extension.js", "icon.png" };
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");

    private final String workbench;
    private final String page;
    private final String pairing;
    private final StaticFile logo;
    private final Map<String, StaticFile> extensionFiles = new HashMap<>();

    WebResources(String version) {
        workbench = text("workbench.html");
        page = text("page.html");
        pairing = text("pair.html");
        logo = staticFile(LOGO, version);
        for (var name : EXTENSION_FILES) extensionFiles.put(name, staticFile(ROOT + "extension/" + name, version));
    }

    String workbench(Map<String, String> values) {
        return render(workbench, values);
    }

    String page(Map<String, String> values) {
        return render(page, values);
    }

    String pairing(Map<String, String> values) {
        return render(pairing, values);
    }

    StaticFile logo() {
        return logo;
    }

    @Nullable StaticFile extensionFile(String relative) {
        return extensionFiles.get(relative);
    }

    static String escape(String value) {
        var result = new StringBuilder(value.length());
        for (var i = 0; i < value.length(); i++) {
            var ch = value.charAt(i);
            switch (ch) {
                case '<' -> result.append("&lt;");
                case '>' -> result.append("&gt;");
                case '&' -> result.append("&amp;");
                case '"' -> result.append("&quot;");
                case '\'' -> result.append("&#39;");
                default -> result.append(ch);
            }
        }
        return result.toString();
    }

    private static String render(String template, Map<String, String> values) {
        var matcher = PLACEHOLDER.matcher(template);
        var result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static StaticFile staticFile(String path, String version) {
        var data = bytes(path);
        if (data == null) throw new IllegalStateException("Missing web resource " + path);
        var type = MimeTypes.of(path);
        var etag = "\"" + version + "-" + data.length + "-" + Integer.toHexString(Arrays.hashCode(data)) + "\"";
        return new StaticFile(data, GzipCache.compress(data, type), type, etag);
    }

    private static String text(String name) {
        var data = bytes(ROOT + name);
        if (data == null) throw new IllegalStateException("Missing web resource " + name);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static byte @Nullable [] bytes(String path) {
        try (var stream = WebResources.class.getResourceAsStream(path)) {
            return stream == null ? null : stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record StaticFile(byte[] data, byte @Nullable [] gzipped, String contentType, String etag) {
    }
}
