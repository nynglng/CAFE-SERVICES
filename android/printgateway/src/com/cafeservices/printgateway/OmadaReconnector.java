package com.cafeservices.printgateway;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

/**
 * Bringing the Omada session back without anyone touching the phone.
 *
 * The gateway sits on a counter and runs all day; the staff are not going to be
 * taught to reconnect it, and should not have to be. Two sessions are in play
 * and they expire differently: the ACCOUNT login lasts (it survives the app
 * being force-stopped), while the CONTROLLER session is short-lived and is
 * re-established by the portal page a few seconds after it loads. That second
 * one lapsing is what makes calls fail with -1200.
 *
 * So reviving is simply loading the portal again, out of sight, and waiting for
 * the controller to accept us - the same thing tapping OMADA does, minus the
 * person. It only works while the account login itself is still good; when that
 * has gone, nothing here helps and someone has to sign in.
 */
public final class OmadaReconnector {

    /** How long to give the portal to re-establish the session. */
    private static final long REVIVE_TIMEOUT_MS = 45000;
    private static final long POLL_MS = 3000;

    /** Kept between attempts: a WebView carries the page state that revives us. */
    private static WebView web;

    private OmadaReconnector() {
    }

    /**
     * Load the portal and wait for the controller to accept us again.
     *
     * Call from a background thread - it blocks - and never more than one at a
     * time, which the caller gets for free by only calling it from the socket
     * thread and the keepalive.
     */
    public static synchronized boolean revive(final Context context) {
        Log.i(PrintServerService.TAG, "reviving omada session");
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (web == null) {
                        web = new WebView(context.getApplicationContext());
                        web.getSettings().setJavaScriptEnabled(true);
                        web.getSettings().setDomStorageEnabled(true);
                    }
                    web.loadUrl(OmadaSession.PORTAL_URL);
                } catch (Exception e) {
                    // A WebView can fail to exist at all (updating, disabled).
                    // Nothing to do but let the wait below time out.
                    Log.e(PrintServerService.TAG, "no webview to revive with: " + e);
                }
            }
        });

        long deadline = System.currentTimeMillis() + REVIVE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            android.webkit.CookieManager.getInstance().flush();
            if (OmadaSession.isConnected()) {
                Log.i(PrintServerService.TAG, "omada session revived");
                return true;
            }
        }
        Log.e(PrintServerService.TAG, "could not revive omada session");
        return false;
    }
}
