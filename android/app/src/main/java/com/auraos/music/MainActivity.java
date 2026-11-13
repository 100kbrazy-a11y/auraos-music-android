package com.auraos.music;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
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
        super.onCreate(savedInstanceState);
        Window w = getWindow();
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

    private void push(WebView web) {
        String js = "(function(){var s=document.documentElement.style;"
            + "s.setProperty('--sat','" + top + "px');s.setProperty('--sab','" + bottom + "px');"
            + "s.setProperty('--sal','" + left + "px');s.setProperty('--sar','" + right + "px');"
            + "s.setProperty('--kb','" + ime + "px');document.documentElement.classList.add('edge');})()";
        web.post(() -> web.evaluateJavascript(js, null));
    }
}
