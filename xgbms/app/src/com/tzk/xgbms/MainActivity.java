package com.tzk.xgbms;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.Notification;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {

    static final String TARGET_MAC = "69:2E:03:43:67:C0";
    static final String NAME_PREFIX = "T106";
    static final UUID SVC   = UUID.fromString("00008900-0000-1000-8000-00805f9b34fb");
    static final UUID C_WAKE = UUID.fromString("00008901-0000-1000-8000-00805f9b34fb");
    static final UUID C_SUM = UUID.fromString("00008902-0000-1000-8000-00805f9b34fb");
    static final UUID C_C1  = UUID.fromString("00008903-0000-1000-8000-00805f9b34fb");
    static final UUID C_C2  = UUID.fromString("00008904-0000-1000-8000-00805f9b34fb");
    static final UUID C_E1  = UUID.fromString("00008905-0000-1000-8000-00805f9b34fb");
    static final UUID C_E2  = UUID.fromString("00008906-0000-1000-8000-00805f9b34fb");
    static final UUID C_PRT = UUID.fromString("00008907-0000-1000-8000-00805f9b34fb");

    WebView wv;
    final Handler ui = new Handler(Looper.getMainLooper());
    BluetoothAdapter adapter;
    BluetoothLeScanner scanner;
    BluetoothGatt gatt;
    volatile boolean scanning = false;
    int scanRounds = 0;
    double lastCap = -1;
    long lastCapMs = 0;
    volatile double iA = 0;
    volatile boolean front = true;
    final Deque<BluetoothGattCharacteristic> queue = new ArrayDeque<BluetoothGattCharacteristic>();
    int rawV = 0, rawCap = 0;
    final List<Integer> cells = new ArrayList<Integer>();
    int e1 = -1, e2 = -1, protByte = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        wv.setBackgroundColor(0xFF05070A);
        wv.setWebViewClient(new WebViewClient());
        wv.addJavascriptInterface(new Bridge(), "xgbms");

        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File html = new File(dir, "index.html");
        copyAsset("index.html", html);
        wv.loadUrl("file://" + html.getAbsolutePath());
        setContentView(wv);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
        try {
            Intent si = new Intent(this, Keep.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si); else startService(si);
        } catch (Exception e) { }
        ui.postDelayed(new Runnable() { public void run() { startScan(); } }, 1500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        front = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        front = false;
    }

    public class Bridge {
        @JavascriptInterface
        public void rescan() {
            ui.post(new Runnable() { public void run() {
                if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception e) { } gatt = null; }
                scanning = false;
                startScan();
            }});
        }
    }

    void notifyData(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification n;
            if (Build.VERSION.SDK_INT >= 26) {
                n = new Notification.Builder(this, "xgbms")
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setShowWhen(false)
                        .build();
            } else {
                n = new Notification.Builder(this)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .build();
            }
            nm.notify(1, n);
        } catch (Exception e) { }
    }

    void push(String json) {
        try {
            File f = new File(getExternalFilesDir(null), "ble.log");
            if (f.length() > 300000) f.delete();
            FileOutputStream fo = new FileOutputStream(f, true);
            fo.write((System.currentTimeMillis() + " " + json + "\n").getBytes());
            fo.close();
        } catch (Exception e) { }
        final String js = "window.onBms && onBms(" + json + ");";
        ui.post(new Runnable() { public void run() { wv.evaluateJavascript(js, null); }});
    }

    void copyAsset(String name, File dest) {
        try {
            if (dest.exists() && dest.length() > 0) return;
            InputStream in = getAssets().open(name);
            FileOutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush(); out.close(); in.close();
        } catch (Exception e) { }
    }

    synchronized void startScan() {
        if (adapter == null || !adapter.isEnabled()) { push("{\"state\":\"no-bt\"}"); return; }
        if (gatt != null) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { push("{\"state\":\"no-scanner\"}"); return; }
        if (scanning) return;
        scanning = true;
        push("{\"state\":\"scanning\"}");
        try {
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        } catch (Exception e) { scanning = false; return; }
        ui.postDelayed(new Runnable() { public void run() { stopScan(); } }, 15000);
    }

    synchronized void stopScan() {
        if (!scanning || scanner == null) return;
        scanning = false;
        try { scanner.stopScan(scanCb); } catch (Exception e) { }
        if (gatt == null) {
            scanRounds++;
            logLine("ROUND " + scanRounds);
            if (scanRounds >= 3) {
                scanRounds = 0;
                try {
                    BluetoothDevice dd = adapter.getRemoteDevice(TARGET_MAC);
                    logLine("DIRECT " + dd.getAddress());
                    connect(dd);
                    return;
                } catch (Exception e) { logLine("DIRECTFAIL " + e); }
            }
            ui.postDelayed(new Runnable() { public void run() { startScan(); } }, 4000);
        }
    }

    final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { onFound(r); }
        @Override public void onBatchScanResults(List<ScanResult> rs) {
            for (ScanResult r : rs) onFound(r);
        }
        @Override public void onScanFailed(int e) { logLine("SCANFAIL " + e); scanning = false; }
    };

    void logLine(String s) {
        try {
            File f = new File(getExternalFilesDir(null), "ble.log");
            if (f.length() > 400000) f.delete();
            FileOutputStream fo = new FileOutputStream(f, true);
            fo.write((System.currentTimeMillis() + " " + s + "\n").getBytes());
            fo.close();
        } catch (Exception e) { }
    }

    void onFound(ScanResult r) {
        BluetoothDevice d = r.getDevice();
        String nm = null;
        try { nm = d.getName(); } catch (Exception e) { }
        String addr = d.getAddress();
        logLine("SEE " + addr + " | " + nm);
        boolean hit = TARGET_MAC.equalsIgnoreCase(addr)
                || (nm != null && nm.startsWith(NAME_PREFIX));
        if (!hit) return;
        logLine("HIT " + addr);
        stopScan();
        connect(d);
    }

    void connect(BluetoothDevice d) {
        push("{\"state\":\"connecting\"}");
        try {
            if (gatt != null) { gatt.close(); gatt = null; }
            gatt = d.connectGatt(this, false, gattCb);
        } catch (Exception e) { push("{\"state\":\"connect-fail\"}"); }
    }

    final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                push("{\"state\":\"connected\"}");
                g.discoverServices();
            } else {
                push("{\"state\":\"disconnected\"}");
                try { g.close(); } catch (Exception e) { }
                gatt = null;
                ui.postDelayed(new Runnable() { public void run() { startScan(); } }, 3000);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(SVC);
            if (svc == null) {
                List<BluetoothGattService> all = g.getServices();
                for (BluetoothGattService s2 : all) {
                    if (s2.getUuid().toString().startsWith("00008900")) { svc = s2; break; }
                }
            }
            if (svc == null) { push("{\"state\":\"no-service\"}"); return; }
            queue.clear();
            addQ(svc, C_SUM); addQ(svc, C_C1); addQ(svc, C_C2);
            addQ(svc, C_E1); addQ(svc, C_E2); addQ(svc, C_PRT);
            BluetoothGattCharacteristic cw = svc.getCharacteristic(C_WAKE);
            if (cw != null) {
                byte[] ff = new byte[17];
                for (int i = 0; i < 17; i++) ff[i] = (byte) 0xFF;
                cw.setValue(ff);
                cw.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                boolean w = false;
                try { w = g.writeCharacteristic(cw); } catch (Exception e) { }
                logLine("WAKE write=" + w);
            }
            ui.postDelayed(new Runnable() { public void run() { nextRead(); } }, 400);
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) handle(c);
            nextRead();
        }
    };

    void addQ(BluetoothGattService svc, UUID u) {
        BluetoothGattCharacteristic c = svc.getCharacteristic(u);
        if (c != null) queue.add(c);
    }

    void nextRead() {
        if (gatt == null) return;
        BluetoothGattCharacteristic c = queue.poll();
        if (c == null) {
            publish();
            long gap = front ? 1800L : 25000L;
            ui.postDelayed(new Runnable() { public void run() { refill(); } }, gap);
            return;
        }
        final BluetoothGattCharacteristic cc = c;
        ui.postDelayed(new Runnable() { public void run() {
            if (gatt == null) return;
            boolean ok = false;
            try { ok = gatt.readCharacteristic(cc); } catch (Exception e) { }
            if (!ok) ui.postDelayed(new Runnable() { public void run() { nextRead(); } }, 60);
        }}, 40);
    }

    void refill() {
        if (gatt == null) return;
        BluetoothGattService svc = gatt.getService(SVC);
        if (svc == null) return;
        queue.clear();
        addQ(svc, C_SUM); addQ(svc, C_C1); addQ(svc, C_C2);
        addQ(svc, C_E1); addQ(svc, C_E2); addQ(svc, C_PRT);
        nextRead();
    }

    void wake() {
        if (gatt == null) return;
        try {
            BluetoothGattService svc = gatt.getService(SVC);
            if (svc == null) return;
            BluetoothGattCharacteristic cw = svc.getCharacteristic(C_WAKE);
            if (cw == null) return;
            byte[] ff = new byte[17];
            for (int i = 0; i < 17; i++) ff[i] = (byte) 0xFF;
            cw.setValue(ff);
            cw.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            gatt.writeCharacteristic(cw);
        } catch (Exception e) { }
    }

    int u16be(byte[] b, int i) {
        if (b == null || b.length < i + 2) return -1;
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    void handle(BluetoothGattCharacteristic c) {
        byte[] b = c.getValue();
        if (b == null) return;
        String us = c.getUuid().toString();
        if (us.startsWith("00008902")) {
            rawV = u16be(b, 1);
            rawCap = u16be(b, 5);
        } else if (us.startsWith("00008903")) {
            cells.clear();
            for (int i = 0; i + 1 < b.length; i += 2) cells.add(u16be(b, i));
        } else if (us.startsWith("00008904")) {
            for (int i = 0; i + 1 < b.length; i += 2) cells.add(u16be(b, i));
        } else if (us.startsWith("00008905")) {
            e1 = b.length > 0 ? (b[0] & 0xFF) : -1;
        } else if (us.startsWith("00008906")) {
            e2 = b.length > 0 ? (b[0] & 0xFF) : -1;
        } else if (us.startsWith("00008907")) {
            protByte = b.length > 0 ? (b[0] & 0xFF) : -1;
        }
    }

    void publish() {
        if (rawV <= 0) return;
        int n = cells.size();
        int mn = 0, mx = 0;
        StringBuilder cs = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            int x = cells.get(i);
            if (i == 0) { mn = x; mx = x; }
            if (x < mn) mn = x;
            if (x > mx) mx = x;
            if (i > 0) cs.append(",");
            cs.append(x);
        }
        cs.append("]");
        double v = rawV / 100.0;
        double cap = rawCap / 1000.0;

        long now = System.currentTimeMillis();
        if (lastCap < 0) {
            lastCap = cap; lastCapMs = now; iA = 0;
        } else if (Math.abs(cap - lastCap) > 0.0005) {
            double dt = (now - lastCapMs) / 1000.0;
            if (dt >= 12.0) {
                iA = (cap - lastCap) / dt * 3600.0;
                lastCap = cap; lastCapMs = now;
            } else {
                lastCap = cap; lastCapMs = now;
            }
        } else if (now - lastCapMs > 240000) {
            iA = 0;
        }
        String mode = iA > 0.4 ? "chg" : (iA < -0.4 ? "dis" : "idle");
        int eta = 0;
        if ("chg".equals(mode) && iA > 0.1) eta = (int) Math.round((25.6 - cap) / iA * 60.0);
        else if ("dis".equals(mode) && iA < -0.1) eta = (int) Math.round(cap / (-iA) * 60.0);
        if (eta < 0) eta = 0;
        int soc = (int) Math.round(cap / 25.6 * 100);
        if (soc > 100) soc = 100;
        if (soc < 0) soc = 0;
        String p = (protByte == 0xFF || protByte < 0) ? "无告警" : ("告警 0x" + Integer.toHexString(protByte));
        String mtxt = "chg".equals(mode) ? "充电中" : ("dis".equals(mode) ? "放电中" : "静置");
        notifyData("XGBMS  " + String.format("%.2f", v) + " V  ·  " + mtxt,
                soc + "%  ·  " + String.format("%.2f", cap) + " Ah  ·  "
                + String.format("%.1f", Math.abs(iA)) + " A"
                + ("idle".equals(mode) ? "" : ("  ·  " + eta + " 分钟")));
        push("{\"state\":\"ok\",\"v\":" + String.format("%.2f", v)
                + ",\"i\":" + String.format("%.2f", iA)
                + ",\"mode\":\"" + mode + "\",\"eta\":" + eta
                + ",\"soc\":" + soc
                + ",\"cap\":" + String.format("%.3f", cap)
                + ",\"diff\":" + (n > 0 ? (mx - mn) : 0)
                + ",\"cells\":" + cs
                + ",\"tmos\":" + (e1 < 0 ? "null" : String.valueOf(e1))
                + ",\"tin\":" + (e2 < 0 ? "null" : String.valueOf(e2))
                + ",\"cyc\":null,\"prot\":\"" + p + "\"}");
    }
}
