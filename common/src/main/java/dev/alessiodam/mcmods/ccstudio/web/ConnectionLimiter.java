package dev.alessiodam.mcmods.ccstudio.web;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.concurrent.atomic.AtomicInteger;

final class ConnectionLimiter extends ChannelInboundHandlerAdapter {
    private final AtomicInteger active;
    private final int max;
    private boolean counted;

    ConnectionLimiter(AtomicInteger active, int max) {
        this.active = active;
        this.max = max;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (active.incrementAndGet() > max) {
            active.decrementAndGet();
            ctx.close();
            return;
        }
        counted = true;
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (counted) {
            counted = false;
            active.decrementAndGet();
        }
        super.channelInactive(ctx);
    }
}
