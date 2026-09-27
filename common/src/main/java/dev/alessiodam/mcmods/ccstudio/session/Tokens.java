package dev.alessiodam.mcmods.ccstudio.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Tokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32}");
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private Tokens() {
    }

    public static String newToken() {
        var bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    public static boolean isToken(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String newPairingCode() {
        var code = new StringBuilder(9);
        for (var i = 0; i < 8; i++) {
            if (i == 4) code.append('-');
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    public static String normalizePairingCode(String code) {
        var result = new StringBuilder(8);
        for (var ch : code.toUpperCase(Locale.ROOT).toCharArray()) {
            if (CODE_ALPHABET.indexOf(ch) >= 0) result.append(ch);
        }
        return result.toString();
    }
}
