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
    private static final long DRAIN_MS = 600;

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

        BluetoothSocket socket = null;
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            socket = device.createRfcommSocketToServiceRecord(SPP);
            // Discovery cripples a connection attempt, and may be running if
            // someone just used the picker.
            adapter.cancelDiscovery();
            socket.connect();

            OutputStream out = socket.getOutputStream();
            for (int offset = 0; offset < payload.length; offset += CHUNK) {
                int length = Math.min(CHUNK, payload.length - offset);
                out.write(payload, offset, length);
                out.flush();
                sleep(CHUNK_PAUSE_MS);
            }
            sleep(DRAIN_MS);
            Log.i(PrintServerService.TAG, "printed " + payload.length + " bytes to "
                    + Settings.printerName());
            return null;
        } catch (SecurityException e) {
            return "no permission to use Bluetooth";
        } catch (Exception e) {
            Log.e(PrintServerService.TAG, "printing failed: " + e);
            return String.valueOf(e.getMessage());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // closing a broken socket is not interesting
                }
            }
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
