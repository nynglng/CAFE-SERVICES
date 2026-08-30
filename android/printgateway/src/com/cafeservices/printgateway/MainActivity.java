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

    /** The client's 58mm paper is 32 characters, which Loyverse formats to. */
    private static final int PAPER_WIDTH = ReceiptParser.COLUMNS;

    private static final int MENU_SETTINGS = 1;

    private static final int OMADA_LOGIN = 1;

    private TextView statusLine;
    private TextView addressLine;
    private TextView omadaLine;
    private TextView jobsView;
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
        jobsView = (TextView) findViewById(R.id.jobsView);
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
                jobsView.postDelayed(new Runnable() {
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
                omadaLine.post(new Runnable() {
                    @Override
                    public void run() {
                        omadaLine.setText(connected
                                ? "Omada: connected - vouchers are real"
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
        menu.add(0, MENU_SETTINGS, 0, "Settings")
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
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

        if (jobs.isEmpty()) {
            jobsView.setText("No print jobs yet.\n\nSend a test print from Loyverse "
                    + "and the receipt will appear here.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        synchronized (jobs) {
            for (PrintJob job : jobs) {
                sb.append(divider());
                sb.append(job.time()).append("   ")
                        .append(job.decoded.byteCount).append(" bytes   -> ")
                        .append(job.role().label).append(" printer (")
                        .append(job.dialled).append(")\n");
                sb.append(divider());
                sb.append(summarise(job));
                sb.append(composed(job));
                if (!job.decoded.events.isEmpty()) {
                    sb.append('\n');
                    for (String event : job.decoded.events) {
                        sb.append("  [").append(event).append("]\n");
                    }
                }
                sb.append('\n');
            }
        }
        jobsView.setText(sb.toString());
    }

    /**
     * What the gateway understood, and what it would print.
     *
     * The receipt is graphic because it is forwarded untouched and never needs
     * reading; the voucher ticket is text because it is the one parsed to work
     * out how many codes to issue.
     */
    private static String summarise(PrintJob job) {
        if (job.hasImage()) {
            if (job.role() == PrinterRole.VOUCHER) {
                return "CANNOT READ THIS: the voucher printer is in graphic mode.\n"
                        + "Set its print mode to Text so the items can be read.\n\n";
            }
            return "Receipt image - forwarded to the printer as-is.\n\n";
        }

        ReceiptParser.Receipt receipt = ReceiptParser.parse(job.decoded);
        if (receipt.items.isEmpty()) {
            return "";
        }
        SalePlan plan = SalePlan.from(receipt);

        StringBuilder sb = new StringBuilder();
        sb.append("UNDERSTOOD AS:\n");
        if (receipt.receiptNumber.length() > 0) {
            sb.append("  receipt ").append(receipt.receiptNumber).append('\n');
        }
        for (ReceiptParser.Item item : receipt.items) {
            sb.append("  ").append(item.wholeQuantity()).append(" x ")
                    .append(item.name);
            if (SalePlan.isWifi(item.name)) {
                sb.append("   <- WiFi");
            }
            sb.append('\n');
        }
        sb.append("WILL PRINT: ").append(plan.describe()).append("\n\n");
        return sb.toString();
    }

    private static ReceiptParser.Item findWifi(ReceiptParser.Receipt receipt) {
        ReceiptParser.Item wifi = receipt.findItem("wi-fi");
        return wifi != null ? wifi : receipt.findItem("wifi");
    }
    /**
     * The full output for a job: what the paper would actually say.
     *
     * Driven entirely by what was sold, not by which printer was dialled, so a
     * single gateway printer works as well as a split pair.
     */
    private static String composed(PrintJob job) {
        if (job.hasImage()) {
            return job.preview(PAPER_WIDTH);
        }
        ReceiptParser.Receipt receipt = ReceiptParser.parse(job.decoded);
        if (receipt.items.isEmpty()) {
            return job.preview(PAPER_WIDTH);
        }

        SalePlan plan = SalePlan.from(receipt);
        StringBuilder sb = new StringBuilder();

        if (plan.printReceipt) {
            for (String row : plan.receiptRows) {
                sb.append(row).append('\n');
            }
        }
        if (plan.printVoucher) {
            // The slip itself is drawn by VoucherRenderer and shown above as
            // part of the picture; only the count is worth restating here.
            sb.append("\n  + ").append(plan.voucherCount)
                    .append(" voucher(s) drawn below the receipt\n")
                    .append("  (codes are placeholders until Omada is wired)\n");
        }

        return sb.toString();
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
    private static String divider() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PAPER_WIDTH; i++) {
            sb.append('-');
        }
        return sb.append('\n').toString();
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
