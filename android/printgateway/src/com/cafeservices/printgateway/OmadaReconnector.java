package com.cafeservices.printgateway;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
 * person. When the portal stops on its list of organizations instead of going
 * in, the card is pressed here too, as a person would. It only works while the
 * account login itself is still good; when that has gone, nothing here helps
 * and someone has to sign in.
 */
public final class OmadaReconnector {

    /** How long to give the portal to re-establish the session. */
    private static final long REVIVE_TIMEOUT_MS = 45000;
    private static final long POLL_MS = 3000;

    /**
     * Counted from asking for the page, not from it arriving, so this allows
     * for the load as well as the second or two a portal that is going in by
     * itself takes. Past it, the portal has stopped on its list.
     */
    private static final long PRESS_AFTER_MS = 9000;
    private static final long PRESS_EVERY_MS = 12000;

    /** Big enough to lay the organization list out; nobody ever sees it. */
    private static final int PAGE_WIDTH = 1280;
    private static final int PAGE_HEIGHT = 800;

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
                        OmadaPortal.configure(web);
                        // Without a client of its own, every redirect the
                        // portal makes is handed to Android to open in some
                        // other app, which from a service it cannot do - so
                        // this page never got past its first address.
                        web.setWebViewClient(new WebViewClient());
                        // Never attached to a window, so it is 0x0 unless told
                        // otherwise, and a page that size may draw no cards.
                        web.measure(
                                View.MeasureSpec.makeMeasureSpec(PAGE_WIDTH,
                                        View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(PAGE_HEIGHT,
                                        View.MeasureSpec.EXACTLY));
                        web.layout(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
                    }
                    web.loadUrl(OmadaSession.PORTAL_URL);
                } catch (Exception e) {
                    // A WebView can fail to exist at all (updating, disabled).
                    // Nothing to do but let the wait below time out.
                    Log.e(PrintServerService.TAG, "no webview to revive with: " + e);
                }
            }
        });

        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + REVIVE_TIMEOUT_MS;
        long pressedAt = 0;
        int connectedPolls = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            android.webkit.CookieManager.getInstance().flush();
            if (OmadaSession.isConnected()) {
                // Twice running, as at sign-in: a single yes from the
                // organization list would spend the sale's one retry on a
                // session that is already gone.
                if (++connectedPolls >= OmadaPortal.CONFIRMING_POLLS) {
                    Log.i(PrintServerService.TAG, "omada session revived");
                    return true;
                }
                continue;
            }
            connectedPolls = 0;
            long now = System.currentTimeMillis();
            if (now - startedAt >= PRESS_AFTER_MS && now - pressedAt >= PRESS_EVERY_MS) {
                pressedAt = now;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (web != null) {
                            OmadaPortal.enterOrganization(web, null);
                        }
                    }
                });
            }
        }
        Log.e(PrintServerService.TAG, "could not revive omada session");
        return false;
    }
}
