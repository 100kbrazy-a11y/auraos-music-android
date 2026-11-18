package com.auraos.music;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import com.getcapacitor.JSObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The music player itself, running in Android's media service: it keeps playing with the screen off, moves on to
 * the next song by itself, and gives the lock screen / notification / headphone buttons.
 * Two ExoPlayers take turns so one song can fade into the next (crossfade).
 */
@OptIn(markerClass = UnstableApi.class)
public class AuraPlaybackService extends MediaSessionService {
    interface Events { void emit(String name, JSObject data); }

    static volatile AuraPlaybackService instance;
    static volatile Events events;

    final ExoPlayer[] players = new ExoPlayer[2];
    final AuraAudioProcessor[] procs = new AuraAudioProcessor[2];
    int cur = 0;
    MediaSession session;
    final List<MediaItem> items = new ArrayList<>();
    float crossfade = 0;
    boolean beatMatch = true;
    /** each song's beat where it starts and where it ends: { bpm in, beat in (ms), bpm out, beat out (ms), cut start (ms) } */
    static final java.util.Map<String, double[]> grids = new java.util.concurrent.ConcurrentHashMap<>();
    int repeat = Player.REPEAT_MODE_OFF;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final AudioAttributes attrs = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build();

    // the fade in progress
    private boolean fading = false;
    private long fadeStart, fadeMs;
    private final Runnable fadeStep = this::stepFade;
    private final Runnable ticker = this::tick;
    private boolean ticking = false;

    @Override
    public void onCreate() {
        super.onCreate();
        for (int i = 0; i < 2; i++) players[i] = build(i);
        players[0].setAudioAttributes(attrs, true);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, players[0]).setId("auraos-music").setSessionActivity(pi).build();
        instance = this;
    }

    private ExoPlayer build(int i) {
        final AuraAudioProcessor proc = new AuraAudioProcessor();
        procs[i] = proc;
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this) {
            @Nullable
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                    .setAudioProcessors(new AudioProcessor[] { proc })
                    .setEnableAudioTrackPlaybackParams(true)     // tempo changes (beat matching) applied by the system, smoothly, pitch kept
                    .setEnableFloatOutput(false)
                    .build();
            }
        };
        // every song is a file on the phone (opened and decoded in milliseconds), so start the moment the first audio is
        // decoded: the defaults wait for 2.5 s of it to pile up first (meant for streaming), which was the pause on every
        // new song and every seek. It still keeps a deep buffer while playing, so nothing ever stutters.
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(20000, 60000, 0, 0)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build();
        ExoPlayer p = new ExoPlayer.Builder(this, rf)
            .setLoadControl(lc)
            .setSeekParameters(SeekParameters.EXACT)
            .setAudioAttributes(attrs, false)
            .setHandleAudioBecomingNoisy(true)          // headphones unplugged: pause
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build();
        p.addListener(new Listen(i));
        return p;
    }

    ExoPlayer active() { return players[cur]; }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) { return session; }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        ExoPlayer p = active();
        if (p == null || !p.getPlayWhenReady() || p.getMediaItemCount() == 0) stopSelf();   // (keeps playing if it was)
    }

    @Override
    public void onDestroy() {
        instance = null;
        h.removeCallbacksAndMessages(null);
        if (session != null) session.release();
        for (ExoPlayer p : players) if (p != null) p.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ commands (main thread)
    static MediaItem item(Context c, JSObject o) {
        String path = o.getString("path", "");
        Uri uri = path.startsWith("file:") || path.startsWith("content:") ? Uri.parse(path) : Uri.fromFile(new File(c.getFilesDir(), path));
        MediaMetadata md = new MediaMetadata.Builder()
            .setTitle(o.getString("title", ""))
            .setArtist(o.getString("artist", ""))
            .setAlbumTitle(o.getString("album", ""))
            .build();
        String mid = o.getString("id", path);
        MediaItem.Builder b = new MediaItem.Builder().setMediaId(mid).setUri(uri).setMediaMetadata(md);
        double bs = o.optDouble("bs", 0), be = o.optDouble("be", 0);
        if (bs > 0 || be > 0) grids.put(mid, new double[] { bs, o.optDouble("bb", 0), be, o.optDouble("eb", 0), Math.max(0, o.optLong("start", 0)) });
        else grids.remove(mid);
        // silence cut from the start / end: the player itself skips it (seamlessly), so crossfades start at the last sound
        long st = Math.max(0, o.optLong("start", 0)), en = Math.max(0, o.optLong("end", 0));
        if (st > 0 || en > 0) {
            b.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(st).setEndPositionMs(en > st ? en : C.TIME_END_OF_SOURCE).build());
        }
        return b.build();
    }

    void setQueue(List<MediaItem> list, int index, long posMs, boolean play, boolean keep) {
        cancelFade();
        ExoPlayer p = active();
        index = Math.max(0, Math.min(index, list.size() - 1));
        MediaItem now = p.getMediaItemCount() > 0 ? p.getCurrentMediaItem() : null;
        boolean same = keep && now != null && !list.isEmpty() && now.mediaId.equals(list.get(index).mediaId)
            && p.getPlaybackState() != Player.STATE_IDLE;
        items.clear();
        items.addAll(list);
        if (list.isEmpty()) { p.stop(); p.clearMediaItems(); emitState(); return; }
        if (same) {                                         // the song playing stays playing: just change what's around it
            int ci = p.getCurrentMediaItemIndex(), n = p.getMediaItemCount();
            if (ci + 1 < n) p.removeMediaItems(ci + 1, n);
            if (ci > 0) p.removeMediaItems(0, ci);
            if (index > 0) p.addMediaItems(0, new ArrayList<>(list.subList(0, index)));
            if (index + 1 < list.size()) p.addMediaItems(new ArrayList<>(list.subList(index + 1, list.size())));
        } else {
            p.setMediaItems(new ArrayList<>(list), index, Math.max(0, posMs));
            p.prepare();
            p.setPlayWhenReady(play);
        }
        p.setRepeatMode(repeat);
        emitState();
    }

    void skipTo(int index, long posMs, boolean play) {
        cancelFade();
        ExoPlayer p = active();
        if (index < 0 || index >= p.getMediaItemCount()) return;
        p.seekTo(index, Math.max(0, posMs));
        if (p.getPlaybackState() == Player.STATE_IDLE) p.prepare();
        p.setPlayWhenReady(play);
    }

    void play() {
        ExoPlayer p = active();
        if (p.getMediaItemCount() == 0) return;
        if (p.getPlaybackState() == Player.STATE_IDLE) p.prepare();
        if (p.getPlaybackState() == Player.STATE_ENDED) p.seekTo(p.getCurrentMediaItemIndex(), 0);
        p.play();
    }

    void pause() { cancelFade(); active().pause(); }

    void seek(long ms) { cancelFade(); active().seekTo(Math.max(0, ms)); }

    void setRepeat(int mode) {
        repeat = mode;
        if (mode == Player.REPEAT_MODE_ONE) cancelFade();
        for (ExoPlayer p : players) p.setRepeatMode(mode);
        emitState();
    }

    void setArtwork(String id, @Nullable byte[] data) {
        ExoPlayer p = active();
        for (int i = 0; i < p.getMediaItemCount(); i++) {
            MediaItem it = p.getMediaItemAt(i);
            if (!it.mediaId.equals(id)) continue;
            MediaMetadata md = it.mediaMetadata.buildUpon().setArtworkData(data, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build();
            MediaItem nu = it.buildUpon().setMediaMetadata(md).build();
            p.replaceMediaItem(i, nu);
            if (i < items.size() && items.get(i).mediaId.equals(id)) items.set(i, nu);
            break;
        }
    }

    // Where the speaker is in the processed audio. The processor counts ms of audio since its last flush; the player's own
    // position already includes the output latency (Bluetooth too). Since a flush starts at the position the player was sent
    // to, "played" = position - where it started (plus the songs that ran on into each other without a flush).
    final double[] vizStart = { 0, 0 }, vizAcc = { 0, 0 };
    final boolean[] vizSeek = { false, false };
    private final int[] vizFlush = { -1, -1 };
    byte[] levels() {
        int i = cur;
        AuraAudioProcessor pr = procs[i];
        long pos = active().getCurrentPosition();
        int fl = pr.flushes;
        if (fl != vizFlush[i]) { vizFlush[i] = fl; if (!vizSeek[i]) { vizStart[i] = pos; vizAcc[i] = 0; } vizSeek[i] = false; }
        double played = pos + vizAcc[i] - vizStart[i];
        if (played > pr.processedMs() + 50 || played < -2000) { vizStart[i] = pos - Math.max(0, pr.processedMs() - pr.leadMs()); vizAcc[i] = 0; played = pos - vizStart[i]; }   // lost track: line up again
        return pr.levelsAt(played);
    }

    // ------------------------------------------------------------------ crossfade
    private void tick() {
        ticking = false;
        ExoPlayer p = active();
        if (!p.isPlaying()) return;
        if (!fading && crossfade > 0 && repeat != Player.REPEAT_MODE_ONE) {
            long dur = p.getDuration(), pos = p.getCurrentPosition();
            int next = p.getNextMediaItemIndex();
            if (dur != C.TIME_UNSET && dur > crossfade * 2000 + 1000 && pos > 1000 && next != C.INDEX_UNSET) {
                long left = dur - pos, xf = (long) (crossfade * 1000);
                if (matching && left > xf + RAMP_MS + 2500) resetMatch();          // (it was moved back: start over)
                if (!matching && beatMatch && left <= xf + RAMP_MS + 600 && left > xf + 400) planMatch(p, next);
                double r = matching ? ratio : 1;
                if (left / r <= xf && left > 300) startFade(next, (long) (left / r));
            }
        }
        schedule();
    }

    private void schedule() {
        if (ticking || crossfade <= 0) return;
        ticking = true;
        h.postDelayed(ticker, 200);
    }

    // ------------------------------------------------------------------ beat matching
    // A few seconds before the crossfade, the song playing eases (pitch kept) to the next song's tempo; the next song
    // then starts exactly on its beat, and while they overlap the old one's tempo is nudged a hair so the beats stay
    // locked. Only when both songs have a clear, steady beat and they're within 8% (or half / double) of each other —
    // otherwise it's the plain crossfade. Anything you do (pause, seek, skip) puts both back to normal speed at once.
    private static final long RAMP_MS = 6000;
    private boolean matching = false, toReady = false;
    private double ratio = 1, corr = 1, speedSet = 1, speedSent = 1;
    private long rampStart, toStartedAt;
    private double[] gOut, gIn;
    private final Runnable rampStep = this::stepRamp;
    private final Runnable startTo = this::startIncoming;
    private static final double CONF_MIN = 3.5;

    private void planMatch(ExoPlayer p, int next) {
        MediaItem now = p.getCurrentMediaItem();
        if (now == null || next < 0 || next >= items.size()) return;
        double[] a = grids.get(now.mediaId), b = grids.get(items.get(next).mediaId);
        if (a == null || b == null || a[2] <= 0 || b[0] <= 0) return;
        double r = b[0] / a[2];
        while (r > 1.414) r /= 2;
        while (r < 0.707) r *= 2;
        if (Math.abs(r - 1) > 0.08) return;                    // too far apart to sound natural: plain crossfade
        gOut = a; gIn = b; ratio = r; corr = 1; speedSet = 1;
        matching = true;
        rampStart = SystemClock.uptimeMillis();
        // the next song, loaded and waiting, so it can start on the exact moment
        ExoPlayer to = players[1 - cur];
        to.setRepeatMode(repeat);
        to.setMediaItems(new ArrayList<>(items), next, 0);
        to.setVolume(0f);
        to.setPlayWhenReady(false);
        to.prepare();
        toReady = true;
        h.removeCallbacks(rampStep);
        h.post(rampStep);
    }

    private static double mod(double x, double m) { return ((x % m) + m) % m; }

    private void stepRamp() {
        if (!matching) return;
        double x = Math.min(1, (SystemClock.uptimeMillis() - rampStart) / (double) RAMP_MS);
        double e = x * x * (3 - 2 * x);
        // the beats locked together while both play: the old song (fading out) follows the new one
        if (fading && toStartedAt > 0 && SystemClock.uptimeMillis() - toStartedAt > 150) {
            ExoPlayer from = players[cur], to = players[1 - cur];
            double PO = 60000 / gOut[2], PI = 60000 / gIn[0];
            double Ao = from.getCurrentPosition() + gOut[4], Ai = to.getCurrentPosition() + gIn[4];
            double tOut = mod(gOut[3] - Ao, PO) / ratio, tIn = mod(gIn[1] - Ai, PI);
            double f = Math.min(PO / ratio, PI);
            double err = mod(tOut - tIn + f / 2, f) - f / 2;          // ms; > 0: the old song's beat is late
            corr = 1 + Math.max(-0.04, Math.min(0.04, err / 1000.0));
        }
        double sp = (1 + (ratio - 1) * e) * corr;
        if (Math.abs(sp - speedSet) > 0.0004) {
            speedSet = sp;
            players[cur].setPlaybackParameters(new PlaybackParameters((float) sp, 1f));
            if (Math.abs(sp - speedSent) > 0.005) { speedSent = sp; emitState(); }      // (the lyrics keep time with it)
        }
        h.postDelayed(rampStep, 100);
    }

    private void resetMatch() {
        boolean was = matching;
        matching = false;
        h.removeCallbacks(rampStep);
        h.removeCallbacks(startTo);
        corr = 1; ratio = 1; speedSet = 1; speedSent = 1; toStartedAt = 0;
        for (ExoPlayer p : players) if (p != null && p.getPlaybackParameters().speed != 1f) p.setPlaybackParameters(PlaybackParameters.DEFAULT);
        if (was && toReady && !fading) { players[1 - cur].stop(); }
        toReady = false;
    }

    private void startFade(int next, long ms) {
        ExoPlayer from = active(), to = players[1 - cur];
        if (!toReady) {
            to.setRepeatMode(repeat);
            to.setMediaItems(new ArrayList<>(items), next, 0);
            to.setVolume(0f);
            to.prepare();
        }
        from.setPauseAtEndOfMediaItems(true);           // the old song stops at its end instead of moving on
        from.setAudioAttributes(attrs, false);          // the new one gets the audio focus
        to.setAudioAttributes(attrs, true);
        fading = true;
        long wait = 0;
        if (matching) {
            // start the new song so its first beat lands on one of the old song's beats
            double PO = 60000 / gOut[2], PI = 60000 / gIn[0];
            double A = from.getCurrentPosition() + gOut[4];
            double dOut = mod(gOut[3] - A, PO) / ratio;                     // until the old song's next beat (real ms)
            double s0 = gIn[4], dIn = mod(gIn[1] - s0, PI);                 // the new song: its start to its first beat
            double f = Math.min(PO / ratio, PI);
            wait = (long) Math.round(mod(dOut - dIn, f));
        }
        fadeMs = Math.max(300, ms - wait);
        fadeStart = SystemClock.uptimeMillis() + wait;
        if (wait > 0) h.postDelayed(startTo, wait); else startIncoming();
        h.post(fadeStep);
    }

    private void startIncoming() {
        if (!fading) return;
        players[1 - cur].play();
        toStartedAt = SystemClock.uptimeMillis();
    }

    private void stepFade() {
        if (!fading) return;
        double x = Math.max(0, Math.min(1, (SystemClock.uptimeMillis() - fadeStart) / (double) fadeMs));
        players[cur].setVolume((float) Math.cos(x * Math.PI / 2));        // equal-power: even loudness through the blend
        players[1 - cur].setVolume((float) Math.sin(x * Math.PI / 2));
        if (x >= 1) finishFade();
        else h.postDelayed(fadeStep, 40);
    }

    private void finishFade() {
        if (!fading) return;
        fading = false;
        h.removeCallbacks(fadeStep);
        ExoPlayer from = active(), to = players[1 - cur];
        if (!to.getPlayWhenReady()) to.play();          // (in case its start was still waiting)
        matching = false; toReady = false; toStartedAt = 0;
        h.removeCallbacks(rampStep); h.removeCallbacks(startTo);
        from.setPauseAtEndOfMediaItems(false);
        from.stop();
        from.setPlaybackParameters(PlaybackParameters.DEFAULT);
        to.setPlaybackParameters(PlaybackParameters.DEFAULT);
        from.setVolume(1f);
        to.setVolume(1f);
        cur = 1 - cur;
        session.setPlayer(to);
        emitTrack("auto");
        emitState();
        schedule();
    }

    private void cancelFade() {
        resetMatch();
        if (!fading) return;
        fading = false;
        h.removeCallbacks(fadeStep);
        ExoPlayer keep = active(), drop = players[1 - cur];
        drop.stop();
        drop.setAudioAttributes(attrs, false);
        drop.setVolume(1f);
        keep.setPauseAtEndOfMediaItems(false);
        keep.setVolume(1f);
        keep.setAudioAttributes(attrs, true);
    }

    // ------------------------------------------------------------------ telling the app
    JSObject state() {
        ExoPlayer p = active();
        JSObject o = new JSObject();
        int st = p.getPlaybackState();
        o.put("playing", p.getPlayWhenReady() && st != Player.STATE_ENDED && st != Player.STATE_IDLE);
        o.put("index", p.getMediaItemCount() > 0 ? p.getCurrentMediaItemIndex() : -1);
        MediaItem it = p.getMediaItemCount() > 0 ? p.getCurrentMediaItem() : null;
        o.put("id", it != null ? it.mediaId : "");
        o.put("pos", p.getCurrentPosition());
        long d = p.getDuration();
        o.put("dur", d == C.TIME_UNSET ? 0 : d);
        o.put("state", st);
        o.put("speed", p.getPlaybackParameters().speed);
        o.put("count", p.getMediaItemCount());
        return o;
    }

    private void emit(String name, JSObject o) { Events e = events; if (e != null) e.emit(name, o); }
    void emitState() { emit("state", state()); }
    private void emitTrack(String why) { JSObject o = state(); o.put("reason", why); emit("track", o); }

    private class Listen implements Player.Listener {
        final int i;
        Listen(int i) { this.i = i; }
        private boolean mine() { return i == cur; }

        @Override public void onIsPlayingChanged(boolean isPlaying) {
            if (!mine()) return;
            if (isPlaying) schedule();
            emitState();
        }
        @Override public void onPlaybackStateChanged(int state) {
            if (!mine()) return;
            if (state == Player.STATE_ENDED && !fading) emit("ended", state());
            emitState();
        }
        @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            if (!mine()) return;
            if (fading) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) { finishFade(); return; }
                if (!playWhenReady) cancelFade();                  // paused during a fade: keep the old song
            }
            emitState();
        }
        @Override public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            if (!mine() || fading || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return;
            emitTrack(reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ? "seek" : reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT ? "repeat" : "auto");
        }
        @Override public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
            // keep the visualizer's clock lined up with the audio
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) { vizStart[i] = newPosition.positionMs; vizAcc[i] = 0; vizSeek[i] = true; }
            else if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) vizAcc[i] += oldPosition.positionMs - newPosition.positionMs;
            if (mine()) emitState();
        }
        @Override public void onPlayerError(@NonNull PlaybackException error) {
            if (i != cur) {                                   // the song fading in failed: just keep the old one
                if (fading) cancelFade();
                return;
            }
            JSObject o = state();
            o.put("message", error.getErrorCodeName() + (error.getMessage() != null ? " · " + error.getMessage() : ""));
            emit("error", o);
        }
    }
}
