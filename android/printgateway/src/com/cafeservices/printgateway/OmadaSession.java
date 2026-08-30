package com.cafeservices.printgateway;

import android.util.Log;
import android.webkit.CookieManager;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * A logged-in Omada Cloud session, held by this app rather than by a browser.
 *
 * Omada Cloud Essentials has no Open API - that is a paid tier - but the portal
 * is itself a single-page app driven by ordinary REST calls, and we are a
 * legitimate operator of it. On the PC that meant driving a signed-in Chrome
 * profile; here the sign-in happens in a WebView inside this app, and the
 * cookies it leaves behind are what these calls carry.
 *
 * There is no way to make the Omada Android app issue a voucher on our behalf -
 * Android gives one app no handle on another's session - so this app holds its
 * own login. It is the same session the portal uses, so anything the portal can
 * do here, we can.
 *
 * Full notes on the endpoints, and how they were derived, in
 * docs/OMADA-CLOUD-API.md.
 */
public final class OmadaSession {

    /** Where a person signs in. The WebView opens this. */
    public static final String PORTAL_URL = "https://aps1-omada-cloud.tplinkcloud.com/";

    /** Where the JSON lives. A different host from the portal, same cookies. */
    public static final String API_BASE =
            "https://aps1-api-omada-essential-controller.tplinkcloud.com";

    /** This account's controller. Paths are {API_BASE}/{OMADAC_ID}/api/v2/... */
    public static final String OMADAC_ID = "<omada-account-id>";

    /** The Cafe. A second client would be a second site id. */
    public static final String SITE_ID = "<omada-site-id>";

    /** Omada says this when the controller no longer knows us. */
    public static final int ERROR_LOGGED_OUT = -1200;

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    private OmadaSession() {
    }

    /** Raised for anything Omada refuses, carrying its own error code. */
    public static final class OmadaException extends Exception {
        public final int errorCode;

        OmadaException(String message, int errorCode) {
            super(message);
            this.errorCode = errorCode;
        }

        public boolean loggedOut() {
            return errorCode == ERROR_LOGGED_OUT;
        }
    }

    /**
     * Whether the controller still considers us signed in.
     *
     * `errorCode: 0` is not the answer: the portal returns a success envelope
     * saying `login: false` when nobody is signed in at all. The payload is
     * what counts. Costs a network round trip, so never call it on the main
     * thread.
     */
    public static boolean isConnected() {
        try {
            JSONObject result = get("/api/v2/hotspot/loginStatus?needToken=true");
            return result != null && result.optBoolean("login", false);
        } catch (Exception e) {
            Log.i(PrintServerService.TAG, "omada not connected: " + e.getMessage());
            return false;
        }
    }

    public static String siteBase() {
        return "/api/v2/hotspot/sites/" + SITE_ID;
    }

    public static JSONObject get(String path) throws Exception {
        return request("GET", path, null);
    }

    public static JSONObject post(String path, JSONObject body) throws Exception {
        return request("POST", path, body);
    }

    public static JSONObject delete(String path) throws Exception {
        return request("DELETE", path, null);
    }

    /**
     * One call to the controller, unwrapped.
     *
     * Every response is `{ errorCode, msg, result }`, so the envelope is peeled
     * here and only `result` is handed back - callers should never have to
     * think about it.
     */
    public static JSONObject request(String method, String path, JSONObject body)
            throws Exception {
        URL url = new URL(API_BASE + "/" + OMADAC_ID + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            // Without x-requested-with the server answers 404 rather than
            // refusing outright, which reads as a wrong path and is not.
            conn.setRequestProperty("x-requested-with", "XMLHttpRequest");
            conn.setRequestProperty("accept", "application/json, text/plain, */*");
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("csrf-token", csrfToken());
            String cookies = cookieHeader();
            if (cookies != null) {
                conn.setRequestProperty("cookie", cookies);
            }

            if (body != null) {
                conn.setDoOutput(true);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(body.toString().getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            }

            int status = conn.getResponseCode();
            InputStream stream = (status >= 400)
                    ? conn.getErrorStream() : conn.getInputStream();
            String text = read(stream);
            if (text.length() == 0) {
                throw new OmadaException(method + " " + shortPath(path)
                        + ": empty response (HTTP " + status + ")", status);
            }

            Object parsed = new JSONTokener(text).nextValue();
            if (!(parsed instanceof JSONObject)) {
                throw new OmadaException(method + " " + shortPath(path)
                        + ": unexpected response (HTTP " + status + ")", status);
            }
            JSONObject envelope = (JSONObject) parsed;
            int errorCode = envelope.optInt("errorCode", -1);
            if (errorCode != 0) {
                throw new OmadaException(method + " " + shortPath(path) + ": "
                        + envelope.optString("msg", "unknown error")
                        + " (errorCode " + errorCode + ")", errorCode);
            }
            return envelope.optJSONObject("result");
        } finally {
            conn.disconnect();
        }
    }

    /** Sent as its own header as well as in the cookie jar. */
    private static String csrfToken() {
        String cookies = CookieManager.getInstance().getCookie(API_BASE);
        String token = valueOf(cookies, "csrfToken");
        if (token != null) {
            return token;
        }
        // The portal sets it on its own host; the API host may not see it.
        token = valueOf(CookieManager.getInstance().getCookie(PORTAL_URL), "csrfToken");
        return token == null ? "" : token;
    }

    private static String cookieHeader() {
        String cookies = CookieManager.getInstance().getCookie(API_BASE);
        return (cookies == null || cookies.length() == 0) ? null : cookies;
    }

    private static String valueOf(String cookieHeader, String name) {
        if (cookieHeader == null) {
            return null;
        }
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = stream.read(chunk)) > 0) {
            out.write(chunk, 0, n);
        }
        stream.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    /** Query strings are noise in an error message. */
    private static String shortPath(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }
}
