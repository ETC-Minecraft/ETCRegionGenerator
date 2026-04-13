package com.etcmc.regiongenerator.util;

import com.etcmc.regiongenerator.ETCRegionGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Thin wrapper around MiniMessage for consistent prefix/formatting.
 */
public final class MessageUtil {

    private final String prefix;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public MessageUtil(ETCRegionGenerator plugin) {
        this.prefix = plugin.getConfig().getString(
            "messages.prefix",
            "<dark_gray>[<gradient:#00e0ff:#00ff99>ETCRGen</gradient>]</dark_gray> "
        );
    }

    /** Parse a MiniMessage string into a Component. */
    public Component parse(String miniMessage) {
        return mm.deserialize(miniMessage);
    }

    /** Prefix string (MiniMessage format). */
    public String getPrefix() {
        return prefix;
    }

    /** Escape raw user input so it cannot inject MiniMessage tags. */
    public String escape(String input) {
        return mm.escapeTags(input);
    }
}
