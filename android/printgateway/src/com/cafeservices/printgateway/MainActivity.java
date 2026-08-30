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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Shows what the printer would have printed.
 *
 * The client's printer is Bluetooth and is not here, so until it is, the point
 * of this screen is to make the intercepted receipt visible: if what appears
 * here is right, what reaches the paper will be right.
 */
public class MainActivity extends Activity {

    /** 80mm paper is 48 characters, which is what Loyverse formats to. */
    private static final int PAPER_WIDTH = ReceiptParser.COLUMNS;

    /** Shown on the voucher slip. Will move into settings. */
    private static final String SSID = "The Cafe WiFi";
    private static final int ACCESS_HOURS = 2;

    private TextView statusLine;
    private TextView addressLine;
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
        setContentView(R.layout.activity_main);

        statusLine = (TextView) findViewById(R.id.statusLine);
        addressLine = (TextView) findViewById(R.id.addressLine);
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
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(PrintServerService.ACTION_JOB_RECEIVED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(jobReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(jobReceiver, filter);
        }
        render();
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

        // Loyverse renders receipts as a bitmap, so for those the picture is
        // the only representation there is. Show the newest one full size.
        Bitmap newest = jobs.isEmpty() ? null : jobs.get(0).bitmap();
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
                sb.append(job.preview(PAPER_WIDTH));
                sb.append(voucherTail(job));
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
     * What the gateway understood from the sale. This is the part that will
     * drive voucher issuing, so it is worth seeing plainly next to the receipt
     * it came from.
     */
    private static String summarise(PrintJob job) {
        if (job.hasImage()) {
            return "(image receipt - set Loyverse print mode to Text)\n\n";
        }
        ReceiptParser.Receipt receipt = ReceiptParser.parse(job.decoded);
        if (receipt.items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("UNDERSTOOD AS:\n");
        if (receipt.receiptNumber.length() > 0) {
            sb.append("  receipt ").append(receipt.receiptNumber).append('\n');
        }
        for (ReceiptParser.Item item : receipt.items) {
            sb.append("  ").append(item.wholeQuantity()).append(" x ")
                    .append(item.name).append('\n');
        }
        ReceiptParser.Item wifi = receipt.findItem("wi-fi");
        if (wifi == null) {
            wifi = receipt.findItem("wifi");
        }
        if (wifi != null) {
            sb.append("  -> would issue ").append(wifi.wholeQuantity())
                    .append(" voucher(s)\n");
        }
        sb.append('\n');
        return sb.toString();
    }
    /**
     * The voucher part that would follow the receipt on the same roll.
     *
     * The codes are placeholders until voucher issuing is connected; the point
     * for now is to settle the layout, so what is shown is the real format with
     * stand-in numbers rather than a sketch of it.
     */
    private static String voucherTail(PrintJob job) {
        if (job.hasImage()) {
            return "";
        }
        ReceiptParser.Receipt receipt = ReceiptParser.parse(job.decoded);
        ReceiptParser.Item wifi = receipt.findItem("wi-fi");
        if (wifi == null) {
            wifi = receipt.findItem("wifi");
        }
        if (wifi == null) {
            return "";
        }

        int quantity = Math.max(1, wifi.wholeQuantity());
        List<String> codes = new ArrayList<String>();
        for (int i = 0; i < quantity; i++) {
            codes.add(placeholderCode(receipt.receiptNumber, i));
        }

        Date expires = new Date(System.currentTimeMillis() + 24L * 60L * 60L * 1000L);
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append(VoucherSlip.build(codes, SSID, ACCESS_HOURS, expires, PAPER_WIDTH));
        sb.append("  (codes above are placeholders until Omada is wired)\n");
        return sb.toString();
    }

    /** Stable per receipt, so the same sale always previews the same digits. */
    private static String placeholderCode(String receiptNumber, int index) {
        int seed = Math.abs((receiptNumber + ":" + index).hashCode()) % 1000000;
        return String.format(Locale.US, "%06d", seed);
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
