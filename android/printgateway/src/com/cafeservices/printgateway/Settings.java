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
