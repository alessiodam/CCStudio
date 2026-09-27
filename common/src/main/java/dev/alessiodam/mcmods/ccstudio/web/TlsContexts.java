package dev.alessiodam.mcmods.ccstudio.web;

import dev.alessiodam.mcmods.ccstudio.platform.Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.KeyManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Locale;

public final class TlsContexts {
    private TlsContexts() {
    }

    public static SslContext create(Settings settings, Path gameDirectory) throws Exception {
        var certificate = resolve(gameDirectory, settings.tlsCertificate(), "tls.certificate");
        var password = settings.tlsPassword();
        var name = certificate.getFileName().toString().toLowerCase(Locale.ROOT);

        if (name.endsWith(".p12") || name.endsWith(".pfx")) {
            var chars = password.toCharArray();
            var keyStore = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(certificate)) {
                keyStore.load(input, chars);
            }
            var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, chars);
            return SslContextBuilder.forServer(keyManagers).build();
        }

        var privateKey = resolve(gameDirectory, settings.tlsPrivateKey(), "tls.privateKey");
        return SslContextBuilder.forServer(certificate.toFile(), privateKey.toFile(), password.isEmpty() ? null : password).build();
    }

    private static Path resolve(Path gameDirectory, String configured, String option) throws IOException {
        if (configured.isBlank()) throw new IOException("TLS is enabled but " + option + " is not set");
        var path = gameDirectory.resolve(configured.trim()).normalize();
        if (!Files.isRegularFile(path)) throw new IOException(option + " points to a missing file: " + path);
        return path;
    }
}
