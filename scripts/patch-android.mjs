// Applies Strata Music's Android settings to the generated Capacitor project. Safe to run again and again.
import fs from "node:fs";

const pkg = JSON.parse(fs.readFileSync("package.json", "utf8"));
const edit = (file, fn) => { const s = fs.readFileSync(file, "utf8"); const t = fn(s); if (t !== s) fs.writeFileSync(file, t); };

// permissions: background playback service, notification, saving packs to Downloads on older phones
edit("android/app/src/main/AndroidManifest.xml", (s) => {
  const perms = [
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.WAKE_LOCK",
    "android.permission.POST_NOTIFICATIONS",
    ['android.permission.WRITE_EXTERNAL_STORAGE', ' android:maxSdkVersion="29"'],
    ['android.permission.READ_EXTERNAL_STORAGE', ' android:maxSdkVersion="32"'],
    // only used for one thing: the little "name your music pack / making it…" window shown over another app after
    // you drag an album or playlist into it (the app asks before using it)
    "android.permission.SYSTEM_ALERT_WINDOW",
  ];
  for (const p of perms) {
    const [name, extra] = Array.isArray(p) ? p : [p, ""];
    if (!s.includes(`"${name}"`)) s = s.replace("</manifest>", `    <uses-permission android:name="${name}"${extra} />\n</manifest>`);
  }
  if (!s.includes("requestLegacyExternalStorage")) s = s.replace("<application", '<application\n        android:requestLegacyExternalStorage="true"');
  // the apps you can drop songs / packs onto (Android 11+ only shows apps the manifest says it looks for)
  if (!s.includes("<queries>")) s = s.replace("<application", `<queries>
        <intent>
            <action android:name="android.intent.action.SEND" />
            <data android:mimeType="*/*" />
        </intent>
    </queries>

    <application`);
  // YouTube / YouTube Music › Share › Strata Music: the link comes straight in (Add from Link)
  if (!s.includes("android.intent.action.SEND\"") || !s.includes('android:label="Add to Strata Music"')) {
    s = s.replace(/(<activity[^>]*\.MainActivity[\s\S]*?)(<\/activity>)/, `$1    <intent-filter android:label="Add to Strata Music">
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        $2`);
  }
  // the native music player (keeps playing with the screen off; lock screen / notification / headphone controls)
  if (!s.includes(".AuraPlaybackService")) s = s.replace("</application>", `    <service
            android:name=".AuraPlaybackService"
            android:foregroundServiceType="mediaPlayback"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>
    </application>`);
  // drag out to other apps: songs and music packs are handed over through this (read-only, per-drop permission)
  if (!s.includes(".DragOutProvider")) s = s.replace("</application>", `    <provider
            android:name=".DragOutProvider"
            android:authorities="\${applicationId}.dragout"
            android:exported="false"
            android:grantUriPermissions="true" />
    </application>`);
  return s;
});

// dark status / navigation bars that match the app
edit("android/app/src/main/res/values/styles.xml", (s) => {
  const bars = `        <item name="android:statusBarColor">#0b0b0e</item>\n        <item name="android:navigationBarColor">#0e0e12</item>\n        <item name="android:windowBackground">@color/auraBackground</item>\n`;
  if (!s.includes("android:navigationBarColor")) {
    s = s.replace(/(<style name="AppTheme.NoActionBar"[^>]*>\n)/, `$1${bars}`);
    s = s.replace(/(<style name="AppTheme.NoActionBarLaunch"[^>]*>\n)/, `$1        <item name="postSplashScreenTheme">@style/AppTheme.NoActionBar</item>\n        <item name="windowSplashScreenBackground">#0b0b0e</item>\n`);
  }
  return s;
});
const colors = "android/app/src/main/res/values/colors.xml";
if (!fs.existsSync(colors)) fs.writeFileSync(colors, '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n');
edit(colors, (s) => (s.includes("auraBackground") ? s : s.replace("</resources>", '    <color name="auraBackground">#0b0b0e</color>\n</resources>')));

// the native player: Media3 (ExoPlayer + media session)
edit("android/app/build.gradle", (s) => {
  if (!s.includes("androidx.media3:media3-exoplayer")) s = s.replace(/dependencies \{\n/, `dependencies {
    implementation "androidx.media3:media3-exoplayer:1.4.1"
    implementation "androidx.media3:media3-session:1.4.1"
`);
  // release builds: lint warnings about Media3's "unstable" APIs (used on purpose) mustn't stop the build
  if (!s.includes("checkReleaseBuilds")) s = s.replace(/android \{\n/, `android {
    lint {
        checkReleaseBuilds false
        abortOnError false
    }
`);
  return s;
});

// version from package.json (versionCode grows with every version bump)
edit("android/app/build.gradle", (s) => {
  const [a, b, c] = pkg.version.split(".").map((x) => parseInt(x, 10) || 0);
  return s.replace(/versionCode \d+/, `versionCode ${a * 10000 + b * 100 + c}`).replace(/versionName "[^"]*"/, `versionName "${pkg.version}"`);
});
// edge-to-edge: the app's own background (album colours) runs behind the status bar and the navigation bar.
// The WebView is told how big those bars (and the keyboard) are through CSS variables --sat/--sab/--sal/--sar/--kb.
const MAIN = "android/app/src/main/java/com/auraos/music/MainActivity.java";
fs.writeFileSync(MAIN, `package com.auraos.music;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private int top, bottom, left, right, ime;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AuraPoPlugin.class);             // YouTube proof-of-origin tokens (Add from Link)
        registerPlugin(DragOutPlugin.class);            // drag songs / albums / playlists out to other apps
        registerPlugin(ShareInPlugin.class);            // links shared to the app (YouTube › Share › Strata Music)
        registerPlugin(AuraPlayerPlugin.class);         // the native music player + battery state
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        preferFastestRefresh(w);
        WindowCompat.setDecorFitsSystemWindows(w, false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(w, w.getDecorView());
        bars.setAppearanceLightStatusBars(false);       // white icons on our dark / album-coloured background
        bars.setAppearanceLightNavigationBars(false);

        final WebView web = getBridge().getWebView();
        ViewCompat.setOnApplyWindowInsetsListener(web, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets kb = insets.getInsets(WindowInsetsCompat.Type.ime());
            float d = getResources().getDisplayMetrics().density;
            top = Math.round(sys.top / d);
            bottom = Math.round(sys.bottom / d);
            left = Math.round(sys.left / d);
            right = Math.round(sys.right / d);
            ime = Math.max(0, Math.round((kb.bottom - sys.bottom) / d));
            push(web);
            return insets;
        });
        // the page may not be loaded the first time the insets arrive, so send them again a few times
        Handler h = new Handler(Looper.getMainLooper());
        for (int ms : new int[] { 300, 900, 2000, 4000 }) h.postDelayed(() -> push(web), ms);
    }

    @Override
    public void onResume() {
        super.onResume();
        WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (web != null) {
            ViewCompat.requestApplyInsets(web);
            push(web);
        }
    }

    /** Ask for the screen's fastest refresh rate (90 / 120 Hz) at its current resolution: Android otherwise often
     *  keeps an app at 60 Hz, and every scroll, swipe and animation feels heavier than the rest of the phone. */
    private void preferFastestRefresh(Window w) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            Display d = getWindowManager().getDefaultDisplay();
            Display.Mode cur = d.getMode(), best = cur;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate() + 0.5f) best = m;
            }
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            lp.preferredRefreshRate = best.getRefreshRate();
            w.setAttributes(lp);
        } catch (Exception ignored) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) preferFastestRefresh(getWindow());     // (some phones drop back after a pause)
    }

    private void push(WebView web) {
        String js = "(function(){var s=document.documentElement.style;"
            + "s.setProperty('--sat','" + top + "px');s.setProperty('--sab','" + bottom + "px');"
            + "s.setProperty('--sal','" + left + "px');s.setProperty('--sar','" + right + "px');"
            + "s.setProperty('--kb','" + ime + "px');document.documentElement.classList.add('edge');})()";
        web.post(() -> web.evaluateJavascript(js, null));
    }
}
`);

console.log("Android project configured for Strata Music " + pkg.version);
