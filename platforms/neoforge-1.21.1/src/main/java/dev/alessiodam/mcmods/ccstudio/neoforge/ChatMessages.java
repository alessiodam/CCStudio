package dev.alessiodam.mcmods.ccstudio.neoforge;

import dan200.computercraft.shared.computer.core.ServerComputer;
import dev.alessiodam.mcmods.ccstudio.util.Names;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;

final class ChatMessages {
    private ChatMessages() {
    }

    static int sendLink(MinecraftServer server, ServerComputer computer, String url) {
        var viewers = Computers.viewers(server, computer);
        if (viewers.isEmpty()) return 0;
        var open = Component.literal("[Open editor]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url))));
        var copy = Component.literal("[Copy link]").withStyle(style -> style
                .withColor(ChatFormatting.GRAY)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Copy to clipboard"))));
        var message = Component.empty()
                .append(prefix())
                .append(Component.literal(Names.describe(computer.getID(), computer.getLabel()) + " ").withStyle(ChatFormatting.WHITE))
                .append(open)
                .append(Component.literal(" "))
                .append(copy);
        for (var player : viewers) player.sendSystemMessage(message);
        return viewers.size();
    }

    static void sendPairingNotice(MinecraftServer server, ServerComputer computer, String command, String address, String browser) {
        var viewers = Computers.viewers(server, computer);
        if (viewers.isEmpty()) return;
        var details = Component.literal("From " + address + "\n" + browser);
        var message = Component.empty()
                .append(prefix())
                .append(Component.literal("A browser wants to open " + Names.describe(computer.getID(), computer.getLabel()) + ". ").withStyle(style -> style
                        .withColor(ChatFormatting.WHITE)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, details))))
                .append(Component.literal("If that was you, run ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(command).withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Copy to clipboard")))))
                .append(Component.literal(" on the computer.").withStyle(ChatFormatting.GRAY));
        for (var player : viewers) player.sendSystemMessage(message);
    }

    private static Component prefix() {
        return Component.literal("[CC: Studio] ").withStyle(ChatFormatting.DARK_AQUA);
    }
}
