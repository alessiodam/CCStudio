package dev.alessiodam.mcmods.ccstudio.session;

final class RateLimiter {
    private final double perSecond;
    private final double burst;
    private double tokens;
    private long last = System.nanoTime();

    RateLimiter(double perSecond, double burst) {
        this.perSecond = perSecond;
        this.burst = burst;
        this.tokens = burst;
    }

    synchronized boolean tryAcquire(double cost) {
        var now = System.nanoTime();
        tokens = Math.min(burst, tokens + (now - last) / 1e9 * perSecond);
        last = now;
        if (tokens < cost) return false;
        tokens -= cost;
        return true;
    }
}
