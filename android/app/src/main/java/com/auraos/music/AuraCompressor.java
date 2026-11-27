package com.auraos.music;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Re-encodes a song to Opus (in an Ogg file) with the phone's own decoders and encoder; phones without an Opus
 * encoder (before Android 10) get AAC in an .m4a instead. Built for speed: samples move in bulk, nothing is
 * reallocated per buffer, the codecs are never left waiting, and several songs can run at once (one per thread).
 */
final class AuraCompressor {
    static final class Result {
        String file;
        long size;
        String codec;
        String mime;
    }

    private static volatile Boolean opusOk = null;
    static boolean hasOpusEncoder() {
        if (opusOk != null) return opusOk;
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (!ci.isEncoder()) continue;
                    for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(MediaFormat.MIMETYPE_AUDIO_OPUS)) ok = true;
                }
            } catch (Exception ignored) {}
        }
        opusOk = ok;
        return ok;
    }

    /** base: the output path without extension. Tries Opus, falls back to AAC. */
    static Result run(File in, String base, int opusKbps) throws Exception {
        if (hasOpusEncoder()) {
            File out = new File(base + ".opus");
            try {
                return encode(in, out, true, opusKbps);
            } catch (Exception e) {
                out.delete();
            }
        }
        File out = new File(base + ".m4a");
        try {
            return encode(in, out, false, Math.max(128, Math.min(256, Math.round(opusKbps * 1.6f))));
        } catch (Exception e) {
            out.delete();
            throw e;
        }
    }

    /** interleaved 16-bit samples waiting for the encoder (the read position moves; compacted only now and then) */
    private static final class Fifo {
        short[] a = new short[1 << 17];
        int start = 0, end = 0;
        int size() { return end - start; }
        void ensure(int more) {
            if (end + more <= a.length) return;
            int n = end - start;
            if (n + more <= a.length && start > 0) { System.arraycopy(a, start, a, 0, n); start = 0; end = n; return; }
            short[] b = new short[Math.max(a.length * 2, n + more)];
            System.arraycopy(a, start, b, 0, n);
            a = b; start = 0; end = n;
        }
        void push(float[] f, int n) {
            ensure(n);
            for (int i = 0; i < n; i++) {
                int s = (int) (f[i] * 32767f + (f[i] >= 0 ? 0.5f : -0.5f));
                a[end++] = (short) (s > 32767 ? 32767 : s < -32768 ? -32768 : s);
            }
        }
    }

    static Result encode(File in, File out, boolean opus, int kbps) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        boolean muxStarted = false;
        try {
            ex.setDataSource(in.getPath());
            int track = -1;
            MediaFormat inF = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; inF = f; break; }
            }
            if (track < 0) throw new Exception("no audio in the file");
            ex.selectTrack(track);
            dec = MediaCodec.createDecoderByType(inF.getString(MediaFormat.KEY_MIME));
            dec.configure(inF, null, null, 0);
            dec.start();
            mux = new MediaMuxer(out.getPath(), opus ? MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo di = new MediaCodec.BufferInfo(), ei = new MediaCodec.BufferInfo();
            boolean inDone = false, decDone = false, encInEos = false, encDone = false;
            int inRate, inCh = 0, outRate = 0, outCh = 0, pcmEnc = AudioFormat.ENCODING_PCM_16BIT;
            Resampler rs = null;
            Fifo fifo = new Fifo();
            short[] sIn = new short[0];
            float[] fIn = new float[0], fRaw = new float[0];
            long framesFed = 0;
            int muxTrack = -1;
            long idle = 0;

            while (!encDone) {
                boolean busy = false;
                // 1) file → decoder (as many buffers as it will take)
                while (!inDone) {
                    int i = dec.dequeueInputBuffer(0);
                    if (i < 0) break;
                    ByteBuffer b = dec.getInputBuffer(i);
                    int n = b == null ? -1 : ex.readSampleData(b, 0);
                    if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                    else { dec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    busy = true;
                }
                // 2) decoder → PCM (converted / resampled only when needed) → fifo
                while (!decDone && fifo.size() < (1 << 18)) {
                    int o = dec.dequeueOutputBuffer(di, 0);
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    busy = true;
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (o >= 0 && enc == null)) {
                        if (enc == null) {
                            MediaFormat f = dec.getOutputFormat();
                            inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEnc = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                            outCh = Math.min(2, Math.max(1, inCh));
                            outRate = opus ? 48000 : (inRate <= 48000 ? inRate : 48000);
                            if (outRate != inRate) rs = new Resampler(inRate, outRate, outCh);
                            String mime = opus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
                            MediaFormat ef = MediaFormat.createAudioFormat(mime, outRate, outCh);
                            ef.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
                            if (opus) ef.setInteger(MediaFormat.KEY_COMPLEXITY, 5);     // (10 is the default: twice the work, no audible gain here)
                            else ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
                            enc = MediaCodec.createEncoderByType(mime);
                            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                            enc.start();
                        }
                        if (o < 0) continue;
                    }
                    if (o < 0) continue;
                    ByteBuffer ob = dec.getOutputBuffer(o);
                    if (ob != null && di.size > 0) {
                        ob.position(di.offset);
                        ob.limit(di.offset + di.size);
                        ByteBuffer src = ob.slice().order(ByteOrder.nativeOrder());
                        boolean flt = pcmEnc == AudioFormat.ENCODING_PCM_FLOAT;
                        int samples = di.size / (flt ? 4 : 2), frames = samples / inCh;
                        if (!flt && rs == null && inCh == outCh) {
                            // the common case (16-bit, same rate, same channels): straight into the fifo, no conversion
                            fifo.ensure(samples);
                            src.asShortBuffer().get(fifo.a, fifo.end, samples);
                            fifo.end += samples;
                        } else {
                            if (fIn.length < frames * outCh) fIn = new float[frames * outCh];
                            if (flt) {
                                if (fRaw.length < samples) fRaw = new float[samples];
                                src.asFloatBuffer().get(fRaw, 0, samples);
                                for (int k = 0; k < frames; k++) for (int c = 0; c < outCh; c++) fIn[k * outCh + c] = fRaw[k * inCh + c];
                            } else {
                                if (sIn.length < samples) sIn = new short[samples];
                                src.asShortBuffer().get(sIn, 0, samples);
                                for (int k = 0; k < frames; k++) for (int c = 0; c < outCh; c++) fIn[k * outCh + c] = sIn[k * inCh + c] * (1f / 32768f);
                            }
                            if (rs != null) { int n = rs.process(fIn, frames * outCh, false); fifo.push(rs.out, n); }
                            else fifo.push(fIn, frames * outCh);
                        }
                    }
                    dec.releaseOutputBuffer(o, false);
                    if ((di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decDone = true;
                        if (rs != null) { int n = rs.process(fIn, 0, true); fifo.push(rs.out, n); }
                    }
                }
                if (enc == null) {
                    if (decDone) throw new Exception("the file has no playable audio");
                    if (!busy) { if (++idle > 400_000) throw new Exception("stuck"); Thread.sleep(0, 200_000); }
                    continue;
                }
                // 3) fifo → encoder
                while (!encInEos && (fifo.size() > 0 || decDone)) {
                    int i = enc.dequeueInputBuffer(0);
                    if (i < 0) break;
                    busy = true;
                    ByteBuffer eb = enc.getInputBuffer(i);
                    eb.clear();
                    eb.order(ByteOrder.nativeOrder());
                    int cap = (eb.remaining() / 2) / outCh * outCh;
                    int n = Math.min(cap, fifo.size());
                    ShortBuffer sb = eb.asShortBuffer();
                    sb.put(fifo.a, fifo.start, n);
                    fifo.start += n;
                    if (fifo.start == fifo.end) fifo.start = fifo.end = 0;
                    long pts = framesFed * 1_000_000L / outRate;
                    framesFed += n / outCh;
                    boolean last = decDone && fifo.size() == 0;
                    enc.queueInputBuffer(i, 0, n * 2, pts, last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    if (last) encInEos = true;
                }
                // 4) encoder → file
                while (true) {
                    int o = enc.dequeueOutputBuffer(ei, 0);
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    busy = true;
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = mux.addTrack(enc.getOutputFormat());
                        mux.start();
                        muxStarted = true;
                        continue;
                    }
                    if (o < 0) continue;
                    ByteBuffer d = enc.getOutputBuffer(o);
                    if ((ei.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0;
                    if (d != null && ei.size > 0 && muxStarted) {
                        d.position(ei.offset);
                        d.limit(ei.offset + ei.size);
                        mux.writeSampleData(muxTrack, d, ei);
                    }
                    enc.releaseOutputBuffer(o, false);
                    if ((ei.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { encDone = true; break; }
                }
                // nothing moved this round: the codecs are busy; wait a fraction of a millisecond, not a whole tick
                if (!busy) {
                    if (++idle > 400_000) throw new Exception("stuck");
                    Thread.sleep(0, 250_000);
                } else idle = 0;
            }
            if (!muxStarted || framesFed == 0) throw new Exception("nothing was encoded");
            mux.stop();
            mux.release();
            mux = null;
            if (out.length() < 1024) throw new Exception("the compressed file came out empty");
            Result r = new Result();
            r.file = out.getName();
            r.size = out.length();
            r.codec = opus ? "Opus · " + kbps + " kbps" : "AAC · " + kbps + " kbps";
            r.mime = opus ? "audio/ogg" : "audio/mp4";
            return r;
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) {}
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (Exception ignored) {}
            try { if (mux != null) { if (muxStarted) mux.stop(); mux.release(); } } catch (Exception ignored) {}
            ex.release();
        }
    }

    /**
     * Windowed-sinc (Blackman) polyphase resampler, streaming. 12 zero-crossings each side: clean (no audible
     * aliasing or imaging) and quick. One growing buffer; nothing is reallocated per call.
     */
    static final class Resampler {
        private final int ch, half, taps, P = 256;
        private final double step;
        private final float[] table;          // [phase][tap], flattened
        private float[] buf;                   // interleaved input not yet used up
        private int bufFrames;
        private double pos;
        float[] out = new float[1 << 15];

        Resampler(int inRate, int outRate, int ch) {
            this.ch = ch;
            step = inRate / (double) outRate;
            double fc = Math.min(1.0, outRate / (double) inRate) * 0.93;
            half = (int) Math.ceil(12 / fc);
            taps = 2 * half;
            table = new float[(P + 1) * taps];
            for (int p = 0; p <= P; p++) {
                double frac = p / (double) P, sum = 0;
                for (int j = 0; j < taps; j++) {
                    double x = (j - half + 1) - frac;
                    double s = x == 0 ? 1 : Math.sin(Math.PI * fc * x) / (Math.PI * fc * x);
                    double w = Math.abs(x) >= half ? 0 : 0.42 + 0.5 * Math.cos(Math.PI * x / half) + 0.08 * Math.cos(2 * Math.PI * x / half);
                    table[p * taps + j] = (float) (s * w);
                    sum += s * w;
                }
                for (int j = 0; j < taps; j++) table[p * taps + j] /= (float) sum;
            }
            bufFrames = half - 1;                     // silence before the first sample
            buf = new float[Math.max(1 << 16, bufFrames * ch * 2)];
            pos = half - 1;
        }

        /** feeds n interleaved samples; returns how many interleaved samples were written into `out` */
        int process(float[] in, int n, boolean last) {
            int inFrames = n / ch, pad = last ? half + 1 : 0;
            int need = (bufFrames + inFrames + pad) * ch;
            if (need > buf.length) { float[] b = new float[Math.max(need, buf.length * 2)]; System.arraycopy(buf, 0, b, 0, bufFrames * ch); buf = b; }
            System.arraycopy(in, 0, buf, bufFrames * ch, n);
            if (pad > 0) java.util.Arrays.fill(buf, (bufFrames + inFrames) * ch, need, 0f);
            bufFrames += inFrames + pad;
            int maxOut = ((int) ((bufFrames - pos) / step) + 2) * ch;
            if (out.length < maxOut) out = new float[maxOut];
            int w = 0;
            final float[] t = table, b = buf;
            while (true) {
                int i = (int) pos;
                if (i + half >= bufFrames) break;
                int start = i - half + 1;
                if (start < 0) { pos += step; continue; }
                int row = (int) ((pos - i) * P + 0.5) * taps;
                if (ch == 2) {                         // stereo: both channels in one pass over the filter
                    float l = 0, r = 0;
                    int idx = start * 2;
                    for (int j = 0; j < taps; j++, idx += 2) { float k = t[row + j]; l += b[idx] * k; r += b[idx + 1] * k; }
                    out[w++] = l; out[w++] = r;
                } else {
                    for (int c = 0; c < ch; c++) {
                        float acc = 0;
                        int idx = start * ch + c;
                        for (int j = 0; j < taps; j++, idx += ch) acc += b[idx] * t[row + j];
                        out[w++] = acc;
                    }
                }
                pos += step;
            }
            int keepFrom = Math.max(0, (int) pos - half + 1);
            if (keepFrom > 0) {
                int left = bufFrames - keepFrom;
                System.arraycopy(buf, keepFrom * ch, buf, 0, left * ch);
                bufFrames = left;
                pos -= keepFrom;
            }
            return w;
        }
    }
}
