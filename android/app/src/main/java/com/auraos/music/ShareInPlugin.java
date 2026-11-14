package com.auraos.music;

import android.content.Intent;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Links shared to AuraOS Music (YouTube / YouTube Music › Share › Add to AuraOS Music). The app picks the link up
 * with take() when it starts, and gets a "shared" event when it's already open.
 */
@CapacitorPlugin(name = "ShareIn")
public class ShareInPlugin extends Plugin {
    private String pending = null;

    @Override
    public void load() {
        if (getActivity() != null) grab(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (grab(intent)) {
            JSObject d = new JSObject();
            d.put("text", pending);
            pending = null;
            notifyListeners("shared", d, true);
        }
    }

    private boolean grab(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return false;
        String t = i.getStringExtra(Intent.EXTRA_TEXT);
        if (t == null || t.trim().isEmpty()) return false;
        pending = t;
        i.setAction(Intent.ACTION_MAIN);          // handled: don't pick it up again after a restart
        i.removeExtra(Intent.EXTRA_TEXT);
        return true;
    }

    @PluginMethod
    public void take(PluginCall call) {
        JSObject r = new JSObject();
        r.put("text", pending);
        pending = null;
        call.resolve(r);
    }
}
