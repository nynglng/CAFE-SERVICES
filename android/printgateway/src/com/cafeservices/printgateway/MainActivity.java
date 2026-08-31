package com.cafeservices.printgateway;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
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
        menu.add(0, MENU_SETTINGS, 1, "Settings")
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == MENU_PRINTER) {
            if (askForBluetooth()) {
                PrinterPicker.show(this, new Runnable() {
                    @Override
                    public void run() {
                        render();
                    }
                });
            }
            return true;
        }
        if (item.getItemId() != MENU_SETTINGS) {
            return super.onOptionsItemSelected(item);
        }
        SettingsDialog.show(this, new Runnable() {
            @Override
            public void run() {
                // Jobs already on screen were drawn with the old wording, so
                // they are redrawn: the point of changing a setting is seeing
                // what it does to the slip.
                for (PrintJob job : PrintServerService.jobs()) {
                    job.forgetReplica();
                }
                render();
                android.widget.Toast.makeText(MainActivity.this,
                        "Saved. " + Settings.ssid() + ", valid "
                                + Settings.accessHours() + "h, expires in "
                                + Settings.expiryHours() + "h",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        return true;
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

    private void render() {
        boolean running = PrintServerService.isRunning();
        statusLine.setText(running
                ? "Listening on port " + PrintServerService.PORT
                : "Stopped");
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
