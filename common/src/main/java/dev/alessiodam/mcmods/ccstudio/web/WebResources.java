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
    private static final String[] EXTENSION_FILES = { "package.json", "package.nls.json", "dist/extension.js" };
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");

    private final String workbench;
    private final String page;
    private final String pairing;
    private final Map<String, StaticFile> extensionFiles = new HashMap<>();

    WebResources(String version) {
        workbench = text("workbench.html");
        page = text("page.html");
        pairing = text("pair.html");
        for (var name : EXTENSION_FILES) {
            var data = bytes("extension/" + name);
            if (data == null) throw new IllegalStateException("Missing web resource extension/" + name);
            var type = MimeTypes.of(name);
            var etag = "\"" + version + "-" + data.length + "-" + Integer.toHexString(Arrays.hashCode(data)) + "\"";
            extensionFiles.put(name, new StaticFile(data, GzipCache.compress(data, type), type, etag));
        }
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

    private static String text(String name) {
        var data = bytes(name);
        if (data == null) throw new IllegalStateException("Missing web resource " + name);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static byte @Nullable [] bytes(String name) {
        try (var stream = WebResources.class.getResourceAsStream(ROOT + name)) {
            return stream == null ? null : stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record StaticFile(byte[] data, byte @Nullable [] gzipped, String contentType, String etag) {
    }
}
