package com.cafeservices.printgateway;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Working out which account and which cafe this phone is signed in to.
 *
 * Nothing here should be hardcoded: a gateway on the counter at one cafe must
 * issue vouchers for THAT cafe, and a wrong site id fails silently - codes are
 * created happily, they just do not work on the network the guest is standing
 * next to.
 *
 * The account id is not returned by any endpoint we know; it is part of the
 * path every other call needs. The portal keeps it in its own localStorage key
 * names, which is where the WebView reads it from at sign-in.
 */
public final class OmadaDiscovery {

    /** A cafe, as Omada lists it. */
    public static final class Site {
        public final String id;
        public final String name;

        Site(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * The sites this account can see.
     *
     * Omada answers with the signed-in user's privilege list, which is the same
     * list the portal's own site picker shows.
     */
    public static List<Site> sites() throws Exception {
        return sitesFrom(OmadaSession.get("/api/v2/users/current"));
    }

    private static List<Site> sitesFrom(JSONObject result) {
        List<Site> out = new ArrayList<Site>();
        if (result == null) {
            return out;
        }
        JSONObject privilege = result.optJSONObject("privilege");
        JSONArray rows = privilege == null ? null : privilege.optJSONArray("sites");
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            // Omada calls the id "key" here and "id" elsewhere.
            String id = row.optString("key", row.optString("id", ""));
            String name = row.optString("name", id);
            if (id.length() > 0) {
                out.add(new Site(id, name));
            }
        }
        return out;
    }

    /**
     * Learn who we are signed in as, and remember it.
     *
     * `users/current` answers both questions at once: the account id that every
     * other path needs, and the sites this login can see. The site is only
     * chosen here when none has been picked yet - Omada's own "last visited"
     * is a better first guess than the first row, and an operator's choice must
     * never be overwritten behind their back.
     */
    public static void refresh() {
        try {
            JSONObject result = OmadaSession.get("/api/v2/users/current");
            if (result == null) {
                return;
            }
            Settings.setAccountId(result.optString("omadacId", null));

            List<Site> sites = sitesFrom(result);
            List<String> rows = new ArrayList<String>();
            for (Site site : sites) {
                rows.add(site.id + "|" + site.name);
            }
            if (!rows.isEmpty()) {
                Settings.setKnownSites(rows);
            }

            if (Settings.siteName().length() > 0) {
                return; // already chosen
            }
            JSONObject privilege = result.optJSONObject("privilege");
            String lastVisited = privilege == null ? "" : privilege.optString("lastVisited", "");
            for (Site site : sites) {
                if (site.id.equals(lastVisited)) {
                    Settings.setSite(site.id, site.name);
                    return;
                }
            }
            if (!sites.isEmpty()) {
                Settings.setSite(sites.get(0).id, sites.get(0).name);
            }
        } catch (Exception e) {
            Log.i(PrintServerService.TAG, "could not read account: " + e.getMessage());
        }
    }

    /** Every 32-character hex token among the portal's localStorage keys. */
    public static String accountIdFrom(String storageKeys) {
        if (storageKeys == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[0-9a-f]{32}").matcher(storageKeys);
        return m.find() ? m.group() : null;
    }

    private static String trim(String text) {
        return text.length() <= 600 ? text : text.substring(0, 600) + "...";
    }

    /** Names of an object's fields - enough to see a shape without dumping it. */
    static String keysOf(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(it.next());
        }
        return sb.toString();
    }
}
