package com.cafeservices.printgateway;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Everything about a receipt that depends on how wide the paper is.
 *
 * The first client is 58mm and the numbers below were measured against their
 * prints; the 80mm set was measured the same way, earlier, against an 80mm
 * test print. Both are kept because the next cafe is a coin toss, and because
 * getting the width wrong does not degrade gracefully - at the wrong setting
 * NOTHING parses, since every item row is cut in the wrong place.
 *
 * Type sizes and line pitches are deliberately absent: they are identical on
 * both papers. Only widths change.
 */
public final class PaperProfile {

    /** 58mm at 203dpi, 48mm of printable width. The first client's paper. */
    public static final PaperProfile NARROW = new PaperProfile(
            "58mm", 32, 22, 384, 382, 291);

    /** 80mm at 203dpi, the other size a thermal printer comes in. */
    public static final PaperProfile WIDE = new PaperProfile(
            "80mm", 48, 38, 576, 574, 478);

    private static final PaperProfile[] ALL = {NARROW, WIDE};

    /** A row that ends in an amount, which is what a correct cut produces. */
    private static final Pattern ENDS_IN_AMOUNT =
            Pattern.compile(".*\\S\\s\\s+[0-9,]+\\.[0-9]{2}$");

    public final String name;

    /** Loyverse's row width in text mode. */
    public final int columns;

    /** Width of the item-name field before the amount column. */
    public final int nameField;

    /** Dots across the printable area. */
    public final int paperWidth;

    /** Where a right-aligned amount ends. */
    public final int marginRight;

    /** Where an item name wraps in Loyverse's graphic output. */
    public final int nameColumnRight;

    private PaperProfile(String name, int columns, int nameField, int paperWidth,
                         int marginRight, int nameColumnRight) {
        this.name = name;
        this.columns = columns;
        this.nameField = nameField;
        this.paperWidth = paperWidth;
        this.marginRight = marginRight;
        this.nameColumnRight = nameColumnRight;
    }

    /**
     * Work out which paper a print came off, by cutting it both ways and
     * seeing which one lands its amounts at the end of a row.
     *
     * Loyverse pads every row to the full width and right-aligns amounts
     * against it, so the correct width leaves rows ending in an amount and the
     * wrong one leaves those amounts stranded mid-row. That is a sharp enough
     * difference to decide on, and it needs no setting anyone could get wrong
     * at a cafe we have never visited.
     *
     * Falls back to the configured profile when a print says nothing either
     * way - a receipt with no amounts on it at all.
     */
    public static PaperProfile detect(EscPosDecoder.Result decoded) {
        PaperProfile best = null;
        int bestScore = 0;
        for (PaperProfile profile : ALL) {
            int score = profile.score(decoded);
            if (score > bestScore) {
                bestScore = score;
                best = profile;
            }
        }
        return best == null ? configured() : best;
    }

    /** How many rows end in an amount when the print is cut at this width. */
    private int score(EscPosDecoder.Result decoded) {
        int hits = 0;
        for (EscPosDecoder.Line line : decoded.lines) {
            for (String row : ReceiptParser.reflow(line.text, this)) {
                String trimmed = trimRight(row);
                if (trimmed.length() > 0 && ENDS_IN_AMOUNT.matcher(trimmed).matches()) {
                    hits++;
                }
            }
        }
        return hits;
    }

    /** The paper the operator chose, for when a print cannot say. */
    public static PaperProfile configured() {
        return WIDE.name.equals(Settings.paperName()) ? WIDE : NARROW;
    }

    public static List<String> names() {
        List<String> out = new java.util.ArrayList<String>();
        for (PaperProfile profile : ALL) {
            out.add(profile.name);
        }
        return out;
    }

    private static String trimRight(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }
}
