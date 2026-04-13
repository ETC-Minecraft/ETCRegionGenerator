package com.etcmc.regiongenerator.util;

/**
 * Builds a MiniMessage-coloured ASCII progress bar.
 *
 * <p>Example output: <code><green>██████████<dark_gray>░░░░░░░░░░</code></p>
 */
public final class ProgressBar {

    private ProgressBar() {}

    /**
     * @param percent  0.0 – 100.0
     * @param width    total number of characters in the bar
     * @return MiniMessage string, no trailing reset tag needed
     */
    public static String build(double percent, int width) {
        int filled = (int) Math.round(percent / 100.0 * width);
        filled = Math.max(0, Math.min(width, filled));

        String color;
        if (percent >= 75) {
            color = "<green>";
        } else if (percent >= 40) {
            color = "<yellow>";
        } else {
            color = "<red>";
        }

        return color
                + "█".repeat(filled)
                + "<dark_gray>"
                + "░".repeat(width - filled)
                + "</dark_gray>";
    }
}
