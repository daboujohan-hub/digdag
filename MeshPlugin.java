package com.exemple.messagerie;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
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
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
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
