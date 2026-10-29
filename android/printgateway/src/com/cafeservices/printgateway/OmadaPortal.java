package com.cafeservices.printgateway;

import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

/**
 * What the gateway does to the portal page itself, as opposed to its API.
 *
 * Loading the portal used to be enough: it went straight back into the
 * organization it was last in, and going in is what brings the controller
 * session up. It now sometimes stops on its list of organizations instead -
 * "Cloud Based Systems", one card per organization - and nothing stays signed
 * in to the controller until that card is pressed. Pressing it is all a person
 * does there, so it is all this does too.
 *
 * Worse than doing nothing, the list can answer loginStatus with a yes that is
 * gone a moment later: the sign-in screen closed on one, and by the time the
 * home screen asked, the gateway read "not connected". So both callers want
 * the yes twice running before they believe it.
 */
public final class OmadaPortal {

    /** A yes is only believed once it has been said this many polls running. */
    public static final int CONFIRMING_POLLS = 2;

    /**
     * Finds the element whose OWN text is the name - not its whole subtree,
     * or the card around the title, which also holds "Sites 1 Devices 1",
     * would never match while the page body always would - and presses it
     * the way a tap does. Prefers one that is actually laid out.
     */
    private static final String PRESS_CARD = "(function(name){"
            + "var hits=[],all=document.getElementsByTagName('*');"
            + "for(var i=0;i<all.length;i++){var own='';"
            + "for(var n=all[i].firstChild;n;n=n.nextSibling)"
            + "if(n.nodeType===3)own+=n.nodeValue;"
            + "if(own.trim()===name)hits.push(all[i]);}"
            + "if(!hits.length)return 'no card named '+name;"
            + "var el=hits[0];"
            + "for(var j=0;j<hits.length;j++)"
            + "if(hits[j].getClientRects().length){el=hits[j];break;}"
            + "['mousedown','mouseup','click'].forEach(function(t){"
            + "el.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));});"
            + "return 'pressed '+name+' ('+hits.length+' on page)';"
            + "})";

    private OmadaPortal() {
    }

    /** The same settings for the sign-in screen and the hidden reviver. */
    public static void configure(WebView web) {
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        // A press from script carries no user gesture, so if the card opens
        // the controller with window.open it would be blocked as a pop-up.
        // Allowed, and with multiple windows off, it loads in this same view.
        web.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
    }

    /**
     * Press this organization's card, if the portal is showing the list.
     *
     * Only call it once the controller has said no for a few seconds: when the
     * portal is going in by itself it does so almost at once, and the name may
     * also sit in the controller's own header, where pressing it is no help.
     * Main thread only, like anything that touches a WebView.
     *
     * @param pressed run if a card was found and pressed; may be null
     */
    public static void enterOrganization(WebView web, final Runnable pressed) {
        String name = Settings.orgName();
        if (name.length() == 0) {
            return;
        }
        web.evaluateJavascript(PRESS_CARD + "(" + JSONObject.quote(name) + ")",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String result) {
                        Log.i(PrintServerService.TAG, "omada org list: " + result);
                        if (pressed != null && result != null
                                && result.startsWith("\"pressed")) {
                            pressed.run();
                        }
                    }
                });
    }
}
