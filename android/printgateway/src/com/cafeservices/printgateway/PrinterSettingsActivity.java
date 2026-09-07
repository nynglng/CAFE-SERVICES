package com.cafeservices.printgateway;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The printer, set up the way Loyverse sets up a printer.
 *
 * Deliberately the same form, in the same order, with the same names and the
 * same defaults: Name, Interface, the printer itself with a Search beside it,
 * Paper width, then Advanced settings carrying Print width, Print resolution
 * and the three ESC/POS command boxes. Anyone who has configured a printer in
 * Loyverse has already configured this one, which is the point - a new cafe
 * should be a thing the operator sets up, not a thing the app is edited for.
 *
 * Underneath sits the section Loyverse has no equivalent of: the Omada login,
 * the cafe, and what the voucher slip says.
 */
public class PrinterSettingsActivity extends Activity {

    private static final int OMADA_LOGIN = 3;

    private EditText label;
    private EditText initCommands;
    private EditText cutterCommands;
    private EditText drawerCommands;
    private EditText ssid;
    private TextView chosenPrinter;
    private TextView omadaState;
    private TextView slipPreview;
    private Switch printReceipts;
    private Spinner paperSpinner;
    private Spinner widthSpinner;
    private Spinner dpiSpinner;
    private Spinner siteSpinner;
    private Spinner accessSpinner;
    private Spinner expirySpinner;

    private final List<String> siteRows = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Settings.init(this);
        setContentView(R.layout.activity_printer);
        setTitle("Printer");

        label = (EditText) findViewById(R.id.printerLabel);
        initCommands = (EditText) findViewById(R.id.initCommands);
        cutterCommands = (EditText) findViewById(R.id.cutterCommands);
        drawerCommands = (EditText) findViewById(R.id.drawerCommands);
        ssid = (EditText) findViewById(R.id.ssidField);
        chosenPrinter = (TextView) findViewById(R.id.printerChosen);
        omadaState = (TextView) findViewById(R.id.omadaState);
        slipPreview = (TextView) findViewById(R.id.slipPreview);
        printReceipts = (Switch) findViewById(R.id.printReceipts);
        paperSpinner = (Spinner) findViewById(R.id.paperSpinner);
        widthSpinner = (Spinner) findViewById(R.id.widthSpinner);
        dpiSpinner = (Spinner) findViewById(R.id.dpiSpinner);
        siteSpinner = (Spinner) findViewById(R.id.siteSpinner);
        accessSpinner = (Spinner) findViewById(R.id.accessSpinner);
        expirySpinner = (Spinner) findViewById(R.id.expirySpinner);

        label.setText(Settings.printerLabel());
        initCommands.setText(Settings.initCommands());
        cutterCommands.setText(Settings.cutterCommands());
        drawerCommands.setText(Settings.drawerCommands());
        ssid.setText(Settings.ssid());
        printReceipts.setChecked(Settings.printReceipts());

        fill(paperSpinner, PaperProfile.names());
        select(paperSpinner, PaperProfile.names(), PaperProfile.configured().name);
        fill(widthSpinner, millimetres(Settings.PRINT_WIDTH_CHOICES));
        select(widthSpinner, millimetres(Settings.PRINT_WIDTH_CHOICES),
                Settings.printWidthMm() + " mm");
        fill(dpiSpinner, dpiLabels());
        select(dpiSpinner, dpiLabels(), Settings.dpi() + " dpi (8 dots/mm)");
        fill(accessSpinner, hours(Settings.ACCESS_CHOICES));
        select(accessSpinner, hours(Settings.ACCESS_CHOICES),
                hourLabel(Settings.accessHours()));
        fill(expirySpinner, hours(Settings.EXPIRY_CHOICES));
        select(expirySpinner, hours(Settings.EXPIRY_CHOICES),
                hourLabel(Settings.expiryHours()));
        loadSites();

        ((Button) findViewById(R.id.searchButton)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PrinterPicker.show(PrinterSettingsActivity.this, new Runnable() {
                            @Override
                            public void run() {
                                showPrinter();
                            }
                        });
                    }
                });

        ((Button) findViewById(R.id.omadaButton)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivityForResult(new Intent(PrinterSettingsActivity.this,
                                OmadaLoginActivity.class), OMADA_LOGIN);
                    }
                });

        ((Button) findViewById(R.id.testButton)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        printTest();
                    }
                });

        printReceipts.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Settings.setPrintReceipts(checked);
            }
        });

        AdapterView.OnItemSelectedListener preview =
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View v, int i, long id) {
                        showSlip();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                };
        accessSpinner.setOnItemSelectedListener(preview);
        expirySpinner.setOnItemSelectedListener(preview);

        showPrinter();
        showSlip();
        checkOmada();
    }

    /**
     * Everything is saved on the way out rather than behind a Save button,
     * which is how Loyverse's own printer form behaves.
     */
    @Override
    protected void onPause() {
        super.onPause();
        Settings.setPrinterLabel(label.getText().toString().trim());
        Settings.setCommands(initCommands.getText().toString().trim(),
                cutterCommands.getText().toString().trim(),
                drawerCommands.getText().toString().trim());
        Settings.setPaperName((String) paperSpinner.getSelectedItem());
        Settings.setPrintWidthMm(
                Settings.PRINT_WIDTH_CHOICES[widthSpinner.getSelectedItemPosition()]);
        Settings.setDpi(Settings.DPI_CHOICES[dpiSpinner.getSelectedItemPosition()]);
        Settings.setAccessHours(
                Settings.ACCESS_CHOICES[accessSpinner.getSelectedItemPosition()]);
        Settings.setExpiryHours(
                Settings.EXPIRY_CHOICES[expirySpinner.getSelectedItemPosition()]);

        String network = ssid.getText().toString().trim();
        if (network.length() > 0) {
            Settings.setSsid(network);
            Settings.rememberNetwork(network);
        }
        if (!siteRows.isEmpty() && siteSpinner.getSelectedItemPosition() >= 0) {
            String row = siteRows.get(siteSpinner.getSelectedItemPosition());
            Settings.setSite(idOf(row), nameOf(row));
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
     * Print a page that proves the whole path, not just the connection.
     *
     * Loyverse's own Print test says the printer is reachable. This one is
     * drawn by the same renderer a receipt goes through and encoded with the
     * commands in the boxes above, so if the cutter command is wrong or the
     * width is set to the wrong paper, this is where it shows.
     */
    private void printTest() {
        onPause(); // take what is on screen before printing with it
        Toast.makeText(this, "Sending test page...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                android.graphics.Bitmap page = TestPage.render(PaperProfile.configured());
                final String problem = BluetoothPrinter.send(PrinterSettingsActivity.this,
                        EscPosEncoder.encode(page, false));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PrinterSettingsActivity.this,
                                problem == null ? "Test page sent." : "Failed: " + problem,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void showPrinter() {
        String name = Settings.printerName();
        chosenPrinter.setText(name.length() > 0 ? name : "Not set");
    }

    private void showSlip() {
        int access = Settings.ACCESS_CHOICES[Math.max(0,
                accessSpinner.getSelectedItemPosition())];
        int expiry = Settings.EXPIRY_CHOICES[Math.max(0,
                expirySpinner.getSelectedItemPosition())];
        slipPreview.setText("The slip will say:\n  "
                + VoucherRenderer.validityLine(access) + "\n  Wi-Fi Name: "
                + ssid.getText().toString().trim() + "\n  "
                + VoucherRenderer.expiryLine(expiry));
    }

    private void checkOmada() {
        omadaState.setText("Omada: checking...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean connected = OmadaSession.isConnected();
                if (connected) {
                    OmadaDiscovery.refresh();
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        omadaState.setText(connected
                                ? "Omada: " + Settings.siteName()
                                : "Omada: not connected");
                        loadSites();
                    }
                });
            }
        }).start();
    }

    private void loadSites() {
        siteRows.clear();
        siteRows.addAll(Settings.knownSites());
        if (siteRows.isEmpty()) {
            siteRows.add(Settings.siteId() + "|"
                    + (Settings.siteName().length() > 0 ? Settings.siteName() : "not set"));
        }
        List<String> names = new ArrayList<String>();
        for (String row : siteRows) {
            names.add(nameOf(row));
        }
        fill(siteSpinner, names);
        for (int i = 0; i < siteRows.size(); i++) {
            if (idOf(siteRows.get(i)).equals(Settings.siteId())) {
                siteSpinner.setSelection(i);
                break;
            }
        }
    }

    // --- small helpers -----------------------------------------------------

    private static String idOf(String row) {
        int bar = row.indexOf('|');
        return bar < 0 ? row : row.substring(0, bar);
    }

    private static String nameOf(String row) {
        int bar = row.indexOf('|');
        return bar < 0 ? row : row.substring(bar + 1);
    }

    private void fill(Spinner spinner, List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private static void select(Spinner spinner, List<String> values, String value) {
        int index = values.indexOf(value);
        spinner.setSelection(index < 0 ? 0 : index);
    }

    private static List<String> millimetres(int[] values) {
        List<String> out = new ArrayList<String>();
        for (int value : values) {
            out.add(value + " mm");
        }
        return out;
    }

    private static List<String> dpiLabels() {
        List<String> out = new ArrayList<String>();
        for (int value : Settings.DPI_CHOICES) {
            out.add(value + " dpi (8 dots/mm)");
        }
        return out;
    }

    private static List<String> hours(int[] values) {
        List<String> out = new ArrayList<String>();
        for (int value : values) {
            out.add(hourLabel(value));
        }
        return out;
    }

    private static String hourLabel(int hours) {
        return hours + (hours == 1 ? " hour" : " hours");
    }
}
