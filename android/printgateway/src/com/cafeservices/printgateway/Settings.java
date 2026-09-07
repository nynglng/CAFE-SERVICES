package com.cafeservices.printgateway;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the voucher slip says, kept where the counter staff can change it.
 *
 * These three values differ per client and per plan, and getting one wrong
 * prints a slip that lies to a paying customer, so they are settings rather
 * than constants: the network to join, how long access lasts once redeemed,
 * and how long an unredeemed code stays alive.
 *
 * Held in SharedPreferences so a choice survives the app being killed - the
 * gateway runs unattended for a whole shift and is restarted by the system
 * more often than by anyone touching it.
 */
public final class Settings {

    private static final String FILE = "gateway";
    private static final String KEY_SSID = "ssid";
    private static final String KEY_ACCESS_HOURS = "accessHours";
    private static final String KEY_EXPIRY_HOURS = "expiryHours";
    private static final String KEY_NETWORKS = "networks";
    private static final String KEY_ACCOUNT_ID = "omadacId";
    private static final String KEY_SITE_ID = "siteId";
    private static final String KEY_SITE_NAME = "siteName";
    private static final String KEY_SITES = "sites";
    private static final String KEY_PAPER = "paper";
    private static final String KEY_PRINTER_LABEL = "printerLabel";
    private static final String KEY_PRINT_WIDTH_MM = "printWidthMm";
    private static final String KEY_DPI = "dpi";
    private static final String KEY_INIT_CMD = "initCommands";
    private static final String KEY_CUTTER_CMD = "cutterCommands";
    private static final String KEY_DRAWER_CMD = "drawerCommands";
    private static final String KEY_PRINT_RECEIPTS = "printReceipts";
    private static final String KEY_PRINTER_NAME = "printerName";
    private static final String KEY_PRINTER_ADDRESS = "printerAddress";

    /** The client this was built for; the first thing the list should offer. */
    public static final String DEFAULT_SSID = "CAFE";
    public static final int DEFAULT_ACCESS_HOURS = 2;

    /**
     * Must agree with VOUCHER_VALID_HOURS on the Omada side, which is what
     * actually decides when a code dies. This only prints the claim.
     */
    public static final int DEFAULT_EXPIRY_HOURS = 24;

    /** Offered in the dropdowns. Anything else is typed in as a new network. */
    public static final int[] ACCESS_CHOICES = {1, 2, 3, 4, 6, 8, 12, 24};
    public static final int[] EXPIRY_CHOICES = {6, 12, 24, 48, 72};

    /**
     * The account and cafe this gateway issues for. Both are learned at
     * sign-in rather than compiled in - a gateway on one cafe's counter
     * issuing another cafe's codes would fail silently, since the codes are
     * created happily and simply do not work on the network in the room.
     *
     * The values below are only what a fresh install assumes before anyone has
     * signed in; the first connection replaces them.
     */
    public static final String DEFAULT_ACCOUNT_ID = "<omada-account-id>";
    public static final String DEFAULT_SITE_ID = "<omada-site-id>";

    private static SharedPreferences prefs;

    private Settings() {
    }

    public static void init(Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext()
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    public static String ssid() {
        return prefs == null ? DEFAULT_SSID : prefs.getString(KEY_SSID, DEFAULT_SSID);
    }

    public static int accessHours() {
        return prefs == null ? DEFAULT_ACCESS_HOURS
                : prefs.getInt(KEY_ACCESS_HOURS, DEFAULT_ACCESS_HOURS);
    }

    public static int expiryHours() {
        return prefs == null ? DEFAULT_EXPIRY_HOURS
                : prefs.getInt(KEY_EXPIRY_HOURS, DEFAULT_EXPIRY_HOURS);
    }

    public static void setSsid(String value) {
        if (prefs != null) {
            prefs.edit().putString(KEY_SSID, value).apply();
        }
    }

    public static void setAccessHours(int value) {
        if (prefs != null) {
            prefs.edit().putInt(KEY_ACCESS_HOURS, value).apply();
        }
    }

    public static void setExpiryHours(int value) {
        if (prefs != null) {
            prefs.edit().putInt(KEY_EXPIRY_HOURS, value).apply();
        }
    }

    /**
     * Every network offered in the dropdown: the ones typed in so far, plus
     * the default and whatever is currently chosen, so the list can never fail
     * to contain the setting it is showing.
     */
    public static List<String> networks() {
        Set<String> all = new LinkedHashSet<String>();
        all.add(DEFAULT_SSID);
        if (prefs != null) {
            all.addAll(prefs.getStringSet(KEY_NETWORKS, new LinkedHashSet<String>()));
        }
        all.add(ssid());
        return new ArrayList<String>(all);
    }

    public static String accountId() {
        return prefs == null ? DEFAULT_ACCOUNT_ID
                : prefs.getString(KEY_ACCOUNT_ID, DEFAULT_ACCOUNT_ID);
    }

    public static void setAccountId(String value) {
        if (prefs != null && value != null && value.length() > 0) {
            prefs.edit().putString(KEY_ACCOUNT_ID, value).apply();
        }
    }

    public static String siteId() {
        return prefs == null ? DEFAULT_SITE_ID : prefs.getString(KEY_SITE_ID, DEFAULT_SITE_ID);
    }

    /** For the screen: the cafe by name, so a wrong site is obvious. */
    public static String siteName() {
        return prefs == null ? "" : prefs.getString(KEY_SITE_NAME, "");
    }

    public static void setSite(String id, String name) {
        if (prefs != null && id != null && id.length() > 0) {
            prefs.edit().putString(KEY_SITE_ID, id)
                    .putString(KEY_SITE_NAME, name == null ? "" : name).apply();
        }
    }

    /**
     * The cafes this account can see, as "id|name" rows, refreshed whenever
     * Omada is reachable so the picker works offline too.
     */
    public static List<String> knownSites() {
        List<String> out = new ArrayList<String>();
        if (prefs == null) {
            return out;
        }
        out.addAll(prefs.getStringSet(KEY_SITES, new LinkedHashSet<String>()));
        return out;
    }

    public static void setKnownSites(List<String> rows) {
        if (prefs != null) {
            prefs.edit().putStringSet(KEY_SITES,
                    new LinkedHashSet<String>(rows)).apply();
        }
    }

    /**
     * The paper size, for prints that cannot say which they came off.
     *
     * Normally nothing reads this: PaperProfile.detect works it out from the
     * print itself, which is what makes a new cafe a matter of installing the
     * app rather than editing it.
     */
    public static String paperName() {
        return prefs == null ? PaperProfile.NARROW.name
                : prefs.getString(KEY_PAPER, PaperProfile.NARROW.name);
    }

    public static void setPaperName(String value) {
        if (prefs != null && value != null && value.length() > 0) {
            prefs.edit().putString(KEY_PAPER, value).apply();
        }
    }

    /**
     * The printer's own settings, named and defaulted as Loyverse names and
     * defaults them, so a cafe that has set up a Loyverse printer already
     * knows what these are.
     *
     * The commands are the ones a printer's manual quotes and Loyverse takes
     * as hex, e.g. "1D,56,42,00". Ours default to what Loyverse itself sends,
     * which is why a printer configured for Loyverse needs nothing changed.
     */
    public static final String DEFAULT_CUTTER = "1D,56,42,00";
    public static final String DEFAULT_DRAWER = "1B,70,00,19,FA";

    /** Printable width in mm, as Loyverse's Print width dropdown offers. */
    public static final int[] PRINT_WIDTH_CHOICES = {48, 72};
    public static final int[] DPI_CHOICES = {203, 300};

    /** What the operator called this printer. Cosmetic, as in Loyverse. */
    public static String printerLabel() {
        return prefs == null ? "RECEIPT PRINTER"
                : prefs.getString(KEY_PRINTER_LABEL, "RECEIPT PRINTER");
    }

    public static void setPrinterLabel(String value) {
        if (prefs != null) {
            prefs.edit().putString(KEY_PRINTER_LABEL, value).apply();
        }
    }

    /**
     * Printable width in millimetres - 48 on 58mm paper, 72 on 80mm.
     *
     * Defaults from the detected paper rather than a fixed number, so a cafe
     * that never opens this screen still prints the right width.
     */
    public static int printWidthMm() {
        int fallback = PaperProfile.configured() == PaperProfile.WIDE ? 72 : 48;
        return prefs == null ? fallback : prefs.getInt(KEY_PRINT_WIDTH_MM, fallback);
    }

    public static void setPrintWidthMm(int value) {
        if (prefs != null) {
            prefs.edit().putInt(KEY_PRINT_WIDTH_MM, value).apply();
        }
    }

    public static int dpi() {
        return prefs == null ? 203 : prefs.getInt(KEY_DPI, 203);
    }

    public static void setDpi(int value) {
        if (prefs != null) {
            prefs.edit().putInt(KEY_DPI, value).apply();
        }
    }

    public static String initCommands() {
        return prefs == null ? "" : prefs.getString(KEY_INIT_CMD, "");
    }

    public static String cutterCommands() {
        return prefs == null ? DEFAULT_CUTTER : prefs.getString(KEY_CUTTER_CMD, DEFAULT_CUTTER);
    }

    public static String drawerCommands() {
        return prefs == null ? DEFAULT_DRAWER : prefs.getString(KEY_DRAWER_CMD, DEFAULT_DRAWER);
    }

    public static void setCommands(String init, String cutter, String drawer) {
        if (prefs != null) {
            prefs.edit().putString(KEY_INIT_CMD, init == null ? "" : init)
                    .putString(KEY_CUTTER_CMD, cutter == null ? "" : cutter)
                    .putString(KEY_DRAWER_CMD, drawer == null ? "" : drawer).apply();
        }
    }

    /** Loyverse's own toggle: a printer can be configured but not used. */
    public static boolean printReceipts() {
        return prefs == null || prefs.getBoolean(KEY_PRINT_RECEIPTS, true);
    }

    public static void setPrintReceipts(boolean value) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_PRINT_RECEIPTS, value).apply();
        }
    }

    /** The Bluetooth printer this gateway prints to; empty until chosen. */
    public static String printerName() {
        return prefs == null ? "" : prefs.getString(KEY_PRINTER_NAME, "");
    }

    public static String printerAddress() {
        return prefs == null ? "" : prefs.getString(KEY_PRINTER_ADDRESS, "");
    }

    public static void setPrinter(String name, String address) {
        if (prefs != null) {
            prefs.edit().putString(KEY_PRINTER_NAME, name == null ? "" : name)
                    .putString(KEY_PRINTER_ADDRESS, address == null ? "" : address).apply();
        }
    }

    public static void rememberNetwork(String value) {
        if (prefs == null || value == null || value.trim().length() == 0) {
            return;
        }
        Set<String> stored = new LinkedHashSet<String>(
                prefs.getStringSet(KEY_NETWORKS, new LinkedHashSet<String>()));
        stored.add(value.trim());
        prefs.edit().putStringSet(KEY_NETWORKS, stored).apply();
    }
}
