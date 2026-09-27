package dev.alessiodam.mcmods.ccstudio.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.alessiodam.mcmods.ccstudio.Log;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerStorage;
import dev.alessiodam.mcmods.ccstudio.platform.ReadOnlyStorage;
import dev.alessiodam.mcmods.ccstudio.platform.StorageException;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class SessionFileSystem {
    public static final int TYPE_FILE = 1;
    public static final int TYPE_DIRECTORY = 2;
    public static final int CHANGE_CHANGED = 1;
    public static final int CHANGE_CREATED = 2;
    public static final int CHANGE_DELETED = 3;

    private static final String ROM = "rom";
    private static final String FORBIDDEN_CHARACTERS = "\"*:<>?|\\";
    private static final int MAX_PATH_LENGTH = 1024;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_ROM_READ_BYTES = 4 * 1024 * 1024;
    private static final long MINIMUM_FILE_SIZE = 500;
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final Pattern RESERVED_NAMES = Pattern.compile("^(con|prn|aux|nul|com[0-9]|lpt[0-9]|conin\\$|conout\\$)(\\..*)?$");

    private final ComputerStorage root;
    private final @Nullable ReadOnlyStorage rom;
    private final long maxReadBytes;
    private volatile long lastMutation;
    private @Nullable Map<String, Snapshot> snapshot;

    public SessionFileSystem(ComputerStorage root, @Nullable ReadOnlyStorage rom) {
        this.root = root;
        this.rom = rom;
        this.maxReadBytes = Math.min(Integer.MAX_VALUE - 1024, root.capacity() + 1024 * 1024);
    }

    public static String normalize(String path) throws FsException {
        if (path.length() > MAX_PATH_LENGTH) throw FsException.noPermissions("Path is too long");
        var parts = new ArrayList<String>();
        for (var part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw FsException.noPermissions("Paths may not contain '..'");
            if (WINDOWS && (part.endsWith(".") || part.endsWith(" ") || RESERVED_NAMES.matcher(part.toLowerCase(Locale.ROOT)).matches())) {
                throw FsException.noPermissions("'" + part + "' is not a valid file name");
            }
            for (var i = 0; i < part.length(); i++) {
                var ch = part.charAt(i);
                if (ch < 32 || FORBIDDEN_CHARACTERS.indexOf(ch) >= 0) {
                    throw FsException.noPermissions("ComputerCraft file names cannot contain '" + (ch < 32 ? "\\x" + Integer.toHexString(ch) : ch) + "'");
                }
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }

    public long lastMutation() {
        return lastMutation;
    }

    public synchronized JsonObject stat(String rawPath) throws FsException {
        var path = normalize(rawPath);
        try {
            if (path.isEmpty()) {
                var info = root.info("");
                return stat(TYPE_DIRECTORY, 0, info.modified(), info.created(), false);
            }
            if (isRom(path)) {
                var mount = requireRom(path);
                var sub = romPath(path);
                if (!mount.exists(sub)) throw FsException.notFound(path);
                var directory = mount.isDirectory(sub);
                return stat(directory ? TYPE_DIRECTORY : TYPE_FILE, directory ? 0 : mount.size(sub), 0, 0, true);
            }
            if (!root.exists(path)) throw FsException.notFound(path);
            var info = root.info(path);
            return stat(info.directory() ? TYPE_DIRECTORY : TYPE_FILE, info.directory() ? 0 : info.size(), info.modified(), info.created(), root.isReadOnly(path));
        } catch (IOException e) {
            throw FsException.unavailable(message(e));
        }
    }

    public synchronized JsonArray readDirectory(String rawPath) throws FsException {
        var path = normalize(rawPath);
        var result = new JsonArray();
        try {
            if (isRom(path)) {
                var mount = requireRom(path);
                var sub = romPath(path);
                if (!mount.exists(sub)) throw FsException.notFound(path);
                if (!mount.isDirectory(sub)) throw FsException.notADirectory(path);
                for (var name : list(mount, sub)) result.add(entry(name, mount.isDirectory(join(sub, name)) ? TYPE_DIRECTORY : TYPE_FILE));
                return result;
            }
            if (!root.exists(path)) throw FsException.notFound(path);
            if (!root.isDirectory(path)) throw FsException.notADirectory(path);
            for (var name : list(root, path)) {
                var child = join(path, name);
                if (isRom(child)) continue;
                result.add(entry(name, root.isDirectory(child) ? TYPE_DIRECTORY : TYPE_FILE));
            }
            if (path.isEmpty() && rom != null) result.add(entry(ROM, TYPE_DIRECTORY));
            return result;
        } catch (IOException e) {
            throw FsException.unavailable(message(e));
        }
    }

    public synchronized byte[] readFile(String rawPath) throws FsException {
        var path = normalize(rawPath);
        var inRom = isRom(path);
        ReadOnlyStorage mount = inRom ? requireRom(path) : root;
        var sub = inRom ? romPath(path) : path;
        var limit = inRom ? MAX_ROM_READ_BYTES : maxReadBytes;
        try {
            if (!mount.exists(sub)) throw FsException.notFound(path);
            if (mount.isDirectory(sub)) throw FsException.isADirectory(path);
            try (var channel = mount.openForRead(sub)) {
                if (channel.size() > limit) throw FsException.unavailable("File is too large to open");
                var output = new ByteArrayOutputStream((int) Math.clamp(channel.size(), 0, limit));
                var buffer = ByteBuffer.allocate(8192);
                while (channel.read(buffer) > 0) {
                    output.write(buffer.array(), 0, buffer.position());
                    buffer.clear();
                    if (output.size() > limit) throw FsException.unavailable("File is too large to open");
                }
                return output.toByteArray();
            }
        } catch (IOException e) {
            throw FsException.unavailable(message(e));
        }
    }

    public synchronized void writeFile(String rawPath, byte[] data, boolean create, boolean overwrite) throws FsException {
        var path = requireWritable(normalize(rawPath));
        try {
            var exists = root.exists(path);
            if (exists && root.isDirectory(path)) throw FsException.isADirectory(path);
            if (!exists && !create) throw FsException.notFound(path);
            if (exists && !overwrite) throw FsException.exists(path);
            requireParent(path);
            mutate(() -> root.write(path, data));
        } catch (IOException e) {
            throw FsException.unavailable(message(e));
        }
    }

    public synchronized void createDirectory(String rawPath) throws FsException {
        var path = requireWritable(normalize(rawPath));
        try {
            if (root.exists(path)) throw FsException.exists(path);
            requireParent(path);
            mutate(() -> root.makeDirectory(path));
        } catch (IOException e) {
            throw FsException.unavailable(message(e));
        }
    }

    public synchronized void delete(String rawPath, boolean recursive) throws FsException {
        var path = requireWritable(normalize(rawPath));
        try {
            if (!root.exists(path)) throw FsException.notFound(path);
            if (!recursive && root.isDirectory(path) && !list(root, path).isEmpty()) {
                throw FsException.noPermissions("Directory is not empty: /" + path);
            }
            mutate(() -> root.delete(path));
        } catch (IOException e) {
            throw FsException.unavailable(message(e));
        }
    }

    public synchronized void rename(String rawFrom, String rawTo, boolean overwrite) throws FsException {
        var from = requireWritable(normalize(rawFrom));
        var to = requireWritable(normalize(rawTo));
        if (from.equals(to)) return;
        try {
            if (!root.exists(from)) throw FsException.notFound(from);
            if (to.startsWith(from + "/")) throw FsException.noPermissions("Cannot move a directory inside itself");
            requireParent(to);
            var exists = root.exists(to);
            if (exists && !overwrite) throw FsException.exists(to);
            mutate(() -> {
                if (exists) root.delete(to);
                root.rename(from, to);
            });
        } catch (IOException e) {
            throw FsException.unavailable(message(e));
        }
    }

    public synchronized JsonArray listFiles() throws FsException {
        var result = new JsonArray();
        try {
            walk(root, "", "", result, true);
            if (rom != null) walk(rom, "", ROM, result, false);
        } catch (IOException e) {
            throw FsException.unavailable(message(e));
        }
        return result;
    }

    public synchronized void syncUsage() {
        if (lastMutation == 0) return;
        try {
            root.syncUsage(MINIMUM_FILE_SIZE + usage(""));
        } catch (IOException e) {
            Log.LOGGER.debug("Could not measure disk usage", e);
        }
    }

    public synchronized List<Change> pollChanges() {
        Map<String, Snapshot> current;
        try {
            current = snapshotTree();
        } catch (IOException e) {
            return List.of();
        }
        var previous = snapshot;
        snapshot = current;
        if (previous == null) return List.of();

        var changes = new ArrayList<Change>();
        for (var entry : current.entrySet()) {
            var old = previous.get(entry.getKey());
            if (old == null) {
                changes.add(new Change(CHANGE_CREATED, entry.getKey()));
            } else if (!old.equals(entry.getValue())) {
                changes.add(new Change(CHANGE_CHANGED, entry.getKey()));
            }
        }
        for (var path : previous.keySet()) {
            if (!current.containsKey(path)) changes.add(new Change(CHANGE_DELETED, path));
        }
        return changes;
    }

    private void mutate(StorageAction action) throws IOException {
        lastMutation = System.currentTimeMillis();
        try {
            action.run();
        } finally {
            syncUsage();
        }
    }

    private long usage(String path) throws IOException {
        var total = 0L;
        for (var name : list(root, path)) {
            var child = join(path, name);
            var info = root.info(child);
            total += info.directory() ? MINIMUM_FILE_SIZE + usage(child) : Math.max(MINIMUM_FILE_SIZE, info.size());
        }
        return total;
    }

    private Map<String, Snapshot> snapshotTree() throws IOException {
        var result = new HashMap<String, Snapshot>();
        snapshotTree("", result);
        return result;
    }

    private void snapshotTree(String path, Map<String, Snapshot> result) throws IOException {
        for (var name : list(root, path)) {
            if (result.size() >= MAX_ENTRIES) return;
            var child = join(path, name);
            if (isRom(child)) continue;
            var info = root.info(child);
            result.put("/" + child, new Snapshot(info.directory(), info.directory() ? 0 : info.size(), info.modified()));
            if (info.directory()) snapshotTree(child, result);
        }
    }

    private void walk(ReadOnlyStorage mount, String sub, String prefix, JsonArray result, boolean skipRom) throws IOException {
        for (var name : list(mount, sub)) {
            if (result.size() >= MAX_ENTRIES) return;
            var child = join(sub, name);
            if (skipRom && isRom(child)) continue;
            if (mount.isDirectory(child)) {
                walk(mount, child, prefix, result, skipRom);
            } else {
                result.add("/" + join(prefix, child));
            }
        }
    }

    private boolean isRom(String path) {
        return rom != null && (path.equals(ROM) || path.startsWith(ROM + "/"));
    }

    private ReadOnlyStorage requireRom(String path) throws FsException {
        if (rom == null) throw FsException.notFound(path);
        return rom;
    }

    private static String romPath(String path) {
        return path.equals(ROM) ? "" : path.substring(ROM.length() + 1);
    }

    private String requireWritable(String path) throws FsException {
        if (path.isEmpty()) throw FsException.noPermissions("Cannot modify the root directory");
        if (isRom(path)) throw FsException.noPermissions("/rom is read-only");
        return path;
    }

    private void requireParent(String path) throws IOException, FsException {
        var slash = path.lastIndexOf('/');
        if (slash < 0) return;
        var parent = path.substring(0, slash);
        if (!root.exists(parent)) throw FsException.notFound(parent);
        if (!root.isDirectory(parent)) throw FsException.notADirectory(parent);
    }

    private static List<String> list(ReadOnlyStorage mount, String path) throws IOException {
        var names = new ArrayList<>(mount.list(path));
        names.sort(String::compareTo);
        return names;
    }

    private static String join(String parent, String child) {
        return parent.isEmpty() ? child : parent + "/" + child;
    }

    private static JsonObject stat(int type, long size, long mtime, long ctime, boolean readonly) {
        var result = new JsonObject();
        result.addProperty("type", type);
        result.addProperty("size", size);
        result.addProperty("mtime", mtime);
        result.addProperty("ctime", ctime);
        result.addProperty("readonly", readonly);
        return result;
    }

    private static JsonArray entry(String name, int type) {
        var result = new JsonArray();
        result.add(name);
        result.add(type);
        return result;
    }

    private static String message(IOException e) {
        if (e instanceof StorageException && e.getMessage() != null) return e.getMessage();
        if ("Out of space".equals(e.getMessage())) return "Out of space";
        Log.LOGGER.debug("File operation failed", e);
        return "The file operation failed";
    }

    public record Change(int type, String path) {
    }

    @FunctionalInterface
    private interface StorageAction {
        void run() throws IOException;
    }

    private record Snapshot(boolean directory, long size, long mtime) {
    }
}
