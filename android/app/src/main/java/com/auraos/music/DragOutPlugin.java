package com.auraos.music;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Drag songs, albums and playlists out of AuraOS Music into another app (Files, Messages, a chat…).
 * A song hands over its real audio file. An album or playlist becomes a music pack, made only when it's dropped:
 * a small window over the other app asks what to call it and shows the progress. That window is the only thing
 * the app ever draws over other apps, and it needs the "display over other apps" permission (asked for first).
 */
@CapacitorPlugin(name = "DragOut")
public class DragOutPlugin extends Plugin {
    static DragOutPlugin instance;
    static final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** one dragged thing */
    static class Entry {
        String token, kind, name, mime, title, label;
        long size = -1;
        volatile File file;
        volatile String error;
        volatile boolean begun = false;
        final CountDownLatch ready = new CountDownLatch(1);
        long created = System.currentTimeMillis();
    }

    @Override
    public void load() {
        instance = this;
        // tidy away anything left from earlier drags
        try {
            File dir = new File(getContext().getCacheDir(), "dragout");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) deleteAll(f);
        } catch (Exception ignored) {}
    }

    private static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---------------------------------------------------------------- permission for the little window
    @PluginMethod
    public void canOverlay(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void askOverlay(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Couldn't open the setting: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- the drag itself
    @PluginMethod
    public void start(PluginCall call) {
        final Entry e = new Entry();
        e.token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        e.kind = call.getString("kind", "song");
        e.name = call.getString("name", "music");
        e.mime = call.getString("mime", "application/octet-stream");
        e.title = call.getString("title", "");
        e.label = call.getString("label", e.name);
        Long size = call.getLong("size");
        if (size != null && size > 0) e.size = size;
        final String sub = call.getString("sub", "");
        final String art = call.getString("art", null);
        entries.put(e.token, e);
        main.post(() -> {
            if (Build.VERSION.SDK_INT < 24) { entries.remove(e.token); call.reject("Dragging to other apps needs Android 7 or newer"); return; }
            startDrag(call, e, sub, art);
        });
    }

    @android.annotation.TargetApi(24)
    private void startDrag(PluginCall call, Entry e, String sub, String art) {
        {
            try {
                Uri uri = new Uri.Builder().scheme("content").authority(getContext().getPackageName() + ".dragout")
                    .appendPath(e.token).appendPath(e.name).build();
                ClipData clip = new ClipData(new ClipDescription(e.label, new String[] { e.mime }), new ClipData.Item(uri));
                View web = getBridge().getWebView();
                Bitmap cover = null;
                if (art != null && !art.isEmpty()) {
                    try { byte[] b = Base64.decode(art, Base64.DEFAULT); cover = BitmapFactory.decodeByteArray(b, 0, b.length); } catch (Exception ignored) {}
                }
                boolean ok = web.startDragAndDrop(clip, new Shadow(web, e.label, sub, cover), null,
                    View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ);
                JSObject r = new JSObject();
                r.put("token", e.token);
                r.put("started", ok);
                if (!ok) entries.remove(e.token);
                call.resolve(r);
            } catch (Exception ex) {
                entries.remove(e.token);
                call.reject("Couldn't start the drag: " + ex.getMessage());
            }
        }
    }

    /** the file is written (song: right away; pack: once it's made) */
    @PluginMethod
    public void ready(PluginCall call) {
        Entry e = entries.get(call.getString("token", ""));
        String path = call.getString("path", "");
        if (e != null && path != null) {
            if (path.startsWith("file://")) path = Uri.parse(path).getPath();
            e.file = new File(path);
            e.ready.countDown();
            if ("pack".equals(e.kind)) main.post(() -> overlayDone(e));
        }
        call.resolve();
    }

    @PluginMethod
    public void progress(PluginCall call) {
        Double f = call.getDouble("f");
        String text = call.getString("text", "");
        main.post(() -> overlayProgress(f == null ? -1 : f, text));
        call.resolve();
    }

    @PluginMethod
    public void fail(PluginCall call) {
        Entry e = entries.get(call.getString("token", ""));
        if (e != null) {
            e.error = call.getString("error", "failed");
            e.ready.countDown();
            if ("pack".equals(e.kind)) main.post(() -> overlayError(e.error));
        }
        call.resolve();
    }

    /** called by DragOutProvider the first time the other app touches what was dropped */
    void begin(Entry e) {
        synchronized (e) {
            if (e.begun) return;
            e.begun = true;
        }
        if (!"pack".equals(e.kind)) {               // songs: the app is already writing the file
            JSObject d = new JSObject();
            d.put("token", e.token);
            notifyListeners("dropped", d);
            return;
        }
        main.post(() -> {
            boolean can = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext());
            if (can) askName(e); else requestPack(e, e.title);
        });
    }

    private void requestPack(Entry e, String title) {
        JSObject d = new JSObject();
        d.put("token", e.token);
        d.put("title", title);
        notifyListeners("packRequest", d, true);
    }

    // ---------------------------------------------------------------- the window over the other app
    private LinearLayout box;
    private WindowManager.LayoutParams lp;
    private TextView status;
    private ProgressBar bar;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getContext().getResources().getDisplayMetrics());
    }

    @android.annotation.TargetApi(23)
    private void askName(Entry e) {
        closeOverlay();
        Context c = getContext();
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F21A1820"));
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        box.setBackground(bg);
        box.setElevation(dp(16));

        TextView t = new TextView(c);
        t.setText("Name your music pack");
        t.setTextColor(Color.WHITE);
        t.setTextSize(19);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        TextView s = new TextView(c);
        s.setText(e.label + " · AuraOS Music");
        s.setTextColor(Color.parseColor("#B3FFFFFF"));
        s.setTextSize(13);
        s.setPadding(0, dp(4), 0, dp(12));
        box.addView(s);

        final EditText name = new EditText(c);
        name.setText(e.title);
        name.setSelectAllOnFocus(true);
        name.setSingleLine(true);
        name.setTextColor(Color.WHITE);
        name.setHintTextColor(Color.parseColor("#80FFFFFF"));
        name.setHint("Pack name");
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(Color.parseColor("#22FFFFFF"));
        fb.setCornerRadius(dp(14));
        name.setBackground(fb);
        name.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(c);
        status.setTextColor(Color.parseColor("#CCFFFFFF"));
        status.setTextSize(13);
        status.setVisibility(View.GONE);
        status.setPadding(0, dp(12), 0, dp(6));
        box.addView(status);
        bar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)));

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.END);
        row.setPadding(0, dp(14), 0, 0);
        Button cancel = button(c, "Cancel", false);
        Button save = button(c, "Make Pack", true);
        row.addView(cancel);
        row.addView(save);
        box.addView(row);

        final Runnable go = () -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) n = e.title;
            hideKeyboard(name);
            name.setEnabled(false);
            row.setVisibility(View.GONE);
            status.setVisibility(View.VISIBLE);
            bar.setVisibility(View.VISIBLE);
            status.setText("Making “" + n + "”…");
            bar.setIndeterminate(true);
            // no keyboard any more: let touches pass to the app underneath except on the card itself
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            try { ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(box, lp); } catch (Exception ignored) {}
            requestPack(e, n);
        };
        save.setOnClickListener(v -> go.run());
        name.setOnEditorActionListener((v, id, ev) -> { go.run(); return true; });
        cancel.setOnClickListener(v -> {
            hideKeyboard(name);
            e.error = "cancelled";
            e.ready.countDown();
            closeOverlay();
            JSObject d = new JSObject();
            d.put("token", e.token);
            notifyListeners("packCancel", d);
        });

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        Point size = new Point();
        wm.getDefaultDisplay().getSize(size);
        lp = new WindowManager.LayoutParams(Math.min(size.x - dp(32), dp(420)), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        lp.dimAmount = 0.35f;
        lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        try {
            wm.addView(box, lp);
            box.setAlpha(0f);
            box.setScaleX(0.92f);
            box.setScaleY(0.92f);
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
            name.requestFocus();
            main.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT);
            }, 250);
        } catch (Exception ex) {
            box = null;
            requestPack(e, e.title);                // couldn't show it: just make the pack with its own name
        }
    }

    private Button button(Context c, String text, boolean primary) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(999));
        g.setColor(primary ? Color.parseColor("#FF4F9A") : Color.parseColor("#22FFFFFF"));
        b.setBackground(g);
        b.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        p.leftMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void overlayProgress(double f, String text) {
        if (box == null || bar == null) return;
        if (f >= 0) { bar.setIndeterminate(false); bar.setProgress((int) Math.round(f * 1000)); }
        if (text != null && !text.isEmpty()) status.setText(text);
    }

    private void overlayDone(Entry e) {
        if (box == null) return;
        bar.setIndeterminate(false);
        bar.setProgress(1000);
        status.setText("Done — “" + e.name + "”");
        main.postDelayed(this::closeOverlay, 1300);
    }

    private void overlayError(String err) {
        if (box == null) return;
        bar.setVisibility(View.GONE);
        status.setText("Couldn't make the pack: " + err);
        main.postDelayed(this::closeOverlay, 3500);
    }

    private void closeOverlay() {
        if (box == null) return;
        final View v = box;
        box = null;
        try {
            v.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(160).withEndAction(() -> {
                try { ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(v); } catch (Exception ignored) {}
            }).start();
        } catch (Exception ex) {
            try { ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(v); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------- what you see under your finger while dragging
    static class Shadow extends View.DragShadowBuilder {
        final String title, sub;
        final Bitmap cover;
        final float d;
        final int w, h;

        Shadow(View v, String title, String sub, Bitmap cover) {
            super(v);
            this.title = title == null ? "" : title;
            this.sub = sub == null ? "" : sub;
            this.cover = cover;
            d = v.getResources().getDisplayMetrics().density;
            w = (int) (240 * d);
            h = (int) (68 * d);
        }

        @Override
        public void onProvideShadowMetrics(Point size, Point touch) {
            size.set(w, h);
            touch.set((int) (34 * d), h / 2);
        }

        @Override
        public void onDrawShadow(Canvas c) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.parseColor("#F01E1B26"));
            c.drawRoundRect(new RectF(0, 0, w, h), 18 * d, 18 * d, p);
            float a = 52 * d, ax = 8 * d, ay = (h - a) / 2f;
            RectF art = new RectF(ax, ay, ax + a, ay + a);
            if (cover != null) {
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(art, 10 * d, 10 * d, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                c.drawBitmap(cover, null, art, new Paint(Paint.FILTER_BITMAP_FLAG));
                c.restore();
            } else {
                p.setColor(Color.parseColor("#FF4F9A"));
                c.drawRoundRect(art, 10 * d, 10 * d, p);
            }
            float tx = ax + a + 12 * d, max = w - tx - 10 * d;
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(Color.WHITE);
            tp.setTextSize(15 * d);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(TextUtils.ellipsize(title, new android.text.TextPaint(tp), max, TextUtils.TruncateAt.END).toString(), tx, h / 2f - 3 * d, tp);
            tp.setTypeface(Typeface.DEFAULT);
            tp.setTextSize(12.5f * d);
            tp.setColor(Color.parseColor("#B3FFFFFF"));
            c.drawText(TextUtils.ellipsize(sub, new android.text.TextPaint(tp), max, TextUtils.TruncateAt.END).toString(), tx, h / 2f + 15 * d, tp);
        }
    }
}
