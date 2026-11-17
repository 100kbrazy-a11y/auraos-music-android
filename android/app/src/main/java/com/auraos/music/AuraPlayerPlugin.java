package com.auraos.music;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** The app's bridge to the native player (AuraPlaybackService) and to the phone's battery state. */
@OptIn(markerClass = UnstableApi.class)
@CapacitorPlugin(name = "AuraPlayer")
public class AuraPlayerPlugin extends Plugin {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ListenableFuture<MediaController> controller;
    private final List<PluginCall> waiting = new ArrayList<>();
    private BroadcastReceiver powerRx;
    private String lastPower = "";

    @Override
    public void load() {
        AuraPlaybackService.events = (name, data) -> notifyListeners(name, data);
        watchPower();
    }

    @Override
    protected void handleOnDestroy() {
        AuraPlaybackService.events = null;
        if (controller != null) MediaController.releaseFuture(controller);
        controller = null;
        if (powerRx != null) { try { getContext().unregisterReceiver(powerRx); } catch (Exception ignored) {} }
    }

    private interface Job { void run(AuraPlaybackService s, PluginCall call) throws Exception; }

    /** runs on the main thread once the service is up */
    private void withService(PluginCall call, Job job) {
        main.post(() -> {
            AuraPlaybackService s = AuraPlaybackService.instance;
            if (s == null) { call.reject("The player isn't started"); return; }
            try { job.run(s, call); } catch (Exception e) { call.reject(e.getMessage() != null ? e.getMessage() : e.toString()); }
        });
    }

    @PluginMethod
    public void start(PluginCall call) {
        main.post(() -> {
            if (AuraPlaybackService.instance != null && controller != null && controller.isDone()) { call.resolve(AuraPlaybackService.instance.state()); return; }
            waiting.add(call);
            if (controller != null) return;
            try {
                SessionToken token = new SessionToken(getContext(), new ComponentName(getContext(), AuraPlaybackService.class));
                controller = new MediaController.Builder(getContext(), token).buildAsync();
                controller.addListener(() -> {
                    List<PluginCall> calls = new ArrayList<>(waiting);
                    waiting.clear();
                    try {
                        controller.get();
                        AuraPlaybackService s = AuraPlaybackService.instance;
                        for (PluginCall c : calls) { if (s != null) c.resolve(s.state()); else c.reject("The player didn't start"); }
                    } catch (Exception e) {
                        controller = null;
                        for (PluginCall c : calls) c.reject("The player didn't start: " + e.getMessage());
                    }
                }, ContextCompat.getMainExecutor(getContext()));
            } catch (Exception e) {
                controller = null;
                List<PluginCall> calls = new ArrayList<>(waiting);
                waiting.clear();
                for (PluginCall c : calls) c.reject("The player didn't start: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void setQueue(PluginCall call) {
        withService(call, (s, c) -> {
            JSArray arr = c.getArray("items", new JSArray());
            List<MediaItem> list = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(AuraPlaybackService.item(getContext(), JSObject.fromJSONObject(o)));
            }
            s.setQueue(list, c.getInt("index", 0), c.getDouble("position", 0.0).longValue(), c.getBoolean("play", false), c.getBoolean("keep", false));
            c.resolve(s.state());
        });
    }

    @PluginMethod
    public void skipTo(PluginCall call) {
        withService(call, (s, c) -> { s.skipTo(c.getInt("index", 0), c.getDouble("position", 0.0).longValue(), c.getBoolean("play", true)); c.resolve(s.state()); });
    }

    @PluginMethod public void play(PluginCall call) { withService(call, (s, c) -> { s.play(); c.resolve(); }); }
    @PluginMethod public void pause(PluginCall call) { withService(call, (s, c) -> { s.pause(); c.resolve(); }); }
    @PluginMethod public void seek(PluginCall call) { withService(call, (s, c) -> { s.seek(c.getDouble("ms", 0.0).longValue()); c.resolve(); }); }
    @PluginMethod public void state(PluginCall call) { withService(call, (s, c) -> c.resolve(s.state())); }

    @PluginMethod
    public void setRepeat(PluginCall call) {
        withService(call, (s, c) -> {
            String m = c.getString("mode", "off");
            s.setRepeat("one".equals(m) ? Player.REPEAT_MODE_ONE : "all".equals(m) ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
            c.resolve();
        });
    }

    @PluginMethod
    public void setCrossfade(PluginCall call) {
        withService(call, (s, c) -> { s.crossfade = c.getFloat("secs", 0f); c.resolve(); });
    }

    @PluginMethod
    public void setEq(PluginCall call) {
        JSArray g = call.getArray("gains", new JSArray());
        float[] db = new float[10];
        try { for (int i = 0; i < 10 && i < g.length(); i++) db[i] = (float) g.getDouble(i); } catch (Exception ignored) {}
        AuraAudioProcessor.setEq(db, call.getFloat("pre", 1f));
        call.resolve();
    }

    @PluginMethod
    public void setViz(PluginCall call) { AuraAudioProcessor.viz = call.getBoolean("on", false); call.resolve(); }

    /** the visualizer's spectrum right now: 512 bytes, base64 (called up to ~30 times a second while it's on screen) */
    @PluginMethod
    public void levels(PluginCall call) {
        AuraPlaybackService s = AuraPlaybackService.instance;
        JSObject o = new JSObject();
        o.put("data", s != null ? Base64.encodeToString(s.levels(), Base64.NO_WRAP) : "");
        call.resolve(o);
    }

    @PluginMethod
    public void setArtwork(PluginCall call) {
        withService(call, (s, c) -> {
            String b64 = c.getString("data", "");
            s.setArtwork(c.getString("id", ""), b64 == null || b64.isEmpty() ? null : Base64.decode(b64, Base64.DEFAULT));
            c.resolve();
        });
    }

    // ------------------------------------------------------------------ compressing songs (Opus 160 kbps)
    private final java.util.concurrent.ExecutorService work = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** from: the song's file (relative to the app's files folder); to: the output without extension. */
    @PluginMethod
    public void compress(PluginCall call) {
        final String from = call.getString("from", ""), to = call.getString("to", "");
        final int kbps = call.getInt("kbps", 160);
        work.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            long t0 = android.os.SystemClock.elapsedRealtime();
            try {
                java.io.File dir = getContext().getFilesDir();
                java.io.File in = new java.io.File(dir, from);
                if (!in.exists()) { call.reject("the song's file isn't there"); return; }
                java.io.File outBase = new java.io.File(dir, to);
                java.io.File parent = outBase.getParentFile();
                if (parent != null) parent.mkdirs();
                AuraCompressor.Result r = AuraCompressor.run(in, outBase.getPath(), kbps);
                JSObject o = new JSObject();
                o.put("file", r.file);
                o.put("size", r.size);
                o.put("before", in.length());
                o.put("codec", r.codec);
                o.put("type", r.mime);
                o.put("ms", android.os.SystemClock.elapsedRealtime() - t0);
                call.resolve(o);
            } catch (Throwable e) {
                call.reject(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    // ------------------------------------------------------------------ battery (for the automatic power saver)
    @PluginMethod
    public void power(PluginCall call) { call.resolve(readPower()); }

    private JSObject readPower() {
        JSObject o = new JSObject();
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        o.put("saver", pm != null && pm.isPowerSaveMode());
        Intent b = ContextCompat.registerReceiver(getContext(), null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        int level = -1, scale = 100, status = -1;
        if (b != null) {
            level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        }
        o.put("level", level >= 0 && scale > 0 ? level / (double) scale : 1.0);
        o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
        return o;
    }

    private void watchPower() {
        powerRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                JSObject o = readPower();
                // battery % changes often: only tell the app when something it cares about changed
                double lv = o.optDouble("level", 1);
                String key = o.optBoolean("saver") + "|" + o.optBoolean("charging") + "|" + (lv <= 0.15 ? "low" : lv <= 0.2 ? "mid" : "ok");
                if (key.equals(lastPower)) return;
                lastPower = key;
                notifyListeners("power", o);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        ContextCompat.registerReceiver(getContext(), powerRx, f, ContextCompat.RECEIVER_NOT_EXPORTED);
    }
}
