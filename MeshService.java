package com.exemple.messagerie;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Service au premier plan : garde le réseau d'appareil à appareil actif écran éteint,
 * retransmet les paquets chiffrés (stockage 24 h) et met en file les paquets destinés à l'app.
 */
public class MeshService extends Service {
    public interface Sink { void onPacket(String raw); void onPeers(int n); void onSent(String id); }

    static volatile MeshService instance;
    static volatile Sink sink;
    static volatile boolean visible = false;

    private static final String SERVICE_ID = "dig.dag.mesh";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long DAY = 24L * 3600L * 1000L;

    private final Set<String> peers = ConcurrentHashMap.newKeySet();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final SecureRandom rnd = new SecureRandom();
    private JSONArray relay = new JSONArray();   // [{p:{paquet}, exp:ms, sent:bool}]
    private JSONArray inbox = new JSONArray();   // paquets pour l'app, en attente de lecture
    private String myId = "", myName = "", myPub = "";
    private boolean started = false;
    private SharedPreferences prefs;
    private ConnectionsClient client;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("mesh", MODE_PRIVATE);
        try { relay = new JSONArray(prefs.getString("relay", "[]")); } catch (Exception e) { relay = new JSONArray(); }
        try { inbox = new JSONArray(prefs.getString("inbox", "[]")); } catch (Exception e) { inbox = new JSONArray(); }
        for (int i = 0; i < relay.length(); i++) {
            JSONObject r = relay.optJSONObject(i);
            if (r != null && r.optJSONObject("p") != null) seen.add(r.optJSONObject("p").optString("id"));
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("mesh", "Réseau actif", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel("msg", "Messages", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("id")) {
            myId = String.valueOf(intent.getStringExtra("id"));
            String nm = intent.getStringExtra("name"), pb = intent.getStringExtra("pub");
            myName = nm == null ? "" : (nm.length() > 30 ? nm.substring(0, 30) : nm);
            myPub = pb == null ? "" : pb;
            prefs.edit().putString("id", myId).putString("name", myName).putString("pub", myPub).apply();
        } else {
            myId = prefs.getString("id", "");
            myName = prefs.getString("name", "");
            myPub = prefs.getString("pub", "");
        }
        if (myId.length() != 10) { stopSelf(); return START_NOT_STICKY; }
        try {
            Notification n = notif(statusText());
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(1, n);
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        if (!started) { started = true; startNearby(); }
        return START_STICKY;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        instance = null;
        if (client != null) { client.stopAllEndpoints(); client.stopAdvertising(); client.stopDiscovery(); }
        super.onDestroy();
    }

    /* ---------- notifications ---------- */
    private String statusText() {
        int n = peers.size();
        return n == 0 ? "Aucun appareil proche" : n + " appareil" + (n > 1 ? "s" : "") + " proche" + (n > 1 ? "s" : "");
    }

    private PendingIntent openApp() {
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notif(String text) {
        return new NotificationCompat.Builder(this, "mesh")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Messagerie active")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .build();
    }

    private void refreshNotif() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notif(statusText()));
    }

    private void notifyMessage() {
        Notification n = new NotificationCompat.Builder(this, "msg")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Nouveau message")
            .setContentText("Ouvre l'application pour le lire.")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(2, n);
    }

    /* ---------- réseau ---------- */
    private void startNearby() {
        client = Nearby.getConnectionsClient(this);
        client.startAdvertising(myId, SERVICE_ID, lifecycle,
            new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener(e -> {});
        client.startDiscovery(SERVICE_ID, discovery,
            new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener(e -> {});
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            // un seul des deux appareils lance la connexion (évite les doublons)
            if (myId.compareTo(info.getEndpointName()) < 0 && !peers.contains(endpointId)) {
                client.requestConnection(myId, endpointId, lifecycle);
            }
        }
        @Override public void onEndpointLost(@NonNull String endpointId) {}
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            client.acceptConnection(endpointId, payloads);
        }
        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) onPeerUp(endpointId);
        }
        @Override
        public void onDisconnected(@NonNull String endpointId) {
            peers.remove(endpointId);
            refreshNotif();
            Sink s = sink;
            if (s != null) s.onPeers(peers.size());
        }
    };

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            byte[] b = payload.asBytes();
            if (b != null) handle(new String(b, StandardCharsets.UTF_8));
        }
        @Override public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate u) {}
    };

    private void sendTo(String endpoint, String raw) {
        client.sendPayload(endpoint, Payload.fromBytes(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private void broadcast(String raw) {
        if (!peers.isEmpty()) client.sendPayload(new ArrayList<>(peers), Payload.fromBytes(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private void onPeerUp(String endpoint) {
        peers.add(endpoint);
        refreshNotif();
        Sink s = sink;
        if (s != null) s.onPeers(peers.size());
        sendTo(endpoint, hello());
        long now = System.currentTimeMillis();
        List<String> newlySent = new ArrayList<>();
        synchronized (this) {
            for (int i = 0; i < relay.length(); i++) {
                JSONObject r = relay.optJSONObject(i);
                if (r == null || r.optLong("exp") <= now) continue;
                JSONObject p = r.optJSONObject("p");
                if (p == null) continue;
                sendTo(endpoint, p.toString());
                if (!r.optBoolean("sent") && p.optString("from").equals(myId)) {
                    try { r.put("sent", true); } catch (Exception e) { /* ignoré */ }
                    newlySent.add(p.optString("id"));
                }
            }
            if (!newlySent.isEmpty()) prefs.edit().putString("relay", relay.toString()).apply();
        }
        if (s != null) for (String id : newlySent) s.onSent(id);
    }

    private String hello() {
        try {
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 12; i++) id.append(ALPHABET.charAt(rnd.nextInt(32)));
            JSONObject h = new JSONObject();
            h.put("t", "h").put("id", id.toString()).put("from", myId).put("name", myName)
             .put("pub", myPub).put("ts", System.currentTimeMillis()).put("ttl", 8);
            return h.toString();
        } catch (Exception e) { return "{}"; }
    }

    /* ---------- paquets ---------- */
    private static boolean len(String s, int min, int max) { return s != null && s.length() >= min && s.length() <= max; }

    /** Valide et reconstruit un paquet avec les seuls champs attendus (null si invalide). */
    static JSONObject clean(String raw) {
        try {
            if (raw == null || raw.length() > 2600) return null;
            JSONObject p = new JSONObject(raw);
            String t = p.optString("t"), id = p.optString("id"), from = p.optString("from");
            int ttl = p.optInt("ttl", -1);
            long ts = p.optLong("ts", -1);
            if (!(t.equals("h") || t.equals("m")) || !len(id, 8, 16) || !len(from, 10, 10) || ttl < 0 || ttl > 8 || ts < 0) return null;
            JSONObject c = new JSONObject();
            c.put("t", t).put("id", id).put("from", from).put("ts", ts).put("ttl", ttl);
            if (t.equals("h")) {
                String name = p.optString("name"), pub = p.optString("pub");
                if (!len(name, 0, 30) || !len(pub, 88, 88)) return null;
                c.put("name", name).put("pub", pub);
            } else {
                String to = p.optString("to"), iv = p.optString("iv"), ct = p.optString("ct");
                if (!len(to, 10, 10) || !len(iv, 1, 16) || !len(ct, 1, 1700)) return null;
                c.put("to", to).put("iv", iv).put("ct", ct);
            }
            return c;
        } catch (Exception e) { return null; }
    }

    private synchronized boolean markSeen(String id) {
        if (seen.contains(id)) return false;
        seen.add(id);
        if (seen.size() > 1500) { Iterator<String> it = seen.iterator(); it.next(); it.remove(); }
        return true;
    }

    private synchronized void keep(JSONObject c, boolean sent) {
        long now = System.currentTimeMillis();
        String id = c.optString("id");
        try {
            JSONArray n = new JSONArray();
            for (int i = 0; i < relay.length(); i++) {
                JSONObject r = relay.getJSONObject(i);
                if (r.optLong("exp") > now && !r.getJSONObject("p").optString("id").equals(id)) n.put(r);
            }
            JSONObject e = new JSONObject();
            e.put("p", c).put("exp", now + DAY).put("sent", sent);
            n.put(e);
            if (n.length() > 100) {
                JSONArray m = new JSONArray();
                for (int i = n.length() - 100; i < n.length(); i++) m.put(n.get(i));
                n = m;
            }
            relay = n;
            prefs.edit().putString("relay", relay.toString()).apply();
        } catch (Exception ex) { /* ignoré */ }
    }

    private synchronized void queueInbox(String raw) {
        try {
            inbox.put(raw);
            if (inbox.length() > 200) {
                JSONArray m = new JSONArray();
                for (int i = inbox.length() - 200; i < inbox.length(); i++) m.put(inbox.get(i));
                inbox = m;
            }
            prefs.edit().putString("inbox", inbox.toString()).apply();
        } catch (Exception e) { /* ignoré */ }
    }

    /** Paquet reçu d'un autre appareil. Le service ne lit jamais le contenu des messages. */
    private void handle(String raw) {
        JSONObject c = clean(raw);
        if (c == null) return;
        if (!markSeen(c.optString("id"))) return;
        if (c.optString("from").equals(myId)) return;
        boolean isMsg = c.optString("t").equals("m");
        boolean forMe = isMsg && c.optString("to").equals(myId);
        if (!isMsg || forMe) {
            Sink s = sink;
            if (s != null && visible) s.onPacket(c.toString()); else queueInbox(c.toString());
            if (forMe && !visible) notifyMessage();
        }
        int ttl = c.optInt("ttl");
        if (!forMe && ttl > 0) { // relais : paquet toujours chiffré, aucune clé nécessaire
            try { c.put("ttl", ttl - 1); } catch (Exception e) { return; }
            keep(c, false);
            broadcast(c.toString());
        }
    }

    /* ---------- appelé par le plugin ---------- */
    /** Retourne le nombre d'appareils auxquels le paquet a été envoyé, ou -1 si invalide. */
    int sendOwn(String raw) {
        JSONObject c = clean(raw);
        if (c == null) return -1;
        markSeen(c.optString("id"));
        int n = peers.size();
        keep(c, n > 0);
        if (n > 0) broadcast(c.toString());
        return n;
    }

    int peerCount() { return peers.size(); }

    synchronized List<String> drainInbox() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < inbox.length(); i++) out.add(inbox.optString(i));
        inbox = new JSONArray();
        prefs.edit().putString("inbox", "[]").apply();
        return out;
    }

    synchronized List<String> sentIds() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < relay.length(); i++) {
            JSONObject r = relay.optJSONObject(i);
            if (r == null || !r.optBoolean("sent")) continue;
            JSONObject p = r.optJSONObject("p");
            if (p != null && p.optString("from").equals(myId)) out.add(p.optString("id"));
        }
        return out;
    }
}
