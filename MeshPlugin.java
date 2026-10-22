package com.exemple.messagerie;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.google.android.gms.common.GoogleApiAvailability;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Pont entre l'interface HTML et le service MeshService. */
@CapacitorPlugin(name = "Mesh")
public class MeshPlugin extends Plugin implements MeshService.Sink {

    @Override
    public void load() {
        MeshService.sink = this;
        MeshService.visible = true;
    }

    @Override protected void handleOnResume() { MeshService.visible = true; }
    @Override protected void handleOnPause() { MeshService.visible = false; }
    @Override
    protected void handleOnDestroy() {
        if (MeshService.sink == this) MeshService.sink = null;
        MeshService.visible = false;
    }

    private List<String> required() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
            p.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT <= 31) p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        return p;
    }

    private boolean granted(String p) {
        return ContextCompat.checkSelfPermission(getContext(), p) == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void start(PluginCall call) {
        String id = call.getString("id");
        if (id == null) { call.reject("id manquant"); return; }
        List<String> missing = new ArrayList<>();
        for (String p : required()) if (!granted(p)) missing.add(p);
        JSObject r = new JSObject();
        if (!missing.isEmpty()) {
            if (Boolean.TRUE.equals(call.getBoolean("ask", false))) {
                List<String> ask = new ArrayList<>(missing);
                // les notifications (Android 13+) servent à afficher « Messagerie active » et « Nouveau message »
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) ask.add(Manifest.permission.POST_NOTIFICATIONS);
                ActivityCompat.requestPermissions(getActivity(), ask.toArray(new String[0]), 42);
            }
            r.put("ok", false);
            call.resolve(r);
            return;
        }
        Intent i = new Intent(getContext(), MeshService.class);
        i.putExtra("id", id);
        i.putExtra("name", call.getString("name", ""));
        i.putExtra("pub", call.getString("pub", ""));
        ContextCompat.startForegroundService(getContext(), i);
        r.put("ok", true);
        call.resolve(r);
    }

    @PluginMethod
    public void send(PluginCall call) {
        String data = call.getString("data");
        MeshService s = MeshService.instance;
        if (data == null) { call.reject("data manquant"); return; }
        if (s == null) { call.reject("service pas encore prêt"); return; }
        int n = s.sendOwn(data);
        if (n < 0) { call.reject("paquet invalide"); return; }
        JSObject r = new JSObject();
        r.put("sent", n);
        call.resolve(r);
    }

    @PluginMethod
    public void drain(PluginCall call) {
        MeshService s = MeshService.instance;
        JSArray packets = new JSArray();
        JSArray sent = new JSArray();
        JSObject r = new JSObject();
        if (s != null) {
            for (String x : s.drainInbox()) packets.put(x);
            for (String x : s.sentIds()) sent.put(x);
            r.put("n", s.peerCount());
        } else {
            r.put("n", 0);
        }
        r.put("packets", packets);
        r.put("sent", sent);
        call.resolve(r);
    }

    @PluginMethod
    public void keepAlive(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        }
        call.resolve();
    }

    @PluginMethod
    public void diag(PluginCall call) {
        JSObject r = new JSObject();
        JSArray missing = new JSArray();
        for (String p : required()) if (!granted(p)) missing.put(p.substring(p.lastIndexOf('.') + 1));
        r.put("missing", missing);
        r.put("sdk", Build.VERSION.SDK_INT);
        Context c = getContext().getApplicationContext();
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            r.put("bt", bt != null && bt.isEnabled());
        } catch (Exception e) { r.put("bt", false); }
        try {
            WifiManager w = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
            r.put("wifi", w != null && w.isWifiEnabled());
        } catch (Exception e) { r.put("wifi", false); }
        try {
            LocationManager l = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            r.put("loc", Build.VERSION.SDK_INT < 28 || (l != null && l.isLocationEnabled()));
        } catch (Exception e) { r.put("loc", false); }
        try { r.put("gms", GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c)); }
        catch (Exception e) { r.put("gms", -1); }
        MeshService s = MeshService.instance;
        r.put("service", s != null);
        if (s != null) {
            r.put("adv", s.advState);
            r.put("disc", s.discState);
            r.put("found", s.found);
            r.put("peers", s.peerCount());
            r.put("err", s.lastError);
            r.put("relayed", s.relayed);
            r.put("met", s.met.size());
        }
        call.resolve(r);
    }

    /** Envoie l'APK de l'app installée (Bluetooth, Partage à proximité, WhatsApp...) : marche sans Internet. */
    @PluginMethod
    public void shareApp(PluginCall call) {
        try {
            Context c = getContext();
            File src = new File(c.getApplicationInfo().sourceDir);
            File dir = new File(c.getCacheDir(), "share");
            dir.mkdirs();
            File out = new File(dir, "Messagerie.apk");
            try (InputStream in = new FileInputStream(src); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", out);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/vnd.android.package-archive");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "Envoyer l'application");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(chooser);
            call.resolve();
        } catch (Exception e) {
            call.reject("partage impossible : " + e.getMessage());
        }
    }

    /** Test interne : le service accepte-t-il ce paquet ? */
    @PluginMethod
    public void check(PluginCall call) {
        JSObject r = new JSObject();
        r.put("valid", MeshService.clean(call.getString("data")) != null);
        call.resolve(r);
    }

    @Override
    public void onPacket(String raw) {
        JSObject o = new JSObject();
        o.put("data", raw);
        notifyListeners("packet", o);
    }

    @Override
    public void onPeers(int n) {
        JSObject o = new JSObject();
        o.put("n", n);
        notifyListeners("peer", o);
    }

    @Override
    public void onSent(String id) {
        JSObject o = new JSObject();
        o.put("id", id);
        notifyListeners("sent", o);
    }
}
