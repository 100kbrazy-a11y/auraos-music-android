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
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
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
                    .setEnableFloatOutput(false)
                    .build();
            }
        };
        ExoPlayer p = new ExoPlayer.Builder(this, rf)
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
        MediaItem.Builder b = new MediaItem.Builder().setMediaId(o.getString("id", path)).setUri(uri).setMediaMetadata(md);
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

    byte[] levels() { return procs[cur].levels(); }

    // ------------------------------------------------------------------ crossfade
    private void tick() {
        ticking = false;
        ExoPlayer p = active();
        if (!p.isPlaying()) return;
        if (!fading && crossfade > 0 && repeat != Player.REPEAT_MODE_ONE) {
            long dur = p.getDuration(), pos = p.getCurrentPosition();
            int next = p.getNextMediaItemIndex();
            if (dur != C.TIME_UNSET && dur > crossfade * 2000 + 1000 && pos > 1000 && next != C.INDEX_UNSET) {
                long left = dur - pos;
                if (left <= crossfade * 1000 && left > 300) startFade(next, left);
            }
        }
        schedule();
    }

    private void schedule() {
        if (ticking || crossfade <= 0) return;
        ticking = true;
        h.postDelayed(ticker, 200);
    }

    private void startFade(int next, long ms) {
        ExoPlayer from = active(), to = players[1 - cur];
        to.setRepeatMode(repeat);
        to.setMediaItems(new ArrayList<>(items), next, 0);
        to.setVolume(0f);
        to.prepare();
        from.setPauseAtEndOfMediaItems(true);           // the old song stops at its end instead of moving on
        from.setAudioAttributes(attrs, false);          // the new one gets the audio focus
        to.setAudioAttributes(attrs, true);
        to.play();
        fading = true;
        fadeStart = SystemClock.uptimeMillis();
        fadeMs = Math.max(300, ms);
        h.post(fadeStep);
    }

    private void stepFade() {
        if (!fading) return;
        double x = Math.min(1, (SystemClock.uptimeMillis() - fadeStart) / (double) fadeMs);
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
        from.setPauseAtEndOfMediaItems(false);
        from.stop();
        from.setVolume(1f);
        to.setVolume(1f);
        cur = 1 - cur;
        session.setPlayer(to);
        emitTrack("auto");
        emitState();
        schedule();
    }

    private void cancelFade() {
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
