package dev.alessiodam.mcmods.ccstudio.platform;

import java.io.IOException;

public interface ComputerStorage extends ReadOnlyStorage {
    long capacity();

    FileInfo info(String path) throws IOException;

    boolean isReadOnly(String path) throws IOException;

    void write(String path, byte[] data) throws IOException;

    void makeDirectory(String path) throws IOException;

    void delete(String path) throws IOException;

    void rename(String from, String to) throws IOException;

    void syncUsage(long realUsage);
}
