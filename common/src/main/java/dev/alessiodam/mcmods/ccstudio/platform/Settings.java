package dev.alessiodam.mcmods.ccstudio.platform;

public record Settings(
        String bindAddress,
        int port,
        String publicUrl,
        int maxConnections,
        boolean tlsEnabled,
        String tlsCertificate,
        String tlsPrivateKey,
        String tlsPassword,
        String tunnelToken,
        String cloudflaredPath,
        int maxSessions,
        int maxConnectionsPerSession,
        int idleTimeoutMinutes,
        int maxLifetimeHours,
        boolean chatLinks,
        boolean showRom,
        boolean allowCommandComputers,
        String vscodeUrl,
        String vscodeSha256,
        boolean openVsx
) {
    public static final String DEFAULT_VSCODE_URL = "https://vscode.download.prss.microsoft.com/dbazure/download/stable/04c0d99f4fb0d8afe6ce4f0c58e31e183ac3e4b1/vscode-web.tar.gz";
    public static final String DEFAULT_VSCODE_SHA256 = "cbbe6d79a59f0e7d6efd2253c453d40dbd2a36416c38727a050bafc4edd06226";
}
