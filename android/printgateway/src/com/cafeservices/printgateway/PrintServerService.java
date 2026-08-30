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
                PrintJob job = new PrintJob(peer, buffer.toByteArray());
                JOBS.add(0, job);
                Log.i(TAG, "job from " + peer + ", " + buffer.size() + " bytes");
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
