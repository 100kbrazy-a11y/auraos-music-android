package com.auraos.music;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * YouTube proof-of-origin tokens for "Add from Link". Google only gives these tokens to pages on www.youtube.com,
 * so a hidden WebView loads a tiny page *as* https://www.youtube.com, runs YouTube's own check there (the script
 * comes from the app: src/potoken-page.js) and mints one token per song.
 */
@CapacitorPlugin(name = "AuraPo")
public class AuraPoPlugin extends Plugin {
    private WebView web;
    private boolean ready = false;
    private int scriptKey = 0;
    private final List<Runnable> waiting = new ArrayList<>();
    private final Map<String, PluginCall> calls = new HashMap<>();
    private int nextId = 1;
    private final Handler main = new Handler(Looper.getMainLooper());

    @PluginMethod
    public void mint(PluginCall call) {
        final String id = call.getString("id");
        final String script = call.getString("script");
        if (id == null || script == null) { call.reject("missing id/script"); return; }
        main.post(() -> withPage(script, call, () -> {
            String req = String.valueOf(nextId++);
            calls.put(req, call);
            String js = "(function(){var r=" + JSONObject.quote(req) + ";"
                + "if(!window.__auraMint){AuraPoBridge.done(r,'','the check script did not start');return;}"
                + "window.__auraMint(" + JSONObject.quote(id) + ")"
                + ".then(function(t){AuraPoBridge.done(r,String(t||''),'');})"
                + ".catch(function(e){AuraPoBridge.done(r,'',String((e&&e.message)||e));});})()";
            web.evaluateJavascript(js, null);
            main.postDelayed(() -> finishCall(req, "", "YouTube's check took too long"), 60000);
        }));
    }

    @PluginMethod
    public void reset(PluginCall call) {
        main.post(() -> { drop(); call.resolve(); });
    }

    private void finishCall(String req, String token, String error) {
        PluginCall c = calls.remove(req);
        if (c == null) return;
        if (token != null && !token.isEmpty()) {
            JSObject r = new JSObject();
            r.put("token", token);
            c.resolve(r);
        } else {
            c.reject(error == null || error.isEmpty() ? "no token" : error);
        }
    }

    private void drop() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        web = null; ready = false; scriptKey = 0;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void withPage(String script, PluginCall call, Runnable then) {
        int key = script.hashCode();
        if (web != null && scriptKey == key) {
            if (ready) then.run(); else waiting.add(then);
            return;
        }
        drop();
        waiting.clear();
        waiting.add(then);
        web = new WebView(getContext());
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString(ws.getUserAgentString().replace("; wv)", ")"));   // an ordinary Chrome, not "WebView"
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void done(String req, String token, String error) {
                main.post(() -> finishCall(req, token, error));
            }
        }, "AuraPoBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (view != web || ready) return;
                ready = true;
                List<Runnable> pending = new ArrayList<>(waiting);
                waiting.clear();
                for (Runnable r : pending) r.run();
            }
        });
        scriptKey = key;
        ready = false;
        String safe = script.replace("</script", "<\\/script");
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>YouTube</title></head><body><script>"
            + safe + "</script></body></html>";
        web.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "utf-8", null);
        main.postDelayed(() -> {
            if (web != null && !ready && scriptKey == key) {
                List<Runnable> pending = new ArrayList<>(waiting);
                waiting.clear();
                ready = true;                     // try anyway; the call itself reports what went wrong
                for (Runnable r : pending) r.run();
            }
        }, 15000);
    }
}
