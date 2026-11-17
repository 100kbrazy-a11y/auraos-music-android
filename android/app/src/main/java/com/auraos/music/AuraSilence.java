package com.auraos.music;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Finds the silence at the start and end of a song: decodes only the first and last 30 seconds (not the whole
 * file), in 10 ms slices; a slice is sound when its peak is above -60 dBFS (so quiet fade-ins / fade-outs and
 * reverb tails count as sound; only true silence is cut). Returns { first sound, last sound, length } in seconds.
 */
final class AuraSilence {
    private static final float THRESH = 0.001f;            // -60 dBFS
    private static final long WINDOW_US = 30_000_000L;      // how much of each end is looked at

    static double[] scan(File f, double knownDur) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getPath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat m = ex.getTrackFormat(i);
                String mime = m.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = m; break; }
            }
            if (track < 0) throw new Exception("no audio");
            ex.selectTrack(track);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : (long) (knownDur * 1e6);
            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            // the start: the first 30 s (the first sound, and the beat a song coming in starts on)
            AuraTempo tS = new AuraTempo(rate), tE = new AuraTempo(rate);
            double first = pass(ex, fmt, 0, Math.min(WINDOW_US, durUs > 0 ? durUs : WINDOW_US), true, tS);
            // the end: the last 30 s (the last sound, and the beat a song going out is on)
            long from = durUs > WINDOW_US ? durUs - WINDOW_US : 0;
            double last = pass(ex, fmt, from, Long.MAX_VALUE, false, tE);
            double dur = durUs > 0 ? durUs / 1e6 : knownDur;
            double[] a = tS.analyze(), b = tE.analyze();
            return new double[] { Math.max(0, first), last < 0 ? dur : last, dur,
                a == null ? 0 : a[0], a == null ? 0 : a[1], a == null ? 0 : a[2],
                b == null ? 0 : b[0], b == null ? 0 : b[1], b == null ? 0 : b[2] };
        } finally {
            ex.release();
        }
    }

    /**
     * Decodes from fromUs (to untilUs). firstOnly: returns the time of the first loud slice (stops there), or -1;
     * otherwise returns the end time of the last loud slice, or -1 if it's all silent.
     */
    private static double pass(MediaExtractor ex, MediaFormat fmt, long fromUs, long untilUs, boolean firstOnly, AuraTempo tempo) throws Exception {
        double firstLoud = -1;
        MediaCodec dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        try {
            dec.configure(fmt, null, null, 0);
            dec.start();
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean inDone = false;
            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int enc = AudioFormat.ENCODING_PCM_16BIT;
            double lastLoud = -1;
            short[] s16 = new short[0];
            float[] f32 = new float[0];
            long idle = 0;
            while (true) {
                boolean busy = false;
                while (!inDone) {
                    int i = dec.dequeueInputBuffer(0);
                    if (i < 0) break;
                    ByteBuffer b = dec.getInputBuffer(i);
                    int n = b == null ? -1 : ex.readSampleData(b, 0);
                    long t = ex.getSampleTime();
                    if (n < 0 || t > untilUs) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                    else { dec.queueInputBuffer(i, 0, n, t, 0); ex.advance(); }
                    busy = true;
                }
                int o = dec.dequeueOutputBuffer(bi, 0);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    tempo.setRate(rate);
                    continue;
                }
                if (o >= 0) {
                    busy = true;
                    ByteBuffer ob = dec.getOutputBuffer(o);
                    if (ob != null && bi.size > 0 && bi.presentationTimeUs + 1 >= fromUs) {
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        ByteBuffer src = ob.slice().order(ByteOrder.nativeOrder());
                        boolean flt = enc == AudioFormat.ENCODING_PCM_FLOAT;
                        int samples = bi.size / (flt ? 4 : 2), frames = samples / Math.max(1, ch);
                        if (flt) { if (f32.length < samples) f32 = new float[samples]; src.asFloatBuffer().get(f32, 0, samples); }
                        else { if (s16.length < samples) s16 = new short[samples]; src.asShortBuffer().get(s16, 0, samples); }
                        // the beat finder hears every sample (channels averaged)
                        double tb = bi.presentationTimeUs / 1e6;
                        float inv = 1f / Math.max(1, ch);
                        for (int k = 0; k < frames; k++) {
                            float m = 0;
                            for (int c = 0, j = k * ch; c < ch; c++, j++) m += flt ? f32[j] : s16[j] * (1f / 32768f);
                            tempo.feed(m * inv, tb + k / (double) rate);
                        }
                        int win = Math.max(1, rate / 100);                   // 10 ms slices
                        for (int k0 = 0; k0 < frames; k0 += win) {
                            int k1 = Math.min(frames, k0 + win);
                            float peak = 0;
                            for (int j = k0 * ch, e = k1 * ch; j < e; j++) {
                                float v = flt ? Math.abs(f32[j]) : Math.abs(s16[j]) * (1f / 32768f);
                                if (v > peak) peak = v;
                            }
                            if (peak > THRESH) {
                                double t = bi.presentationTimeUs / 1e6 + k0 / (double) rate;
                                if (firstOnly) { if (firstLoud < 0) firstLoud = t; continue; }
                                lastLoud = bi.presentationTimeUs / 1e6 + k1 / (double) rate;
                            }
                        }
                    }
                    dec.releaseOutputBuffer(o, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
                if (!busy) {
                    if (++idle > 400_000) throw new Exception("stuck");
                    Thread.sleep(0, 200_000);
                } else idle = 0;
            }
            return firstOnly ? firstLoud : lastLoud;
        } finally {
            try { dec.stop(); } catch (Exception ignored) {}
            dec.release();
        }
    }
}
