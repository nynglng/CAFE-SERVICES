package com.cafeservices.printgateway;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.widget.ArrayAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Choosing the printer, from what the phone can see.
 *
 * Paired printers come first, because that is what a set-up till has. Search
 * then adds anything discoverable nearby, for the first run at a new cafe -
 * a printer that has never been paired shows up here, and picking it lets
 * Android pair on the first print.
 */
public final class PrinterPicker {

    private PrinterPicker() {
    }

    public static void show(final Activity activity, final Runnable onChosen) {
        if (BluetoothPrinter.adapter() == null) {
            toast(activity, "This phone has no Bluetooth.");
            return;
        }
        if (!BluetoothPrinter.available()) {
            toast(activity, "Turn Bluetooth on first.");
            return;
        }

        final List<BluetoothPrinter.Device> devices =
                new ArrayList<BluetoothPrinter.Device>(BluetoothPrinter.paired());
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity,
                android.R.layout.simple_list_item_single_choice);
        for (BluetoothPrinter.Device device : devices) {
            adapter.add(device.toString());
        }
        if (adapter.getCount() == 0) {
            adapter.add("No paired printers - press Search");
        }

        int checked = -1;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).address.equals(Settings.printerAddress())) {
                checked = i;
                break;
            }
        }

        final BroadcastReceiver found = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    return;
                }
                BluetoothDevice device =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) {
                    return;
                }
                try {
                    for (BluetoothPrinter.Device known : devices) {
                        if (known.address.equals(device.getAddress())) {
                            return;
                        }
                    }
                    String name = device.getName();
                    BluetoothPrinter.Device row = new BluetoothPrinter.Device(
                            name == null ? device.getAddress() : name, device.getAddress());
                    if (devices.isEmpty()) {
                        adapter.clear(); // drop the "none paired" placeholder
                    }
                    devices.add(row);
                    adapter.add(row.toString());
                } catch (SecurityException e) {
                    Log.e(PrintServerService.TAG, "cannot read found device: " + e);
                }
            }
        };
        activity.registerReceiver(found, new IntentFilter(BluetoothDevice.ACTION_FOUND));

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Printer")
                .setSingleChoiceItems(adapter, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which < devices.size()) {
                            BluetoothPrinter.Device device = devices.get(which);
                            Settings.setPrinter(device.name, device.address);
                        }
                    }
                })
                .setNeutralButton("Search", null) // wired below so it stays open
                .setNegativeButton("Close", null)
                .setPositiveButton("Use this", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        onChosen.run();
                    }
                })
                .create();

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                stopSearch(activity, found);
            }
        });
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        search(activity);
                    }
                });
    }

    private static void search(Activity activity) {
        BluetoothAdapter adapter = BluetoothPrinter.adapter();
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            if (adapter.startDiscovery()) {
                toast(activity, "Searching... printers appear as they answer.");
            } else {
                toast(activity, "Could not start searching.");
            }
        } catch (SecurityException e) {
            toast(activity, "Allow Bluetooth access, then search again.");
        }
    }

    private static void stopSearch(Activity activity, BroadcastReceiver receiver) {
        try {
            BluetoothAdapter adapter = BluetoothPrinter.adapter();
            if (adapter != null && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
            // nothing to cancel without permission
        }
        try {
            activity.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // already gone
        }
    }

    private static void toast(Activity activity, String message) {
        android.widget.Toast.makeText(activity, message,
                android.widget.Toast.LENGTH_LONG).show();
    }
}
