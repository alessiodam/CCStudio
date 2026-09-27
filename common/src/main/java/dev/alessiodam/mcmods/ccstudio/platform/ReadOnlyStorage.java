package dev.alessiodam.mcmods.ccstudio.platform;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.List;

public interface ReadOnlyStorage {
    boolean exists(String path) throws IOException;

    boolean isDirectory(String path) throws IOException;

    List<String> list(String path) throws IOException;

    long size(String path) throws IOException;

    SeekableByteChannel openForRead(String path) throws IOException;
}
