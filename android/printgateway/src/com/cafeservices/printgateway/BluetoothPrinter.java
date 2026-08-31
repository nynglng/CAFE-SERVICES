package com.cafeservices.printgateway;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends the finished print to the cafe's Bluetooth printer.
 *
 * Thermal printers speak Serial Port Profile: connect, write bytes, close.
 * They are also slow and small-buffered, so the payload goes out in chunks
 * with a breath between them; pushing a whole receipt at once is how you get
 * half a page and a hang.
 *
 * Whatever happens here, it must not cost the sale. The codes are already
 * issued and the picture is already drawn by the time this runs; a printer
 * that is off, out of range or busy is a thing to log, not a thing to fail.
 */
public final class BluetoothPrinter {

    /** Serial Port Profile - what every ESC/POS printer exposes. */
    private static final UUID SPP =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** Bytes per write. Small enough for a modest printer buffer. */
    private static final int CHUNK = 512;

    /** Time for the printer to swallow a chunk before the next one. */
    private static final long CHUNK_PAUSE_MS = 30;

    /** Time for the last chunk to reach paper before the socket closes. */

    private BluetoothPrinter() {
    }

    /** A printer as Android knows it. */
    public static final class Device {
        public final String name;
        public final String address;

        public Device(String name, String address) {
            this.name = name;
            this.address = address;
        }

        @Override
        public String toString() {
            return name + "  (" + address + ")";
        }
    }

    public static BluetoothAdapter adapter() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    public static boolean available() {
        BluetoothAdapter adapter = adapter();
        return adapter != null && adapter.isEnabled();
    }

    /** Printers already paired with this phone. */
    public static List<Device> paired() {
        List<Device> out = new ArrayList<Device>();
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            return out;
        }
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = device.getName();
                out.add(new Device(name == null ? device.getAddress() : name,
                        device.getAddress()));
            }
        } catch (SecurityException e) {
            Log.e(PrintServerService.TAG, "no permission to list printers: " + e);
        }
        return out;
    }

    /**
     * Send a finished ESC/POS stream to the configured printer.
     *
     * @return null on success, or a short reason to show and log
     */
    /** The open link to the printer, kept between prints. */
    private static BluetoothSocket socket;
    private static String connectedTo = "";
    private static final Object LOCK = new Object();

    /**
     * Send one job to the printer.
     *
     * The link is opened once and kept. An RFCOMM connect to a paired printer
     * costs the best part of a second or two, and paying that on every receipt
     * was a large part of the wait at the counter. A dropped link is not
     * unusual - printers sleep, someone walks out of range - so a failed write
     * closes the socket and tries once more with a fresh one.
     */
    public static String send(Context context, byte[] payload) {
        String address = Settings.printerAddress();
        if (address.length() == 0) {
            return "no printer chosen";
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            return "this phone has no Bluetooth";
        }
        if (!adapter.isEnabled()) {
            return "Bluetooth is off";
        }

        synchronized (LOCK) {
            String lastProblem = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    long t0 = System.currentTimeMillis();
                    boolean reused = isUsable(address);
                    BluetoothSocket live = connect(adapter, address);
                    long connected = System.currentTimeMillis();

                    OutputStream out = live.getOutputStream();
                    for (int offset = 0; offset < payload.length; offset += CHUNK) {
                        int length = Math.min(CHUNK, payload.length - offset);
                        out.write(payload, offset, length);
                        out.flush();
                        sleep(CHUNK_PAUSE_MS);
                    }
                    Log.i(PrintServerService.TAG, "printed " + payload.length
                            + " bytes to " + Settings.printerName()
                            + " (link " + (reused ? "reused" : "opened in "
                                    + (connected - t0) + "ms")
                            + ", sent in " + (System.currentTimeMillis() - connected) + "ms)");
                    return null;
                } catch (SecurityException e) {
                    return "no permission to use Bluetooth";
                } catch (Exception e) {
                    // Either the kept link had gone stale or the printer is
                    // genuinely unreachable; the retry tells them apart.
                    lastProblem = String.valueOf(e.getMessage());
                    Log.i(PrintServerService.TAG, "print attempt " + (attempt + 1)
                            + " failed: " + lastProblem);
                    drop();
                }
            }
            return lastProblem;
        }
    }

    private static boolean isUsable(String address) {
        return socket != null && socket.isConnected() && address.equals(connectedTo);
    }

    /** The open socket, dialling only if there is not already a good one. */
    private static BluetoothSocket connect(BluetoothAdapter adapter, String address)
            throws Exception {
        if (isUsable(address)) {
            return socket;
        }
        drop();
        BluetoothDevice device = adapter.getRemoteDevice(address);
        BluetoothSocket fresh = device.createRfcommSocketToServiceRecord(SPP);
        // Discovery cripples a connection attempt, and may be running if
        // someone just used the picker.
        adapter.cancelDiscovery();
        fresh.connect();
        socket = fresh;
        connectedTo = address;
        return fresh;
    }

    /** Let go of the link, so the next print dials a fresh one. */
    public static void drop() {
        synchronized (LOCK) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // closing a broken socket is not interesting
                }
            }
            socket = null;
            connectedTo = "";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
