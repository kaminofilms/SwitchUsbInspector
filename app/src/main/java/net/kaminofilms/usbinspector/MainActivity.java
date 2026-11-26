package net.kaminofilms.usbinspector;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String ACTION_PERMISSION = "net.kaminofilms.usbinspector.USB_PERMISSION";
    /** Hidden-but-stable sticky broadcast describing the phone's own USB role. */
    static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    static final int REQ_PICK_IMAGE = 42;
    static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    static final int MAX_SHARE_CHARS = 400_000;

    static final String NO_DEVICE_HELP =
            "No USB devices visible. The phone isn't acting as USB host for anything right now.\n"
          + "  If the phone's USB state above shows connected=true, the phone is the USB *device* and the Switch is the host,\n"
          + "  or it's a charge-only link. In that role Android can't see the Switch at all. Things to try:\n"
          + "  • Use a USB-C OTG adapter or an OTG-capable C-to-C cable, and turn on OTG if your phone has a setting for it\n"
          + "  • Flip the cable ends; keep the Switch 2 awake in handheld mode\n"
          + "  • Developer options → Default USB configuration → 'No data transfer', then replug";

    private UsbManager usb;
    private TextView logView;
    private ScrollView logScroll;
    private Spinner deviceSpinner;
    private Spinner endpointSpinner;
    private CheckBox lengthPrefixBox;
    private CheckBox readReplyBox;
    private final List<UsbDevice> devices = new ArrayList<>();
    private final List<UsbSender.Target> targets = new ArrayList<>();
    private final StringBuilder logText = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private volatile boolean busy;
    private boolean receiverRegistered;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice dev = deviceFrom(intent);
            String name = dev == null ? "?" : UsbDumper.shortName(dev);
            if (ACTION_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                log("Permission " + (granted ? "GRANTED" : "DENIED") + " for " + name);
                if (granted && dev != null) dumpFull(dev);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("ATTACHED: " + name);
                scan();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("DETACHED: " + name);
                scan();
            } else if (ACTION_USB_STATE.equals(action)) {
                log("Phone USB state: " + extrasToString(intent) + roleHint(intent));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        setContentView(buildUi());

        log("Switch USB Inspector 1.0. Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL);
        if (usb == null) {
            log("ERROR: this phone has no USB service.");
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(ACTION_USB_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);
        receiverRegistered = true;

        scan();
        handleLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(usbReceiver);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void handleLaunchIntent(Intent i) {
        if (i == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) return;
        UsbDevice d = deviceFrom(i);
        if (d == null) return;
        log("Opened because a Nintendo USB device was plugged in: " + UsbDumper.shortName(d));
        scan();
        if (usb.hasPermission(d)) dumpFull(d);
    }

    // ---------- UI ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private void addWeighted(LinearLayout row, View v) {
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, dp(28), pad, pad);

        TextView title = new TextView(this);
        title.setText("Switch USB Inspector");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout r1 = row();
        addWeighted(r1, button("Scan", v -> scan()));
        addWeighted(r1, button("Permission", v -> requestPermissionForSelected()));
        addWeighted(r1, button("Full dump", v -> dumpSelected()));
        root.addView(r1);

        root.addView(label("Device"));
        deviceSpinner = new Spinner(this);
        root.addView(deviceSpinner);
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) { refreshTargets(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { refreshTargets(); }
        });

        root.addView(label("Send to OUT endpoint (experimental)"));
        endpointSpinner = new Spinner(this);
        root.addView(endpointSpinner);

        lengthPrefixBox = new CheckBox(this);
        lengthPrefixBox.setText("Prefix with 4-byte length (little-endian)");
        root.addView(lengthPrefixBox);
        readReplyBox = new CheckBox(this);
        readReplyBox.setText("Read a reply from the IN endpoint afterwards");
        readReplyBox.setChecked(true);
        root.addView(readReplyBox);

        LinearLayout r2 = row();
        addWeighted(r2, button("Send image", v -> pickImage()));
        addWeighted(r2, button("Share log", v -> shareLog()));
        addWeighted(r2, button("Clear", v -> clearLog()));
        root.addView(r2);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // ---------- Logging ----------

    private void log(String msg) {
        final String line;
        synchronized (clock) {
            line = "[" + clock.format(new Date()) + "] " + msg + "\n";
        }
        ui.post(() -> {
            logText.append(line);
            logView.append(line);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void clearLog() {
        logText.setLength(0);
        logView.setText("");
    }

    private void shareLog() {
        String s = logText.toString();
        if (s.length() > MAX_SHARE_CHARS) s = "…(trimmed)…\n" + s.substring(s.length() - MAX_SHARE_CHARS);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Switch USB Inspector log");
        i.putExtra(Intent.EXTRA_TEXT, s);
        startActivity(Intent.createChooser(i, "Share log"));
    }

    // ---------- Scanning ----------

    @SuppressWarnings("deprecation")
    private static UsbDevice deviceFrom(Intent i) {
        return i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private UsbDevice selectedDevice() {
        int pos = deviceSpinner.getSelectedItemPosition();
        return pos >= 0 && pos < devices.size() ? devices.get(pos) : null;
    }

    private UsbSender.Target selectedTarget() {
        int pos = endpointSpinner.getSelectedItemPosition();
        return pos >= 0 && pos < targets.size() ? targets.get(pos) : null;
    }

    private void scan() {
        if (usb == null) return;
        log("===== Scan =====\n" + phoneStateReport());
        UsbDevice previous = selectedDevice();
        String previousName = previous == null ? null : previous.getDeviceName();

        Map<String, UsbDevice> map = usb.getDeviceList();
        devices.clear();
        devices.addAll(map.values());
        Collections.sort(devices, (a, b) -> Boolean.compare(UsbDumper.isNintendo(b), UsbDumper.isNintendo(a)));

        UsbAccessory[] acc = usb.getAccessoryList();
        if (acc != null) {
            for (UsbAccessory a : acc) {
                log("Accessory talking to the phone (phone is USB device): " + a.getManufacturer() + " / " + a.getModel() + " v" + a.getVersion());
            }
        }

        List<String> labels = new ArrayList<>();
        if (devices.isEmpty()) {
            log(NO_DEVICE_HELP);
            labels.add("(no devices)");
        } else {
            log(devices.size() + " device(s) found:");
            for (UsbDevice d : devices) {
                log(UsbDumper.summary(d));
                if (!usb.hasPermission(d)) log("  → tap Permission, then Full dump, to get strings and raw descriptors");
                labels.add((UsbDumper.isNintendo(d) ? "★ " : "") + UsbDumper.shortName(d));
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(adapter);
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).getDeviceName().equals(previousName)) deviceSpinner.setSelection(i);
        }
        refreshTargets();
    }

    private void refreshTargets() {
        targets.clear();
        targets.addAll(UsbSender.findOutTargets(selectedDevice()));
        List<String> labels = new ArrayList<>();
        for (UsbSender.Target t : targets) labels.add(t.label());
        if (labels.isEmpty()) labels.add("(no bulk/interrupt OUT endpoint)");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        endpointSpinner.setAdapter(adapter);
    }

    private void requestPermissionForSelected() {
        UsbDevice d = selectedDevice();
        if (d == null) { log("No device selected. Plug something in and tap Scan."); return; }
        requestPermission(d);
    }

    private void requestPermission(UsbDevice d) {
        if (usb.hasPermission(d)) { log("Already have permission for " + UsbDumper.shortName(d)); return; }
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent i = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, flags);
        usb.requestPermission(d, pi);
        log("Asked Android for access to " + UsbDumper.shortName(d) + ". Accept the popup.");
    }

    private void dumpSelected() {
        UsbDevice d = selectedDevice();
        if (d == null) { log("No device selected."); return; }
        if (!usb.hasPermission(d)) { requestPermission(d); return; }
        dumpFull(d);
    }

    private void dumpFull(UsbDevice d) {
        worker.execute(() -> {
            try {
                log(UsbDumper.fullDump(usb, d));
            } catch (Exception e) {
                log("Full dump failed: " + e);
            }
        });
    }

    // ---------- Phone-side state ----------

    private Intent sticky(String action) {
        try {
            IntentFilter f = new IntentFilter(action);
            return Build.VERSION.SDK_INT >= 33
                    ? registerReceiver(null, f, Context.RECEIVER_NOT_EXPORTED)
                    : registerReceiver(null, f);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static String extrasToString(Intent i) {
        Bundle b = i.getExtras();
        if (b == null) return "(no data)";
        StringBuilder sb = new StringBuilder();
        for (String k : new TreeSet<>(b.keySet())) sb.append(k).append('=').append(b.get(k)).append(' ');
        return sb.toString().trim();
    }

    private static String roleHint(Intent usbState) {
        boolean deviceMode = usbState.getBooleanExtra("connected", false);
        boolean hostMode = usbState.getBooleanExtra("host_connected", false);
        if (hostMode) return "\n  → Phone is USB HOST: connected devices should be listed below.";
        if (deviceMode) return "\n  → Phone is USB DEVICE: the other end (the Switch?) is the host, so Android won't list it as a device.";
        return "\n  → No USB data role active (charge-only or nothing plugged in).";
    }

    private String phoneStateReport() {
        StringBuilder sb = new StringBuilder("Phone side:\n");
        Intent st = sticky(ACTION_USB_STATE);
        sb.append("  USB state: ").append(st == null ? "unavailable" : extrasToString(st) + roleHint(st)).append('\n');
        Intent bat = sticky(Intent.ACTION_BATTERY_CHANGED);
        if (bat != null) {
            int plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            String p = plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB (something is charging the phone)"
                    : plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC charger"
                    : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless"
                    : plugged == 0 ? "not charging (phone may be powering the other device)" : String.valueOf(plugged);
            String s = status == BatteryManager.BATTERY_STATUS_CHARGING ? "charging"
                    : status == BatteryManager.BATTERY_STATUS_DISCHARGING ? "discharging"
                    : status == BatteryManager.BATTERY_STATUS_FULL ? "full"
                    : status == BatteryManager.BATTERY_STATUS_NOT_CHARGING ? "not charging" : String.valueOf(status);
            sb.append("  Power: plugged=").append(p).append(", battery ").append(s).append('\n');
        }
        sb.append("  USB host feature: ").append(getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST));
        return sb.toString();
    }

    // ---------- Sending ----------

    private void pickImage() {
        if (busy) { log("Still sending. Wait for it to finish."); return; }
        UsbDevice d = selectedDevice();
        if (d == null) { log("No device selected."); return; }
        if (selectedTarget() == null) {
            log("The selected device has no bulk/interrupt OUT endpoint, so there's nowhere to send data. "
                    + "On a stock Switch 2 this is the expected result.");
            return;
        }
        if (!usb.hasPermission(d)) {
            log("Need permission first. Accept the popup, then tap Send image again.");
            requestPermission(d);
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) { log("No image picked."); return; }
        sendImage(data.getData());
    }

    private void sendImage(Uri uri) {
        final UsbDevice dev = selectedDevice();
        final UsbSender.Target target = selectedTarget();
        final boolean prefix = lengthPrefixBox.isChecked();
        final boolean reply = readReplyBox.isChecked();
        if (dev == null || target == null) { log("Device or endpoint went away. Tap Scan."); return; }
        busy = true;
        worker.execute(() -> {
            try {
                byte[] bytes = readAll(uri);
                log("Image loaded: " + bytes.length + " bytes");
                UsbSender.send(usb, dev, target, bytes, prefix, reply, this::log);
            } catch (Exception e) {
                log("Send failed: " + e);
            } finally {
                busy = false;
            }
        });
    }

    private byte[] readAll(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("couldn't open the image");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_FILE_BYTES) throw new IOException("image is larger than 64 MB");
            }
            return out.toByteArray();
        }
    }
}
