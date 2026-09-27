package dev.alessiodam.mcmods.ccstudio.session;

public final class Pairing {
    private final String code;
    private final String address;
    private final String browser;
    private final long created = System.currentTimeMillis();
    private volatile boolean approved;

    Pairing(String code, String address, String browser) {
        this.code = code;
        this.address = address;
        this.browser = browser;
    }

    public String code() {
        return code;
    }

    public String address() {
        return address;
    }

    public String browser() {
        return browser;
    }

    public long created() {
        return created;
    }

    public boolean approved() {
        return approved;
    }

    void approve() {
        approved = true;
    }

    boolean isExpired(long now, long lifetimeMillis) {
        return now - created > lifetimeMillis;
    }
}
