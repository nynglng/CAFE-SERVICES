package com.cafeservices.printgateway;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.graphics.Bitmap;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Shows what the printer would have printed.
 *
 * The client's printer is Bluetooth and is not here, so until it is, the point
 * of this screen is to make the intercepted receipt visible: if what appears
 * here is right, what reaches the paper will be right.
 */
public class MainActivity extends Activity {

    private static final int MENU_SETTINGS = 1;
    private static final int MENU_PRINTER = 2;
    private static final int PERMISSIONS = 7;

    private static final int OMADA_LOGIN = 1;

    private TextView statusLine;
    private TextView addressLine;
    private TextView omadaLine;
    private TextView printerLine;
    private TextView emptyView;
    private ImageView receiptImage;
    private Button toggleButton;

    private final BroadcastReceiver jobReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String error = intent.getStringExtra("error");
            if (error != null) {
                statusLine.setText("Server error: " + error);
            }
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.init(this);
        setContentView(R.layout.activity_main);

        statusLine = (TextView) findViewById(R.id.statusLine);
        addressLine = (TextView) findViewById(R.id.addressLine);
        omadaLine = (TextView) findViewById(R.id.omadaLine);
        printerLine = (TextView) findViewById(R.id.printerLine);
        emptyView = (TextView) findViewById(R.id.emptyView);
        receiptImage = (ImageView) findViewById(R.id.receiptImage);
        toggleButton = (Button) findViewById(R.id.toggleButton);

        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (PrintServerService.isRunning()) {
                    stopService(new Intent(MainActivity.this, PrintServerService.class));
                } else {
                    startGateway();
                }
                emptyView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        render();
                    }
                }, 400);
            }
        });

        findViewById(R.id.omadaButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(
                        new Intent(MainActivity.this, OmadaLoginActivity.class), OMADA_LOGIN);
            }
        });

        findViewById(R.id.clearButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PrintServerService.clearJobs();
                render();
            }
        });

        startGateway();
        askToStayVisible();
        askToBeLeftAlone();
        checkNotificationsAllowed();
    }

    /**
     * Ask for the one permission that actually keeps the gateway alive.
     *
     * Everything else we can do is a request the phone is free to ignore, and
     * on this hardware it does: the service was frozen while awake, with
     * notifications on and on the battery whitelist. An app holding a window
     * on screen is the one thing these battery savers leave alone, because it
     * is indistinguishable from an app being used.
     *
     * The window is a single transparent pixel. Nobody at the counter will
     * ever see it, and it takes no touches - but Android will not let an app
     * put anything on top of other apps without being asked first, so here we
     * ask, and say why in terms of the thing that actually goes wrong.
     */
    private void askToStayVisible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || android.provider.Settings.canDrawOverlays(this)) {
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Allow display over other apps")
                .setMessage("Without this the phone freezes the gateway a few "
                        + "minutes after you stop looking at it, and printing "
                        + "from Loyverse does nothing until someone opens this "
                        + "app.\n\nIt puts a single transparent dot on screen "
                        + "that nobody can see or tap. That is what stops the "
                        + "phone treating this as a background app.")
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings
                                            .ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(
                                    android.provider.Settings
                                            .ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        }
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    /**
     * Say plainly when the gateway is going to be frozen.
     *
     * Switching the app's notifications off is a reasonable thing to want -
     * nobody at a counter should be wondering what a developer's tool is doing
     * on the till - but it is also what stops the phone treating this as a
     * foreground service, and a frozen gateway accepts no prints at all. It
     * fails silently and looks exactly like a broken printer, which is the
     * worst way for it to fail.
     *
     * The notification is now as quiet as Android permits: no sound, no
     * pop-up, no badge, and it sits at the bottom of the shade. Not silent
     * altogether - the system refuses to let a foreground service's channel
     * sit at minimum importance and clamps it up, so a small status bar icon
     * remains, and there is no way around that.
     */
    private void checkNotificationsAllowed() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.areNotificationsEnabled()) {
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Turn notifications back on")
                .setMessage("With notifications off, Android freezes this app "
                        + "after a few minutes idle. Printing then does nothing "
                        + "until someone opens it.\n\nIt shows no sound, no "
                        + "pop-up and no status bar icon - only a silent line at "
                        + "the bottom of the notification shade, reading "
                        + "\"Receipt printer\".")
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openNotificationSettings();
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        }
    }

    /**
     * Ask Android to stop putting the gateway to sleep.
     *
     * This is the single most effective thing for the counter's complaint that
     * a print does nothing until the gateway is opened. Battery optimisation
     * freezes an app that has been in the background a while, and the gateway
     * spends every shift in the background behind the POS. Exempted, it is
     * left running; not exempted, no amount of care in our own code reliably
     * beats it.
     *
     * Asked once - Android shows the dialog only while the app is not already
     * exempt, so this goes quiet as soon as the owner says yes.
     *
     * Note this is not the whole story on a phone-maker's Android. Several
     * keep a second, private list of their own that no app can ask about;
     * those have to be set by hand on the tablet.
     */
    private void askToBeLeftAlone() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            android.os.PowerManager power =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (power == null || power.isIgnoringBatteryOptimizations(getPackageName())) {
                return;
            }
            startActivity(new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            // Some builds hide the screen entirely. The owner can still reach
            // it through Settings, and the gateway works either way.
            android.util.Log.i(PrintServerService.TAG,
                    "could not ask about battery optimisation: " + e);
        }
    }

    private void startGateway() {
        Intent intent = new Intent(this, PrintServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OMADA_LOGIN) {
            checkOmada();
        }
    }

    /**
     * Bluetooth needs asking for, and what to ask for changed in Android 12.
     *
     * @return true when the picker can be opened now; false while the
     *         permission dialog is up, in which case the operator taps again
     */
    private boolean askForBluetooth() {
        List<String> wanted = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            wanted.add("android.permission.BLUETOOTH_CONNECT");
            wanted.add("android.permission.BLUETOOTH_SCAN");
        } else {
            // Pre-12, finding a printer counts as finding a location.
            wanted.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }

        List<String> missing = new ArrayList<String>();
        for (String permission : wanted) {
            if (checkSelfPermission(permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            return true;
        }
        requestPermissions(missing.toArray(new String[missing.size()]), PERMISSIONS);
        return false;
    }

    /**
     * Ask Omada whether we are signed in, off the main thread.
     *
     * Shown on the front screen rather than buried in settings: a gateway that
     * cannot reach Omada still prints receipts, but every voucher on them is a
     * stand-in, and that should be visible before a queue forms.
     */
    private void checkOmada() {
        omadaLine.setText("Omada: checking...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean connected = OmadaSession.isConnected();
                if (connected) {
                    // Who we are and which cafe, straight from the session.
                    OmadaDiscovery.refresh();
                }
                omadaLine.post(new Runnable() {
                    @Override
                    public void run() {
                        String site = Settings.siteName();
                        omadaLine.setText(connected
                                ? "Omada: " + (site.length() > 0 ? site : "connected")
                                : "Omada: not connected - tap Omada to sign in");
                        for (PrintJob job : PrintServerService.jobs()) {
                            job.forgetReplica();
                        }
                        render();
                    }
                });
            }
        }).start();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, MENU_PRINTER, 0, "Printer")
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == MENU_PRINTER || item.getItemId() == MENU_SETTINGS) {
            // One screen now: the printer set up as Loyverse sets one up,
            // with vouchering under it.
            startActivity(new Intent(this, PrinterSettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(PrintServerService.ACTION_JOB_RECEIVED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(jobReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(jobReceiver, filter);
        }
        render();
        checkOmada();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(jobReceiver);
        } catch (IllegalArgumentException ignored) {
            // not registered; nothing to undo
        }
    }

    /** "a", or "a and b" - the way a person would say it. */
    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0) + " is";
        }
        return parts.get(0) + " and " + parts.get(1) + " are";
    }

    private void render() {
        boolean running = PrintServerService.isRunning();
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        List<String> missing = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(this)) {
            missing.add("display over other apps");
        }
        if (nm != null && !nm.areNotificationsEnabled()) {
            missing.add("notifications");
        }
        String listening = "Listening on port " + PrintServerService.PORT;
        statusLine.setText(!running ? "Stopped"
                : missing.isEmpty() ? listening
                        : listening + " - but " + join(missing) + " off, so the "
                                + "phone will freeze this within minutes");
        toggleButton.setText(running ? "Stop" : "Start");
        addressLine.setText(addresses());
        String printer = Settings.printerName();
        printerLine.setText(printer.length() > 0
                ? "Printer: " + printer
                : "Printer: none chosen - tap Printer above");

        List<PrintJob> jobs = PrintServerService.jobs();

        // Show the newest job as a picture, full size. A text-mode sale is
        // redrawn as the graphic receipt the printer will actually be handed,
        // which is the thing worth looking at; only when there is no replica
        // to draw - an image job, or a voucher-only sale - do we fall back to
        // the raster that arrived.
        Bitmap newest = null;
        if (!jobs.isEmpty()) {
            PrintJob top = jobs.get(0);
            newest = top.replica();
            if (newest == null) {
                newest = top.bitmap();
            }
        }
        if (newest != null) {
            receiptImage.setImageBitmap(newest);
            receiptImage.setVisibility(View.VISIBLE);
        } else {
            receiptImage.setImageDrawable(null);
            receiptImage.setVisibility(View.GONE);
        }

        emptyView.setVisibility(newest == null ? View.VISIBLE : View.GONE);
    }

    /** The store name row, which sits centred above everything else. */
    static String storeHeader(ReceiptParser.Receipt receipt) {
        for (String row : receipt.rows) {
            String t = row.trim();
            if (t.length() == 0) {
                continue;
            }
            if (t.startsWith("Employee") || t.startsWith("POS:")) {
                break;
            }
            return t;
        }
        return "";
    }
    /**
     * Every address this device can be reached on, so the right one can be
     * typed into Loyverse. Loopback is included because the POS is on this
     * same device and may be pointed straight at 127.0.0.1.
     */
    private String addresses() {
        StringBuilder sb = new StringBuilder("127.0.0.1  (this device)");
        try {
            List<NetworkInterface> nics =
                    Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nic : nics) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr.isLoopbackAddress() || addr.getHostAddress().contains(":")) {
                        continue;
                    }
                    sb.append('\n').append(addr.getHostAddress())
                            .append("  (").append(nic.getName()).append(')');
                }
            }
        } catch (Exception e) {
            sb.append("\n(could not read interfaces)");
        }
        return sb.toString();
    }
}
