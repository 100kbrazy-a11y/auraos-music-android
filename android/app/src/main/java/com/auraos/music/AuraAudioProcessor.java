package com.auraos.music;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Runs inside Android's own audio pipeline (ExoPlayer), on 16-bit PCM:
 *  - the 10-band equalizer (same bands, filter shapes and presets as the app's old web EQ)
 *  - a gentle peak limiter, only when the EQ is boosting (so nothing ever clips)
 *  - the visualizer's spectrum (a 2048-point FFT, ~30 times a second, only while the visualizer is on screen),
 *    delayed by the audio buffer so the bars move with what you hear, not what's about to be heard.
 */
@OptIn(markerClass = UnstableApi.class)
public final class AuraAudioProcessor extends BaseAudioProcessor {
    // ---- settings shared by both players (the second one is for crossfades)
    static final double[] BANDS = { 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };
    private static volatile float[] gainsDb = new float[10];
    private static volatile float preGain = 1f;
    private static volatile int version = 1;
    static volatile boolean viz = false;

    static void setEq(float[] db, float pre) {
        float[] g = new float[10];
        for (int i = 0; i < 10 && i < db.length; i++) g[i] = db[i];
        gainsDb = g;
        preGain = pre;
        version++;
    }

    private int sr, ch, seen = -1;
    private int nb;                         // filters in use
    private double[] b0, b1, b2, a1, a2;    // normalised coefficients per filter in use
    private double[] st;                    // state: [filter][channel] → x1, x2, y1, y2
    private boolean flat = true;
    private float pre = 1f;
    private double env = 0, rel = 0.999;
    private float[] frame = new float[2];

    // ---- spectrum
    private static final int N = 2048;
    private static final double[] WIN = new double[N], COS = new double[N / 2], SIN = new double[N / 2];
    private static final int[] REV = new int[N];
    static {
        for (int i = 0; i < N; i++) {
            double a = 2 * Math.PI * i / N;
            WIN[i] = 0.42 - 0.5 * Math.cos(a) + 0.08 * Math.cos(2 * a);     // Blackman, like the web's analyser
        }
        for (int i = 0; i < N / 2; i++) { COS[i] = Math.cos(2 * Math.PI * i / N); SIN[i] = -Math.sin(2 * Math.PI * i / N); }
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) REV[i] = Integer.reverse(i) >>> (32 - bits);
    }
    private final float[] ring = new float[N];
    private int ringPos = 0, sinceSnap = 0;
    private final double[] re = new double[N], im = new double[N], smooth = new double[N / 2];
    static final int OUT = 512;                      // bins sent to the app (it only looks at the lower ones)
    private static final int SNAPS = 24;
    private final byte[][] snaps = new byte[SNAPS][OUT];
    private final long[] snapAt = new long[SNAPS];
    private int snapHead = 0, snapCount = 0;
    private long leadNs = 300_000_000L;

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat in) {
        if (in.encoding != C.ENCODING_PCM_16BIT || in.channelCount < 1) return AudioProcessor.AudioFormat.NOT_SET;   // (left alone)
        sr = in.sampleRate;
        ch = in.channelCount;
        frame = new float[ch];
        seen = -1;
        rel = Math.exp(-1.0 / (0.12 * sr));          // limiter release ≈ 120 ms
        // how far ahead of the speaker the audio is processed: the sink's buffer (what ExoPlayer sizes it to)
        int mask = ch == 1 ? android.media.AudioFormat.CHANNEL_OUT_MONO : android.media.AudioFormat.CHANNEL_OUT_STEREO;
        int min = android.media.AudioTrack.getMinBufferSize(sr, mask, android.media.AudioFormat.ENCODING_PCM_16BIT);
        double ms = min > 0 ? 4.0 * min / (2 * Math.min(ch, 2)) * 1000.0 / sr : 250;
        ms = Math.max(250, Math.min(750, ms));
        leadNs = (long) ((ms + 25) * 1_000_000L);
        return in;
    }

    private void design() {
        seen = version;
        float[] g = gainsDb;
        pre = preGain;
        int n = 0;
        double[] B0 = new double[10], B1 = new double[10], B2 = new double[10], A1 = new double[10], A2 = new double[10];
        for (int i = 0; i < 10; i++) {
            double f = BANDS[i], gain = g[i];
            if (Math.abs(gain) < 0.05 || f >= sr * 0.45) continue;
            double A = Math.pow(10, gain / 40), w = 2 * Math.PI * f / sr, cs = Math.cos(w), sn = Math.sin(w);
            double b0_, b1_, b2_, a0_, a1_, a2_;
            if (i == 0 || i == 9) {                    // shelves (slope 1, as the web's BiquadFilter)
                double alpha = sn / 2 * Math.sqrt(2), sa = 2 * Math.sqrt(A) * alpha;
                if (i == 0) {
                    b0_ = A * ((A + 1) - (A - 1) * cs + sa); b1_ = 2 * A * ((A - 1) - (A + 1) * cs); b2_ = A * ((A + 1) - (A - 1) * cs - sa);
                    a0_ = (A + 1) + (A - 1) * cs + sa; a1_ = -2 * ((A - 1) + (A + 1) * cs); a2_ = (A + 1) + (A - 1) * cs - sa;
                } else {
                    b0_ = A * ((A + 1) + (A - 1) * cs + sa); b1_ = -2 * A * ((A - 1) + (A + 1) * cs); b2_ = A * ((A + 1) + (A - 1) * cs - sa);
                    a0_ = (A + 1) - (A - 1) * cs + sa; a1_ = 2 * ((A - 1) - (A + 1) * cs); a2_ = (A + 1) - (A - 1) * cs - sa;
                }
            } else {                                   // peaking, Q 1.1
                double alpha = sn / (2 * 1.1);
                b0_ = 1 + alpha * A; b1_ = -2 * cs; b2_ = 1 - alpha * A;
                a0_ = 1 + alpha / A; a1_ = -2 * cs; a2_ = 1 - alpha / A;
            }
            B0[n] = b0_ / a0_; B1[n] = b1_ / a0_; B2[n] = b2_ / a0_; A1[n] = a1_ / a0_; A2[n] = a2_ / a0_;
            n++;
        }
        b0 = B0; b1 = B1; b2 = B2; a1 = A1; a2 = A2;
        if (st == null || st.length != 10 * ch * 4 || n != nb) st = new double[10 * ch * 4];
        nb = n;
        flat = n == 0 && Math.abs(pre - 1f) < 0.001f;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int pos = input.position(), lim = input.limit(), size = lim - pos;
        if (size <= 0) return;
        if (seen != version) design();
        ByteBuffer out = replaceOutputBuffer(size);
        boolean doViz = viz;
        if (flat && !doViz) {                          // nothing to do: pass it straight through
            out.put(input);
            out.flip();
            return;
        }
        ByteOrder o = input.order();
        int frames = size / (2 * ch);
        for (int f = 0; f < frames; f++) {
            double peak = 0, mono = 0;
            for (int c = 0; c < ch; c++) {
                double x = input.getShort(pos + (f * ch + c) * 2) / 32768.0;
                if (!flat) {
                    for (int k = 0; k < nb; k++) {
                        int s = (k * ch + c) * 4;
                        double y = b0[k] * x + b1[k] * st[s] + b2[k] * st[s + 1] - a1[k] * st[s + 2] - a2[k] * st[s + 3];
                        st[s + 1] = st[s]; st[s] = x; st[s + 3] = st[s + 2]; st[s + 2] = y;
                        x = y;
                    }
                    x *= pre;
                }
                frame[c] = (float) x;
                double ax = Math.abs(x);
                if (ax > peak) peak = ax;
                mono += x;
            }
            double gain = 1;
            if (!flat) {                               // limiter: instant attack, smooth release, ceiling ≈ -1 dB
                env = peak > env ? peak : env * rel + peak * (1 - rel);
                if (env > 0.89) gain = 0.89 / env;
            }
            for (int c = 0; c < ch; c++) {
                double v = frame[c] * gain * 32767.0;
                out.putShort((short) (v > 32767 ? 32767 : v < -32768 ? -32768 : v));
            }
            if (doViz) {
                ring[ringPos] = (float) (mono / ch * gain);
                ringPos = (ringPos + 1) & (N - 1);
                if (++sinceSnap >= sr / 30) { sinceSnap = 0; snapshot(); }
            }
        }
        input.position(lim);
        out.order(o);
        out.flip();
    }

    private void snapshot() {
        for (int i = 0; i < N; i++) { re[REV[i]] = ring[(ringPos + i) & (N - 1)] * WIN[i]; }
        java.util.Arrays.fill(im, 0);
        for (int len = 2; len <= N; len <<= 1) {
            int half = len >> 1, step = N / len;
            for (int i = 0; i < N; i += len) {
                for (int j = 0; j < half; j++) {
                    double wr = COS[j * step], wi = SIN[j * step];
                    int a = i + j, b = a + half;
                    double tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                }
            }
        }
        byte[] out = new byte[OUT];
        for (int k = 0; k < OUT; k++) {
            double mag = Math.sqrt(re[k] * re[k] + im[k] * im[k]) / N;
            smooth[k] = 0.72 * smooth[k] + 0.28 * mag;
            double db = 20 * Math.log10(smooth[k] + 1e-12);
            int v = (int) Math.round((db + 100) / 70 * 255);
            out[k] = (byte) (v < 0 ? 0 : v > 255 ? 255 : v);
        }
        synchronized (snaps) {
            snaps[snapHead] = out;
            snapAt[snapHead] = System.nanoTime();
            snapHead = (snapHead + 1) % SNAPS;
            if (snapCount < SNAPS) snapCount++;
        }
    }

    /** The spectrum of what's coming out of the speaker right now (512 bytes, like the web analyser's lower bins). */
    byte[] levels() {
        long want = System.nanoTime() - leadNs;
        synchronized (snaps) {
            byte[] best = null;
            for (int i = 1; i <= snapCount; i++) {         // newest first
                int idx = (snapHead - i + SNAPS) % SNAPS;
                best = snaps[idx];
                if (snapAt[idx] <= want) break;
            }
            return best != null ? best : new byte[OUT];
        }
    }

    @Override
    protected void onFlush() {
        if (st != null) java.util.Arrays.fill(st, 0);
        env = 0;
    }

    @Override
    protected void onReset() {
        st = null;
        seen = -1;
        env = 0;
        synchronized (snaps) { snapCount = 0; }
        java.util.Arrays.fill(smooth, 0);
    }
}
