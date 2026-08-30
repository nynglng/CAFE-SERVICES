package com.cafeservices.printgateway;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * What differs per client, behind one Save: which cafe, and what its slip says.
 *
 * Nothing is written until Save is pressed, so a half-made change cannot reach
 * a customer's slip - the gateway keeps printing with the old settings while
 * the dialog is open. The preview line at the bottom shows the wording that
 * will actually appear on paper, since "2" and "24" mean nothing out of
 * context and everything on it.
 */
public final class SettingsDialog {

    /** Last entry of the network list: opens a prompt for a new name. */
    private static final String ADD_NETWORK = "Add a network...";

    private SettingsDialog() {
    }

    public static void show(final Activity activity, final Runnable onSaved) {
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_settings, null);

        final Spinner siteSpinner = (Spinner) view.findViewById(R.id.siteSpinner);
        final Spinner ssidSpinner = (Spinner) view.findViewById(R.id.ssidSpinner);
        final Spinner accessSpinner = (Spinner) view.findViewById(R.id.accessSpinner);
        final Spinner expirySpinner = (Spinner) view.findViewById(R.id.expirySpinner);
        final TextView preview = (TextView) view.findViewById(R.id.settingsPreview);

        // Sites come from the Omada login, so the list is whatever this
        // account can see. Stored between runs, so the picker still works when
        // the phone is offline.
        final List<String> siteRows = new ArrayList<String>(Settings.knownSites());
        if (siteRows.isEmpty()) {
            siteRows.add(Settings.siteId() + "|"
                    + (Settings.siteName().length() > 0
                            ? Settings.siteName() : "current site"));
        }
        List<String> siteNames = new ArrayList<String>();
        for (String row : siteRows) {
            siteNames.add(nameOf(row));
        }
        fill(activity, siteSpinner, siteNames);
        for (int i = 0; i < siteRows.size(); i++) {
            if (idOf(siteRows.get(i)).equals(Settings.siteId())) {
                siteSpinner.setSelection(i);
                break;
            }
        }

        final List<String> networks = new ArrayList<String>(Settings.networks());
        networks.add(ADD_NETWORK);
        fill(activity, ssidSpinner, networks);
        ssidSpinner.setSelection(networks.indexOf(Settings.ssid()));

        final List<String> access = hours(Settings.ACCESS_CHOICES);
        fill(activity, accessSpinner, access);
        accessSpinner.setSelection(indexOf(Settings.ACCESS_CHOICES, Settings.accessHours()));

        final List<String> expiry = hours(Settings.EXPIRY_CHOICES);
        fill(activity, expirySpinner, expiry);
        expirySpinner.setSelection(indexOf(Settings.EXPIRY_CHOICES, Settings.expiryHours()));

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                String network = (String) ssidSpinner.getSelectedItem();
                if (ADD_NETWORK.equals(network)) {
                    network = Settings.ssid();
                }
                int accessHours = Settings.ACCESS_CHOICES[accessSpinner.getSelectedItemPosition()];
                int expiryHours = Settings.EXPIRY_CHOICES[expirySpinner.getSelectedItemPosition()];
                preview.setText("The slip will say:\n\n"
                        + "  " + VoucherRenderer.validityLine(accessHours) + "\n"
                        + "  Wi-Fi Name: " + network + "\n"
                        + "  " + VoucherRenderer.expiryLine(expiryHours));
            }
        };

        AdapterView.OnItemSelectedListener watcher = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (parent == ssidSpinner && ADD_NETWORK.equals(parent.getItemAtPosition(position))) {
                    promptForNetwork(activity, ssidSpinner, networks, refresh);
                    return;
                }
                refresh.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        ssidSpinner.setOnItemSelectedListener(watcher);
        accessSpinner.setOnItemSelectedListener(watcher);
        expirySpinner.setOnItemSelectedListener(watcher);
        refresh.run();

        new AlertDialog.Builder(activity)
                .setTitle("Voucher settings")
                .setView(view)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String network = (String) ssidSpinner.getSelectedItem();
                        if (!ADD_NETWORK.equals(network)) {
                            Settings.setSsid(network);
                        }
                        Settings.setAccessHours(
                                Settings.ACCESS_CHOICES[accessSpinner.getSelectedItemPosition()]);
                        Settings.setExpiryHours(
                                Settings.EXPIRY_CHOICES[expirySpinner.getSelectedItemPosition()]);
                        String site = siteRows.get(siteSpinner.getSelectedItemPosition());
                        Settings.setSite(idOf(site), nameOf(site));
                        onSaved.run();
                    }
                })
                .show();
    }

    /** A network name typed by hand, kept in the list for next time. */
    private static void promptForNetwork(final Activity activity, final Spinner spinner,
                                         final List<String> networks, final Runnable refresh) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Wi-Fi name as guests see it");

        new AlertDialog.Builder(activity)
                .setTitle("Add a network")
                .setView(input)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        select(spinner, networks, Settings.ssid());
                    }
                })
                .setPositiveButton("Add", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.length() == 0) {
                            select(spinner, networks, Settings.ssid());
                            return;
                        }
                        // Remembered now rather than on Save: the list has to
                        // hold it to be able to show it as the selection.
                        Settings.rememberNetwork(name);
                        if (!networks.contains(name)) {
                            networks.add(networks.size() - 1, name);
                        }
                        fill(activity, spinner, networks);
                        select(spinner, networks, name);
                        refresh.run();
                    }
                })
                .show();
    }

    /** Sites are stored as "id|name" so one row carries both. */
    private static String idOf(String row) {
        int bar = row.indexOf('|');
        return bar < 0 ? row : row.substring(0, bar);
    }

    private static String nameOf(String row) {
        int bar = row.indexOf('|');
        return bar < 0 ? row : row.substring(bar + 1);
    }

    private static void select(Spinner spinner, List<String> values, String value) {
        int index = values.indexOf(value);
        spinner.setSelection(index >= 0 ? index : 0);
    }

    private static void fill(Activity activity, Spinner spinner, List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private static List<String> hours(int[] choices) {
        List<String> out = new ArrayList<String>();
        for (int hours : choices) {
            out.add(hours + (hours == 1 ? " hour" : " hours"));
        }
        return out;
    }

    private static int indexOf(int[] choices, int value) {
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == value) {
                return i;
            }
        }
        return 0;
    }
}
