package dev.alessiodam.mcmods.ccstudio.session;

public final class FsException extends Exception {
    private final String code;

    public FsException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static FsException notFound(String path) {
        return new FsException("FileNotFound", "No such file or directory: /" + path);
    }

    public static FsException exists(String path) {
        return new FsException("FileExists", "File already exists: /" + path);
    }

    public static FsException notADirectory(String path) {
        return new FsException("FileNotADirectory", "Not a directory: /" + path);
    }

    public static FsException isADirectory(String path) {
        return new FsException("FileIsADirectory", "Is a directory: /" + path);
    }

    public static FsException noPermissions(String message) {
        return new FsException("NoPermissions", message);
    }

    public static FsException unavailable(String message) {
        return new FsException("Unavailable", message);
    }
}
