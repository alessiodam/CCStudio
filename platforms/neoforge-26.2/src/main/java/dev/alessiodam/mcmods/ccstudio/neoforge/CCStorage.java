package dev.alessiodam.mcmods.ccstudio.neoforge;

import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.filesystem.FileOperationException;
import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.WritableMount;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerHandle;
import dev.alessiodam.mcmods.ccstudio.platform.ComputerStorage;
import dev.alessiodam.mcmods.ccstudio.platform.FileInfo;
import dev.alessiodam.mcmods.ccstudio.platform.ReadOnlyStorage;
import dev.alessiodam.mcmods.ccstudio.platform.StorageException;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

final class CCStorage implements ComputerStorage {
    private final MinecraftServer server;
    private final int computerId;
    private final WritableMount readMount;
    private final long capacity;
    private final Supplier<@Nullable ComputerHandle> current;

    CCStorage(MinecraftServer server, CCComputer computer, Supplier<@Nullable ComputerHandle> current) {
        this.server = server;
        this.computerId = computer.id();
        this.readMount = computer.computer.createRootMount();
        this.capacity = readMount.getCapacity();
        this.current = current;
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public boolean exists(String path) throws IOException {
        return call(() -> readMount.exists(path));
    }

    @Override
    public boolean isDirectory(String path) throws IOException {
        return call(() -> readMount.isDirectory(path));
    }

    @Override
    public List<String> list(String path) throws IOException {
        return call(() -> list(readMount, path));
    }

    @Override
    public long size(String path) throws IOException {
        return call(() -> readMount.getSize(path));
    }

    @Override
    public SeekableByteChannel openForRead(String path) throws IOException {
        return call(() -> readMount.openForRead(path));
    }

    @Override
    public FileInfo info(String path) throws IOException {
        return call(() -> {
            var attributes = readMount.getAttributes(path);
            return new FileInfo(attributes.isDirectory(), attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.creationTime().toMillis());
        });
    }

    @Override
    public boolean isReadOnly(String path) throws IOException {
        return call(() -> readMount.isReadOnly(path));
    }

    @Override
    public void write(String path, byte[] data) throws IOException {
        withWritableMount(mount -> {
            try (var channel = mount.openFile(path, Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
                var buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
        });
    }

    @Override
    public void makeDirectory(String path) throws IOException {
        withWritableMount(mount -> mount.makeDirectory(path));
    }

    @Override
    public void delete(String path) throws IOException {
        withWritableMount(mount -> mount.delete(path));
    }

    @Override
    public void rename(String from, String to) throws IOException {
        withWritableMount(mount -> mount.rename(from, to));
    }

    @Override
    public void syncUsage(long realUsage) {
        if (current.get() instanceof CCComputer computer) ComputerMounts.raiseUsedSpace(ComputerMounts.access(computer.computer).rootMount(), realUsage);
    }

    private void withWritableMount(MountAction action) throws IOException {
        call(() -> {
            if (!(current.get() instanceof CCComputer computer)) {
                action.run(freshMount());
                return null;
            }
            var access = ComputerMounts.access(computer.computer);
            if (!access.ok()) throw new StorageException("Editing a loaded computer is not supported with this CC: Tweaked version.");
            var mount = access.rootMount();
            if (mount == null) {
                action.run(freshMount());
                return null;
            }
            var fileSystem = access.fileSystem();
            if (fileSystem == null) {
                action.run(mount);
            } else {
                synchronized (fileSystem) {
                    action.run(mount);
                }
            }
            return null;
        });
    }

    private WritableMount freshMount() {
        return ComputerCraftAPI.createSaveDirMount(server, "computer/" + computerId, capacity);
    }

    static List<String> list(Mount mount, String path) throws IOException {
        var names = new ArrayList<String>();
        mount.list(path, names);
        return names;
    }

    static <T> T call(IOCall<T> call) throws IOException {
        try {
            return call.run();
        } catch (FileOperationException e) {
            throw new StorageException(e.getMessage() == null ? "The file operation failed" : e.getMessage());
        }
    }

    @FunctionalInterface
    interface IOCall<T> {
        T run() throws IOException;
    }

    @FunctionalInterface
    private interface MountAction {
        void run(WritableMount mount) throws IOException;
    }

    record Rom(Mount mount) implements ReadOnlyStorage {
        @Override
        public boolean exists(String path) throws IOException {
            return call(() -> mount.exists(path));
        }

        @Override
        public boolean isDirectory(String path) throws IOException {
            return call(() -> mount.isDirectory(path));
        }

        @Override
        public List<String> list(String path) throws IOException {
            return call(() -> CCStorage.list(mount, path));
        }

        @Override
        public long size(String path) throws IOException {
            return call(() -> mount.getSize(path));
        }

        @Override
        public SeekableByteChannel openForRead(String path) throws IOException {
            return call(() -> mount.openForRead(path));
        }
    }
}
