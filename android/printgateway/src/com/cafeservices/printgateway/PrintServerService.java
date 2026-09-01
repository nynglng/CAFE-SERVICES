package com.cafeservices.printgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pretends to be a network receipt printer.
 *
 * Loyverse cannot be told to send its receipt anywhere but a printer, so the
 * gateway becomes one: it listens on the port every ESC/POS network printer
 * listens on, and Loyverse is configured to print to this device's own address.
 * What arrives is exactly the byte stream that would have gone to the paper.
 *
 * It runs as a foreground service because the POS will be the app in front,
 * and a background socket would be killed the moment the screen slept.
 */
public class PrintServerService extends Service {

    public static final String TAG = "PrintGateway";
    /** The de facto standard port for raw ESC/POS over TCP. */
    public static final int PORT = 9100;

    public static final String ACTION_JOB_RECEIVED = "com.cafeservices.printgateway.JOB";
    /**
     * The channel the ongoing notification lives on.
     *
     * A new id rather than the old "print_gateway", because a channel's
     * importance belongs to the user once it exists: an app may create it, not
     * quieten it afterwards. The only way to move an existing install down to
     * IMPORTANCE_MIN is to ask on a channel that has never been seen before.
     */
    private static final String CHANNEL_ID = "receipt_printer";
    private static final String OLD_CHANNEL_ID = "print_gateway";

    /** Jobs are kept in memory only; nothing is written to storage yet. */
    private static final List<PrintJob> JOBS =
            Collections.synchronizedList(new ArrayList<PrintJob>());

    private static volatile boolean running = false;

    /**
     * How often to touch Omada while nothing is being sold.
     *
     * Two jobs, which is why it is far more often than a session check needs.
     * The gateway runs a whole shift unattended, so a lapsed session should be
     * found between sales rather than by a customer at the counter - a quarter
     * of an hour was plenty for that.
     *
     * It also keeps the HTTPS connection pooled. Idle sockets are dropped
     * within about a minute, and issuing a voucher is two calls to a server in
     * Singapore: if the first has to dial and shake hands, the customer waits
     * for it. Touching the portal every 45 seconds means a sale almost always
     * finds the connection already open. The cost is one small request a
     * minute; sales are minutes apart, so it buys back most of a second on
     * every voucher printed.
     */
    private static final long KEEPALIVE_MS = 45 * 1000L;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread keepaliveThread;

    /**
     * What keeps the tablet reachable while nobody is touching it.
     *
     * A foreground service keeps the process alive; it does not keep the radio
     * awake. With the screen off, wifi drops into power save and the tablet
     * stops answering an incoming connection promptly - or at all - so
     * Loyverse's print goes nowhere and the counter learns it only when no
     * paper comes out. Waking the app is what fixed it, which is why the
     * gateway had to be opened by hand.
     *
     * The wifi lock is the one that matters: it keeps the interface out of
     * power save so a connection to port 9100 is answered. The wake lock stops
     * the CPU sleeping through the middle of a job - a print is a couple of
     * seconds of work and a doze in the gap would strand it half printed.
     *
     * Both are held only while the gateway is listening, and both are released
     * in onDestroy. The cost is battery, which is the right trade for a tablet
     * that is plugged in at a counter.
     */
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    /**
     * One transparent pixel on the screen, and the reason the gateway stays
     * awake at all.
     *
     * A foreground service is a request, not a guarantee. Phone makers layer
     * their own battery saving over Android and freeze background processes
     * regardless - we watched this one go into freezer:/frozen while the phone
     * was awake, notifications were on and it sat on Android's own battery
     * whitelist. Frozen, it cannot answer Loyverse: the connection lands in
     * the kernel's accept queue, the receipt sits unread in the socket buffer,
     * and nothing prints until a person touches the phone.
     *
     * What none of those killers will touch is an app with a window on screen,
     * because that is indistinguishable from an app the user is looking at. So
     * the gateway keeps one: a single transparent pixel in the corner, not
     * focusable, not touchable, drawing nothing anyone can see. To Android the
     * process is visible and stays out of the freezer; to the counter there is
     * nothing there.
     *
     * It costs the "Display over other apps" permission, which is one toggle
     * and shows no UI of its own.
     */
    private View presence;

    public static List<PrintJob> jobs() {
        return JOBS;
    }

    public static void clearJobs() {
        JOBS.clear();
    }

    public static boolean isRunning() {
        return running;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The service draws replicas of its own, and can be restarted by the
        // system without the activity, so it loads the settings itself.
        Settings.init(this);
        if (running) {
            return START_STICKY;
        }
        startForeground(1, buildNotification("Connected"));
        running = true;
        holdRadios();
        showPresence();
        // Arm the safety net every time the gateway starts, so it is armed
        // again after a reboot, a reinstall, or a force stop.
        Watchdog.schedule(this);

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                serve();
            }
        }, "print-accept");
        acceptThread.start();

        keepaliveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                keepSessionAlive();
            }
        }, "omada-keepalive");
        keepaliveThread.start();

        return START_STICKY;
    }

    /** Keep wifi and the CPU awake enough to answer a print. */
    private void holdRadios() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null && wifiLock == null) {
                // HIGH_PERF rather than FULL: FULL still allows power save on
                // many devices, and power save is the whole problem here.
                wifiLock = wifi.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "printgateway:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
            PowerManager power =
                    (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power != null && wakeLock == null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "printgateway:cpu");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            Log.i(TAG, "holding wifi and cpu awake while listening");
        } catch (Exception e) {
            // A tablet that refuses the locks still prints while it is awake;
            // losing them is not a reason to refuse to start.
            Log.e(TAG, "could not hold the radios awake: " + e);
        }
    }

    /** Put the pixel on screen, if we are allowed windows at all. */
    private void showPresence() {
        if (presence != null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(this)) {
            Log.i(TAG, "no permission to show over other apps - the gateway "
                    + "will be frozen when it is not on screen");
            return;
        }
        try {
            WindowManager windows =
                    (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (windows == null) {
                return;
            }
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            WindowManager.LayoutParams where = new WindowManager.LayoutParams(
                    1, 1, type,
                    // Never take focus or touches: the cashier must not be
                    // able to hit this by accident, and Loyverse must keep
                    // every key press and tap it would otherwise get.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            where.gravity = Gravity.TOP | Gravity.START;
            where.x = 0;
            where.y = 0;
            View pixel = new View(this);
            pixel.setBackgroundColor(0x00000000);
            windows.addView(pixel, where);
            presence = pixel;
            Log.i(TAG, "holding a window on screen so the gateway stays visible");
        } catch (Exception e) {
            Log.e(TAG, "could not hold a window: " + e);
        }
    }

    private void hidePresence() {
        if (presence == null) {
            return;
        }
        try {
            WindowManager windows =
                    (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (windows != null) {
                windows.removeView(presence);
            }
        } catch (Exception ignored) {
            // shutting down anyway
        }
        presence = null;
    }

    private void releaseRadios() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
            // shutting down anyway
        }
        wifiLock = null;
        wakeLock = null;
    }

    /**
     * Keep the Omada session usable without anyone watching it.
     *
     * Only ever revives a session that has actually gone: a working one is left
     * alone, so this costs one small request every quarter of an hour.
     */
    private void keepSessionAlive() {
        while (running) {
            try {
                Thread.sleep(KEEPALIVE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                return;
            }
            if (!OmadaSession.isConnected()) {
                OmadaReconnector.revive(this);
            }
        }
    }

    private void serve() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            // 0.0.0.0 so it answers on loopback and on Wi-Fi alike: Loyverse
            // may be pointed at 127.0.0.1 or at the device's LAN address.
            serverSocket.bind(new InetSocketAddress("0.0.0.0", PORT));
            Log.i(TAG, "listening on " + PORT);

            while (running && !serverSocket.isClosed()) {
                final Socket client = serverSocket.accept();
                // Handle it off the accept thread. Issuing a voucher and
                // pushing a receipt over Bluetooth take a second or two, and
                // while that ran here the gateway was not accepting anything -
                // so a reprint, or the next sale, sat in the backlog waiting
                // for a print it had nothing to do with.
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(client);
                    }
                }, "print-job");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "server stopped: " + e);
            broadcastError(String.valueOf(e.getMessage()));
        } finally {
            running = false;
        }
    }

    /**
     * How long to wait on a silent socket before calling the job finished.
     *
     * Only a fallback now that a cut ends the job. It used to be 3000ms and
     * that was the single biggest part of the wait at the counter, because
     * Loyverse holds the connection open after sending.
     */
    private static final int IDLE_END_MS = 1200;

    /**
     * Whether the stream has just ended with a paper cut.
     *
     * Checked only against the tail, because raster data can contain these
     * bytes anywhere; a cut is always the last thing sent. Covers the
     * three-byte forms (GS V 0 / 1) and the four-byte ones (GS V A n, GS V B
     * n) - Loyverse sends GS V B 0.
     */
    private static boolean endsWithCut(ByteArrayOutputStream buffer) {
        byte[] b = buffer.toByteArray();
        int n = b.length;
        if (n >= 3 && b[n - 3] == 0x1D && b[n - 2] == 0x56) {
            return true;
        }
        return n >= 4 && b[n - 4] == 0x1D && b[n - 3] == 0x56;
    }

    private void handleClient(Socket client) {
        String peer = client.getInetAddress() != null
                ? client.getInetAddress().getHostAddress() : "unknown";
        // Which of our addresses Loyverse dialled. All of 127.0.0.0/8 is
        // loopback, so several printers can be configured on this one device
        // and told apart here, with no network involved at all.
        String dialled = client.getLocalAddress() != null
                ? client.getLocalAddress().getHostAddress() : "unknown";
        long acceptedAt = System.currentTimeMillis();
        long rawMs = 0;
        try {
            client.setSoTimeout(IDLE_END_MS);
            InputStream in = client.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];

            // A print ends when the POS closes the socket - but Loyverse does
            // not always close it, and waiting for a timeout to decide meant
            // the cashier stood there for whole seconds after the last byte
            // had arrived. Every ESC/POS job ends with a cut, so the cut is
            // the end of the job: stop the moment one lands. The idle timeout
            // stays as a fallback for a stream that carries no cut at all.
            long firstByteAt = 0;
            while (true) {
                int n;
                try {
                    n = in.read(chunk);
                } catch (java.net.SocketTimeoutException timeout) {
                    if (buffer.size() > 0) {
                        break;
                    }
                    continue;
                }
                if (n < 0) {
                    break;
                }
                if (firstByteAt == 0) {
                    firstByteAt = System.currentTimeMillis();
                }
                buffer.write(chunk, 0, n);
                if (endsWithCut(buffer)) {
                    break;
                }
            }
            long readDoneAt = System.currentTimeMillis();

            if (buffer.size() > 0) {
                byte[] bytes = buffer.toByteArray();
                PrintJob job = new PrintJob(peer, dialled, bytes);
                JOBS.add(0, job);
                Log.i(TAG, "job for " + dialled + " from " + peer + ", "
                        + bytes.length + " bytes");
                // Keep the raw stream. Guessing at another vendor's format is
                // how you get a parser that works on one receipt and no others,
                // so real captures are worth more than any amount of inference.
                long rawSavedAt;
                {
                    long before = System.currentTimeMillis();
                    saveRaw(bytes);
                    rawSavedAt = System.currentTimeMillis();
                    rawMs = rawSavedAt - before;
                }
                // Tell the screen straight away. The work below takes a second
                // or two, and a job that only appears once it has finished
                // printing looks like a gateway that was slow to accept it.
                sendBroadcast(new Intent(ACTION_JOB_RECEIVED).setPackage(getPackageName()));
                // Codes first: they have to be on the paper this sale prints,
                // and there is no second print to add them to.
                long t0 = System.currentTimeMillis();
                VoucherIssuer.fill(job, this);
                // Drawing is separated from saving the debug PNG, and encoding
                // from sending, because three of those four are ours to make
                // faster and the fourth is the printer's own speed.
                long t1 = System.currentTimeMillis();
                job.replica();
                long t2 = System.currentTimeMillis();
                saveReplica(job);
                long t3 = System.currentTimeMillis();
                byte[] payload = payloadFor(job);
                long t4 = System.currentTimeMillis();
                saveBytes(payload, "-print.bin");
                long t5 = System.currentTimeMillis();
                deliver(payload);
                long t6 = System.currentTimeMillis();
                // The paper is out and the customer has gone. Everything from
                // here is for us.
                VoucherIssuer.tidy(job);
                // Where the cashier's wait actually goes. Read this after a
                // real sale before optimising anything - the parts are not the
                // sizes you would guess.
                // One line per sale, every phase separated. Read it after a
                // real sale before optimising anything - the parts have never
                // once been the sizes they looked.
                // Composed after the tidying so its cost is in the line, but
                // reported apart from "counter", which stops at the paper.
                Log.i(TAG, "timing:"
                        + " wait " + (firstByteAt - acceptedAt)
                        + "ms, read " + (readDoneAt - firstByteAt)
                        + "ms, rawsave " + rawMs
                        + "ms, issue " + job.issueMs
                        + "ms, tidy " + job.tidyMs
                        + "ms, draw " + (t2 - t1)
                        + "ms, png " + (t3 - t2)
                        + "ms, encode " + (t4 - t3)
                        + "ms, binsave " + (t5 - t4)
                        + "ms, send " + (t6 - t5)
                        + "ms, counter " + (t6 - firstByteAt)
                        + "ms, total " + (System.currentTimeMillis() - acceptedAt) + "ms");
                sendBroadcast(new Intent(ACTION_JOB_RECEIVED).setPackage(getPackageName()));
                updateNotification(JOBS.size() + " receipt(s) printed");
            }
        } catch (Exception e) {
            Log.e(TAG, "client error: " + e);
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
                // closing a already-broken socket is not interesting
            }
        }
    }

    /**
     * The bytes to send for this job.
     *
     * What goes out is the redrawn receipt re-encoded the way Loyverse encodes
     * its own graphic prints. A job we could not redraw - an image, or
     * something with no items in it - is forwarded byte for byte instead:
     * whatever Loyverse meant by it, the printer already understood it.
     *
     * The drawer pulse is mirrored rather than assumed. Loyverse opens a sale's
     * print with one, and a till that stopped opening because the gateway was
     * put in front of it would be a strange thing to have to debug.
     */
    private byte[] payloadFor(PrintJob job) {
        android.graphics.Bitmap replica = job.replica();
        if (replica == null) {
            return job.raw;
        }
        return EscPosEncoder.encode(replica,
                job.decoded.events.contains("cash drawer pulse"));
    }

    /** Put it on the wire. Silent when no printer is chosen. */
    private void deliver(byte[] payload) {
        if (Settings.printerAddress().length() == 0) {
            return;
        }
        String problem = BluetoothPrinter.send(this, payload);
        if (problem != null) {
            Log.e(TAG, "could not print: " + problem);
            broadcastError("printer: " + problem);
        }
    }

    /**
     * Write the untouched bytes to external files, where adb can pull them
     * without root or run-as:
     *   /sdcard/Android/data/com.cafeservices.printgateway/files/jobs/
     */
    private void saveRaw(byte[] bytes) {
        saveBytes(bytes, ".bin");
    }

    private void saveBytes(byte[] bytes, String suffix) {
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "jobs");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            String name = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS",
                    java.util.Locale.US).format(new java.util.Date()) + suffix;
            java.io.File file = new java.io.File(dir, name);
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            Log.i(TAG, "saved " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "could not save raw job: " + e);
        }
    }

    /**
     * Render the receipt as Loyverse would have drawn it and write a PNG
     * beside the capture, so the replica can be compared with the real thing.
     */
    private void saveReplica(PrintJob job) {
        if (job.hasImage()) {
            return;
        }
        try {
            android.graphics.Bitmap bitmap = job.replica();
            if (bitmap == null) {
                return;
            }
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "jobs");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            String name = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS",
                    java.util.Locale.US).format(new java.util.Date()) + "-replica.png";
            java.io.File file = new java.io.File(dir, name);
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            try {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                out.close();
            }
            Log.i(TAG, "replica " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "replica failed: " + e);
        }
    }

    private void broadcastError(String message) {
        Intent intent = new Intent(ACTION_JOB_RECEIVED).setPackage(getPackageName());
        intent.putExtra("error", message);
        sendBroadcast(intent);
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // A foreground service must carry a notification - that is the
            // deal Android makes, and it is the notification that tells the
            // phone this app is doing real work rather than idling in the
            // background. Switching notifications off for the app therefore
            // gets it frozen, and a frozen gateway silently ignores prints.
            //
            // The quietest channel Android will accept keeps the deal while
            // showing the counter as little as possible: no sound, no pop-up,
            // no badge. The system clamps a foreground service's channel up to
            // LOW whatever we ask for, so a small status bar icon stays; the
            // wording is what is left to work with, and it says what this is -
            // a printer that is connected - rather than a developer tool.
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Receipt printer", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("Shows that receipt printing is working.");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            nm.createNotificationChannel(channel);
            // Leave nothing behind in the notification settings screen that
            // the owner could switch off by mistake.
            try {
                nm.deleteNotificationChannel(OLD_CHANNEL_ID);
            } catch (Exception ignored) {
                // it may never have existed on this install
            }
        }

        Intent open = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle("Receipt printer")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Bottom of the shade, under the silent heading.
            b.setPriority(Notification.PRIORITY_MIN);
        }
        return b.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(1, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        running = false;
        hidePresence();
        releaseRadios();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
            // shutting down anyway
        }
        super.onDestroy();
    }
}
