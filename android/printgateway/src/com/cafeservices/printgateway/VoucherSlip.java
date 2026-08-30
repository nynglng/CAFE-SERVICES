package com.cafeservices.printgateway;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lays out the voucher portion of the printout.
 *
 * This is the part the customer actually walks away with, so it is built to be
 * read off paper by someone standing at a counter: the code large and alone on
 * its own line, and the rules for using it stated plainly underneath.
 *
 * Widths are in characters, because the printer is in text mode.
 */
public final class VoucherSlip {

    private VoucherSlip() {
    }

    /**
     * @param codes      one entry per voucher sold
     * @param ssid       the network name a guest should join
     * @param hours      access time, counted from first login
     * @param expiresAt  when an unredeemed code stops working
     * @param columns    paper width in characters
     */
    public static String build(List<String> codes, String ssid, int hours,
                               Date expiresAt, int columns) {
        StringBuilder sb = new StringBuilder();
        String heavy = repeat('=', columns);
        String light = repeat('-', columns);

        sb.append(heavy).append('\n');
        sb.append(centre("WIFI ACCESS", columns)).append('\n');
        sb.append(heavy).append('\n');
        sb.append('\n');

        for (int i = 0; i < codes.size(); i++) {
            if (codes.size() > 1) {
                sb.append(centre("voucher " + (i + 1) + " of " + codes.size(), columns))
                        .append('\n');
            }
            // Spaced out so it can be read aloud and typed without squinting.
            sb.append(centre(spaced(codes.get(i)), columns)).append('\n');
            sb.append('\n');
        }

        sb.append(light).append('\n');
        sb.append("  Network    : ").append(ssid).append('\n');
        sb.append("  Good for   : ").append(hours)
                .append(hours == 1 ? " hour" : " hours").append(" once you log in\n");
        if (expiresAt != null) {
            sb.append("  Use before : ")
                    .append(new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)
                            .format(expiresAt))
                    .append('\n');
        }
        sb.append(light).append('\n');
        sb.append("  1. Connect to ").append(ssid).append('\n');
        sb.append("  2. The login page opens by itself\n");
        sb.append("  3. Type the code above\n");
        sb.append('\n');
        sb.append(centre("timer starts on first login", columns)).append('\n');
        sb.append(heavy).append('\n');

        return sb.toString();
    }

    /** Digits in pairs: far easier to read off paper than a solid run. */
    private static String spaced(String code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 2 == 0) {
                sb.append(' ');
            }
            sb.append(code.charAt(i));
        }
        return sb.toString();
    }

    private static String centre(String text, int columns) {
        if (text.length() >= columns) {
            return text;
        }
        return repeat(' ', (columns - text.length()) / 2) + text;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
