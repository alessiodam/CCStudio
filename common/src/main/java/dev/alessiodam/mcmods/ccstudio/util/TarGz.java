package dev.alessiodam.mcmods.ccstudio.util;

import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

public final class TarGz {
    private static final int BLOCK = 512;

    private TarGz() {
    }

    public static void extract(Path archive, Path target, int stripComponents, long maxBytes, int maxEntries) throws IOException {
        Files.createDirectories(target);
        var extracted = 0L;
        var entries = 0;
        try (var input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(archive), 65536), 65536))) {
            var header = new byte[BLOCK];
            String pendingName = null;
            while (readHeader(input, header)) {
                var size = size(header);
                var type = (char) header[156];
                var name = pendingName != null ? pendingName : name(header);
                pendingName = null;
                if (++entries > maxEntries) throw new IOException("Archive has too many entries");

                switch (type) {
                    case 'L' -> pendingName = cString(readData(input, size));
                    case 'x' -> pendingName = paxPath(readData(input, size));
                    case '0', '\0', '7' -> {
                        extracted += size;
                        if (extracted > maxBytes) throw new IOException("Archive is too large");
                        var destination = destination(target, name, stripComponents);
                        if (destination == null) {
                            skip(input, size);
                        } else {
                            Files.createDirectories(destination.getParent());
                            try (var output = Files.newOutputStream(destination)) {
                                copy(input, output, size);
                            }
                            skipPadding(input, size);
                        }
                    }
                    case '5' -> {
                        var destination = destination(target, name, stripComponents);
                        if (destination != null) Files.createDirectories(destination);
                        skip(input, size);
                    }
                    default -> skip(input, size);
                }
            }
        }
    }

    private static boolean readHeader(DataInputStream input, byte[] header) throws IOException {
        try {
            input.readFully(header);
        } catch (EOFException e) {
            return false;
        }
        for (var value : header) {
            if (value != 0) return true;
        }
        return false;
    }

    private static @Nullable Path destination(Path target, String name, int stripComponents) throws IOException {
        var parts = name.replace('\\', '/').split("/");
        var relative = new StringBuilder();
        var skipped = 0;
        for (var part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (skipped < stripComponents) {
                skipped++;
                continue;
            }
            if (!relative.isEmpty()) relative.append('/');
            relative.append(part);
        }
        if (relative.isEmpty()) return null;
        var destination = target.resolve(relative.toString()).normalize();
        if (!destination.startsWith(target)) throw new IOException("Refusing to extract " + name);
        return destination;
    }

    private static String name(byte[] header) {
        var name = cString(header, 0, 100);
        var magic = cString(header, 257, 6);
        if (magic.startsWith("ustar")) {
            var prefix = cString(header, 345, 155);
            if (!prefix.isEmpty()) return prefix + "/" + name;
        }
        return name;
    }

    private static long size(byte[] header) throws IOException {
        if ((header[124] & 0x80) != 0) {
            long value = header[124] & 0x7F;
            for (var i = 125; i < 136; i++) value = (value << 8) | (header[i] & 0xFF);
            return value;
        }
        var text = cString(header, 124, 12).trim();
        if (text.isEmpty()) return 0;
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            throw new IOException("Corrupt tar header size: " + text);
        }
    }

    private static @Nullable String paxPath(byte[] data) {
        var text = new String(data, StandardCharsets.UTF_8);
        var offset = 0;
        while (offset < text.length()) {
            var space = text.indexOf(' ', offset);
            if (space < 0) break;
            int length;
            try {
                length = Integer.parseInt(text.substring(offset, space));
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0) break;
            var record = new String(data, offset, Math.min(length, data.length - offset), StandardCharsets.UTF_8);
            var content = record.substring(record.indexOf(' ') + 1).stripTrailing();
            if (content.startsWith("path=")) return content.substring(5);
            offset += record.length();
        }
        return null;
    }

    private static String cString(byte[] data) {
        return cString(data, 0, data.length);
    }

    private static String cString(byte[] data, int offset, int length) {
        var end = offset;
        while (end < offset + length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static byte[] readData(DataInputStream input, long size) throws IOException {
        if (size > 16 * 1024 * 1024) throw new IOException("Tar header record is too large");
        var data = new byte[(int) size];
        input.readFully(data);
        skipPadding(input, size);
        return data;
    }

    private static void copy(InputStream input, OutputStream output, long size) throws IOException {
        var buffer = new byte[65536];
        var remaining = size;
        while (remaining > 0) {
            var read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new EOFException("Unexpected end of archive");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skip(DataInputStream input, long size) throws IOException {
        input.skipNBytes(size);
        skipPadding(input, size);
    }

    private static void skipPadding(DataInputStream input, long size) throws IOException {
        var padding = (BLOCK - size % BLOCK) % BLOCK;
        if (padding > 0) input.skipNBytes(padding);
    }
}
