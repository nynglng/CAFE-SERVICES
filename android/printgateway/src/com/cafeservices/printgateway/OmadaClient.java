package com.cafeservices.printgateway;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Issues and clears up vouchers on the real Omada site.
 *
 * This is the Android half of what the PC tool does, and deliberately the same
 * shapes: a sale creates one GROUP holding N codes, the codes are read back,
 * and spent groups of ours are deleted afterwards so the site never drifts back
 * towards the 5000-voucher cap that started this project.
 *
 * The pile-up was never caused by the manual work - it was that every group was
 * created Permanent, so ~98% of codes were never redeemed and could never
 * expire. Every group made here is Fixed Dates, which is the actual fix.
 */
public final class OmadaClient {

    /** Written into every group we create; the guard that protects hand-made ones. */
    public static final String SERVICE_TAG = "Issued by cafe-voucher-service";

    /** Name prefix for groups this service creates. */
    public static final String POS_PREFIX = "POS-";

    /** Omada's validity modes. Permanent is what made vouchers pile up. */
    public static final int VALIDITY_PERMANENT = 0;
    public static final int VALIDITY_FIXED_DATES = 1;

    /** Digits only, six of them: what a guest can actually type off paper. */
    private static final int CODE_LENGTH = 6;
    private static final int DEVICES_PER_CODE = 1;

    private OmadaClient() {
    }

    /** One code, as Omada returns it. */
    public static final class Voucher {
        public final String id;
        public final String code;
        public final int status;
        /** Epoch ms, or null when the code never expires. */
        public final Long endTime;

        Voucher(String id, String code, int status, Long endTime) {
            this.id = id;
            this.code = code;
            this.status = status;
            this.endTime = endTime;
        }
    }

    /** A batch of codes, which is also the unit Omada deletes. */
    public static final class Group {
        public final String id;
        public final String name;
        public final String description;
        public final int totalCount;
        public final int validityType;

        Group(String id, String name, String description, int totalCount, int validityType) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.totalCount = totalCount;
            this.validityType = validityType;
        }
    }

    /**
     * Whether automation may delete a group.
     *
     * All three markers must hold. The description is the real guard - groups
     * made by hand in the portal have none at all - while the name and validity
     * are belt and braces, so a group would have to look deliberately like ours
     * in three separate ways before it were ever at risk. The cafe's
     * hand-printed batches are the fallback when this app is down. Do not
     * loosen this.
     */
    public static boolean ownedByService(Group g) {
        return SERVICE_TAG.equals(g.description)
                && g.name != null
                && g.name.startsWith(POS_PREFIX)
                && g.validityType == VALIDITY_FIXED_DATES;
    }

    /**
     * Create a group for a sale and hand back its codes.
     *
     * @param quantity     how many codes the sale asked for
     * @param accessHours  access time, counted from a guest's first login
     * @param expiryHours  how long an unredeemed code stays redeemable
     */
    public static List<String> issue(int quantity, int accessHours, int expiryHours)
            throws Exception {
        String name = POS_PREFIX + stamp();
        String groupId = createGroup(name, quantity, accessHours * 60, expiryHours);

        List<String> codes = new ArrayList<String>();
        for (Voucher v : listCodes(groupId)) {
            codes.add(v.code);
        }
        if (codes.isEmpty()) {
            throw new IllegalStateException("Omada created " + name + " but returned no codes");
        }
        return codes;
    }

    /** The group id. Creating returns only that; codes come from a second call. */
    public static String createGroup(String name, int amount, int durationMinutes,
                                     int expireAfterHours) throws Exception {
        JSONObject body = new JSONObject();
        body.put("codeLength", CODE_LENGTH);
        body.put("codeForm", new JSONArray().put(0)); // digits only
        body.put("amount", amount);
        body.put("name", name);
        body.put("type", 2); // unlimited usage count
        body.put("logout", true);
        body.put("downLimitUnit", 0);
        body.put("upLimitUnit", 0);
        body.put("trafficLimitEnable", false);
        body.put("trafficLimit", JSONObject.NULL);
        body.put("trafficLimitUnit", 0);
        // Stays false even for Fixed Dates. Setting it true gets the request
        // rejected with -1001, and the portal itself never sets it.
        body.put("voucherValidityEnable", false);
        body.put("upTimeLimitEnable", false);
        body.put("durationType", 1); // counted from first login
        body.put("duration", durationMinutes);
        body.put("description", SERVICE_TAG);
        body.put("maxUsers", DEVICES_PER_CODE);
        body.put("trafficLimitFrequency", 1);
        body.put("applyToAllPortals", true);
        body.put("validityType", VALIDITY_FIXED_DATES);
        body.put("startTime", "00:00");
        body.put("endTime", "23:59");
        body.put("scheduleTime", 0);

        JSONObject days = new JSONObject();
        for (int day = 1; day <= 7; day++) {
            days.put(String.valueOf(day), true);
        }
        body.put("weeklyEnableDays", days);

        JSONObject pattern = new JSONObject();
        pattern.put("patternType", 2);
        pattern.put("position", 0);
        pattern.put("ssidNetworkEnable", false);
        pattern.put("durationEnable", false);
        pattern.put("limitEnable", false);
        body.put("pattern", pattern);

        // Absolute epoch milliseconds. The portal's pickers are day-granular,
        // but the field is finer and the controller honours it.
        body.put("effectiveTime", startOfToday());
        body.put("expirationTime",
                System.currentTimeMillis() + expireAfterHours * 3600000L);

        JSONObject result = OmadaSession.post(OmadaSession.siteBase() + "/voucherGroups", body);
        String id = result == null ? null : result.optString("id", null);
        if (id == null || id.length() == 0) {
            throw new IllegalStateException("Omada did not return a voucher group id");
        }
        return id;
    }

    public static List<Voucher> listCodes(String groupId) throws Exception {
        List<Voucher> out = new ArrayList<Voucher>();
        JSONObject result = OmadaSession.get(OmadaSession.siteBase()
                + "/voucherGroups/" + groupId + "?currentPage=1&currentPageSize=500");
        JSONArray rows = result == null ? null : result.optJSONArray("data");
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            out.add(new Voucher(row.optString("id", ""), row.optString("code", ""),
                    row.optInt("status", 0), endTimeOf(row)));
        }
        return out;
    }

    public static List<Group> listGroups() throws Exception {
        List<Group> out = new ArrayList<Group>();
        JSONObject result = OmadaSession.get(OmadaSession.siteBase()
                + "/voucherGroups?currentPage=1&currentPageSize=100&searchField=name%2Ccode");
        JSONArray rows = result == null ? null : result.optJSONArray("data");
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            out.add(new Group(row.optString("id", ""), row.optString("name", ""),
                    row.has("description") ? row.optString("description", null) : null,
                    row.optInt("totalCount", 0), row.optInt("validityType", VALIDITY_PERMANENT)));
        }
        return out;
    }

    public static void deleteGroup(String groupId) throws Exception {
        OmadaSession.delete(OmadaSession.siteBase() + "/voucherGroups/" + groupId);
    }

    /**
     * Delete our own groups in which no code can still be redeemed.
     *
     * Omada flips a code to expired but never deletes it, and expired codes
     * still count towards the cap, so this is what keeps the count down.
     * Returns how many groups went.
     */
    public static int cleanup() throws Exception {
        long now = System.currentTimeMillis();
        int deleted = 0;
        for (Group group : listGroups()) {
            if (!ownedByService(group)) {
                continue;
            }
            boolean stillUsable = false;
            for (Voucher v : listCodes(group.id)) {
                if (v.status == 0 && (v.endTime == null || v.endTime > now)) {
                    stillUsable = true;
                    break;
                }
            }
            if (!stillUsable) {
                deleteGroup(group.id);
                deleted++;
                Log.i(PrintServerService.TAG, "cleanup deleted " + group.name);
            }
        }
        return deleted;
    }

    /** Stored vouchers across the whole site - the number that hits the cap. */
    public static int storedVouchers() throws Exception {
        int total = 0;
        for (Group g : listGroups()) {
            total += g.totalCount;
        }
        return total;
    }

    /**
     * Omada returns Long.MAX_VALUE for "never expires", which no timestamp
     * comparison should treat as a real date.
     */
    private static Long endTimeOf(JSONObject row) {
        String raw = row.optString("endTime", "");
        if (raw.startsWith("922337203685477") || raw.length() == 0) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long startOfToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Local time, so group names line up with what the Omada UI shows. */
    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }
}
