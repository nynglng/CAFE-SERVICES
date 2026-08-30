package com.cafeservices.printgateway;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

/**
 * Signing the gateway in to Omada Cloud.
 *
 * The portal is a normal web app, so the sign-in is the portal's own - password,
 * two-factor, whatever TP-Link asks for that week - and none of it is
 * reimplemented or intercepted here. What we keep afterwards is what any
 * browser would keep: the cookies. Those are what OmadaSession's calls carry.
 *
 * The controller session is established by the page a few seconds AFTER it
 * loads, so a fixed wait is unreliable and the check below polls instead. On
 * the PC this same behaviour showed up as errorCode -1200 on every call for the
 * first few seconds of a fresh profile.
 */
public class OmadaLoginActivity extends Activity {

    private static final int MENU_DONE = 1;

    /** How often to ask the controller whether it knows us yet. */
    private static final long POLL_MS = 4000;

    private WebView web;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean finishing;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            check(false);
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        setTitle("Connect Omada");

        status = (TextView) findViewById(R.id.loginStatus);
        web = (WebView) findViewById(R.id.loginWeb);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cookies.setAcceptThirdPartyCookies(web, true);
        }

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                probe(view);
                // The page being up is not the same as the controller knowing
                // us, so this only restarts the polling.
                handler.removeCallbacks(poll);
                handler.postDelayed(poll, POLL_MS);
            }
        });
        web.loadUrl(OmadaSession.PORTAL_URL);
    }

    /**
     * Learn the account id from the page.
     *
     * Chicken and egg: every API path contains the account id, so it cannot be
     * fetched from the API before it is known. The portal names its own storage
     * keys after it - omadaCentralMicroUrl_<account> - so it is read from there
     * first, and everything else follows from the session.
     *
     * Only key NAMES are read. Their values are session material.
     */
    private void probe(WebView view) {
        view.evaluateJavascript(
                "(function(){var k=[];for(var i=0;i<localStorage.length;i++)"
                        + "k.push(localStorage.key(i));return k.join(',');})()",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        String account = OmadaDiscovery.accountIdFrom(value);
                        if (account != null) {
                            Settings.setAccountId(account);
                        }
                    }
                });
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, MENU_DONE, 0, "Done")
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == MENU_DONE) {
            check(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(poll);
    }

    /**
     * Ask the controller whether this session is real.
     *
     * @param manual true when the operator pressed Done, which is also the way
     *               out if the check keeps saying no.
     */
    private void check(final boolean manual) {
        if (finishing) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                CookieManager.getInstance().flush();
                final boolean connected = OmadaSession.isConnected();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (connected) {
                            finishing = true;
                            handler.removeCallbacks(poll);
                            setResult(RESULT_OK);
                            finish();
                            return;
                        }
                        if (manual) {
                            status.setText("Not signed in yet. Finish the sign-in above,"
                                    + " then press Done again.");
                        }
                    }
                });
            }
        }).start();
    }
}
