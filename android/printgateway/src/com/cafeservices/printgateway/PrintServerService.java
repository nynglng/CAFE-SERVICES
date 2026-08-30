package com.cafeservices.printgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
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
    private static final String CHANNEL_ID = "print_gateway";

    /** Jobs are kept in memory only; nothing is written to storage yet. */
    private static final List<PrintJob> JOBS =
            Collections.synchronizedList(new ArrayList<PrintJob>());

    private static volatile boolean running = false;

    private ServerSocket serverSocket;
    private Thread acceptThread;

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
        startForeground(1, buildNotification("Listening on port " + PORT));
        running = true;

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                serve();
            }
        }, "print-accept");
        acceptThread.start();

        return START_STICKY;
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
                Socket client = serverSocket.accept();
                handleClient(client);
            }
        } catch (Exception e) {
            Log.e(TAG, "server stopped: " + e);
            broadcastError(String.valueOf(e.getMessage()));
        } finally {
            running = false;
        }
    }

    private void handleClient(Socket client) {
        String peer = client.getInetAddress() != null
                ? client.getInetAddress().getHostAddress() : "unknown";
        // Which of our addresses Loyverse dialled. All of 127.0.0.0/8 is
        // loopback, so several printers can be configured on this one device
        // and told apart here, with no network involved at all.
        String dialled = client.getLocalAddress() != null
                ? client.getLocalAddress().getHostAddress() : "unknown";
        try {
            client.setSoTimeout(3000);
            InputStream in = client.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];

            // A print ends when the POS closes the socket. Some clients hold it
            // open, so a read timeout with data already buffered also counts as
            // the end of the job rather than an error.
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
                buffer.write(chunk, 0, n);
            }

            if (buffer.size() > 0) {
                byte[] bytes = buffer.toByteArray();
                PrintJob job = new PrintJob(peer, dialled, bytes);
                JOBS.add(0, job);
                Log.i(TAG, "job for " + dialled + " from " + peer + ", "
                        + bytes.length + " bytes");
                // Keep the raw stream. Guessing at another vendor's format is
                // how you get a parser that works on one receipt and no others,
                // so real captures are worth more than any amount of inference.
                saveRaw(bytes);
                // Codes first: they have to be on the paper this sale prints,
                // and there is no second print to add them to.
                VoucherIssuer.fill(job);
                saveReplica(job);
                sendBroadcast(new Intent(ACTION_JOB_RECEIVED).setPackage(getPackageName()));
                updateNotification(JOBS.size() + " job(s) received");
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
     * Write the untouched bytes to external files, where adb can pull them
     * without root or run-as:
     *   /sdcard/Android/data/com.cafeservices.printgateway/files/jobs/
     */
    private void saveRaw(byte[] bytes) {
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "jobs");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            String name = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS",
                    java.util.Locale.US).format(new java.util.Date()) + ".bin";
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
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Print Gateway", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setContentTitle("Print Gateway")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(1, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        running = false;
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
