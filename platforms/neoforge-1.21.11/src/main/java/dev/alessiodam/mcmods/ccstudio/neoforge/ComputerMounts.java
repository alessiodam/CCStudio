package dev.alessiodam.mcmods.ccstudio.neoforge;

import dan200.computercraft.api.filesystem.WritableMount;
import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.core.filesystem.FileSystem;
import dan200.computercraft.core.filesystem.WritableFileMount;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dev.alessiodam.mcmods.ccstudio.Log;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;

final class ComputerMounts {
    private static final @Nullable Field COMPUTER = field(ServerComputer.class, "computer");
    private static final @Nullable Field EXECUTOR = field(Computer.class, "executor");
    private static final @Nullable Field ROOT_MOUNT = EXECUTOR == null ? null : field(EXECUTOR.getType(), "rootMount");
    private static final @Nullable Field USED_SPACE = field(WritableFileMount.class, "usedSpace");
    private static final boolean AVAILABLE = COMPUTER != null && EXECUTOR != null && ROOT_MOUNT != null && USED_SPACE != null;

    private ComputerMounts() {
    }

    public static boolean available() {
        return AVAILABLE;
    }

    public static Access access(ServerComputer computer) {
        if (!AVAILABLE) return Access.UNAVAILABLE;
        try {
            var core = (Computer) COMPUTER.get(computer);
            var executor = EXECUTOR.get(core);
            var mount = (WritableMount) ROOT_MOUNT.get(executor);
            FileSystem fileSystem = null;
            if (core.isOn()) {
                try {
                    fileSystem = core.getAPIEnvironment().getFileSystem();
                } catch (IllegalStateException ignored) {
                }
            }
            return new Access(true, mount, fileSystem);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.LOGGER.debug("Could not access the computer's file system", e);
            return Access.UNAVAILABLE;
        }
    }

    public static void raiseUsedSpace(@Nullable WritableMount mount, long realUsage) {
        if (!AVAILABLE || !(mount instanceof WritableFileMount fileMount)) return;
        try {
            if (USED_SPACE.getLong(fileMount) < realUsage) USED_SPACE.setLong(fileMount, realUsage);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.LOGGER.debug("Could not update the computer's disk usage", e);
        }
    }

    private static @Nullable Field field(Class<?> owner, String name) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.LOGGER.error("CC: Studio cannot access {}.{}; editing loaded computers is disabled. Update CC: Studio for this CC: Tweaked version.", owner.getName(), name);
            return null;
        }
    }

    public record Access(boolean ok, @Nullable WritableMount rootMount, @Nullable FileSystem fileSystem) {
        static final Access UNAVAILABLE = new Access(false, null, null);
    }
}
