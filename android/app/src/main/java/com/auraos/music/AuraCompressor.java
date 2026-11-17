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

/**
 * Re-encodes a song to Opus 160 kbps in an Ogg file (near-lossless to the ear, a fraction of the size), using the
 * phone's own decoders and encoder — a few seconds per song. Phones without an Opus encoder (before Android 10)
 * get AAC 256 kbps in an .m4a instead.
 */
final class AuraCompressor {
    static final class Result {
        String file;      // the output's name (opus / m4a)
        long size;
        String codec;
        String mime;
    }

    private static boolean hasEncoder(String mime) {
        try {
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** base: the output path without extension. Tries Opus, falls back to AAC. */
    static Result run(File in, String base, int opusKbps) throws Exception {
        boolean opus = Build.VERSION.SDK_INT >= 29 && hasEncoder(MediaFormat.MIMETYPE_AUDIO_OPUS);
        if (opus) {
            File out = new File(base + ".opus");
            try {
                return encode(in, out, true, opusKbps);
            } catch (Exception e) {
                out.delete();                    // (falls through to AAC)
            }
        }
        File out = new File(base + ".m4a");
        try {
            return encode(in, out, false, Math.max(128, Math.min(256, Math.round(opusKbps * 1.6f))));   // (AAC needs a bit more for the same quality)
        } catch (Exception e) {
            out.delete();
            throw e;
        }
    }

    private static Result encode(File in, File out, boolean opus, int kbps) throws Exception {
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
            int inRate = 0, inCh = 0, outRate = 0, outCh = 0, pcmEnc = AudioFormat.ENCODING_PCM_16BIT;
            Resampler rs = null;
            short[] fifo = new short[1 << 16];
            int fifoLen = 0;                               // samples (interleaved) waiting for the encoder
            long framesFed = 0;
            int muxTrack = -1;
            long guard = 0;

            while (!encDone) {
                if (++guard > 50_000_000L) throw new Exception("stuck");
                // 1) file → decoder
                if (!inDone) {
                    int i = dec.dequeueInputBuffer(2000);
                    if (i >= 0) {
                        ByteBuffer b = dec.getInputBuffer(i);
                        int n = b == null ? -1 : ex.readSampleData(b, 0);
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else { dec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                // 2) decoder → PCM (resampled when needed) → fifo
                if (!decDone && fifoLen < fifo.length / 2) {
                    int o = dec.dequeueOutputBuffer(di, 2000);
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (o >= 0 && enc == null)) {
                        MediaFormat f = dec.getOutputFormat();
                        if (enc == null) {
                            inRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEnc = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                            outCh = Math.min(2, Math.max(1, inCh));
                            outRate = opus ? 48000 : (inRate <= 48000 ? inRate : 48000);
                            if (outRate != inRate) rs = new Resampler(inRate, outRate, outCh);
                            MediaFormat ef = MediaFormat.createAudioFormat(opus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC, outRate, outCh);
                            ef.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
                            if (!opus) ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
                            enc = MediaCodec.createEncoderByType(opus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC);
                            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                            enc.start();
                        }
                    }
                    if (o >= 0) {
                        ByteBuffer ob = dec.getOutputBuffer(o);
                        if (ob != null && di.size > 0) {
                            ob.position(di.offset);
                            ob.limit(di.offset + di.size);
                            ob.order(ByteOrder.nativeOrder());
                            int bps = pcmEnc == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                            int frames = di.size / (bps * inCh);
                            float[] f = new float[frames * outCh];
                            for (int k = 0; k < frames; k++) {
                                for (int c = 0; c < outCh; c++) {
                                    int idx = di.offset + (k * inCh + c) * bps;
                                    f[k * outCh + c] = bps == 4 ? ob.getFloat(idx) : ob.getShort(idx) / 32768f;
                                }
                            }
                            if (rs != null) f = rs.process(f, false);
                            if (fifoLen + f.length > fifo.length) {
                                short[] g = new short[Math.max(fifo.length * 2, fifoLen + f.length)];
                                System.arraycopy(fifo, 0, g, 0, fifoLen);
                                fifo = g;
                            }
                            for (float v : f) {
                                int s = Math.round(v * 32767f);
                                fifo[fifoLen++] = (short) (s > 32767 ? 32767 : s < -32768 ? -32768 : s);
                            }
                        }
                        dec.releaseOutputBuffer(o, false);
                        if ((di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decDone = true;
                            if (rs != null) {
                                float[] tail = rs.process(new float[0], true);
                                if (fifoLen + tail.length > fifo.length) {
                                    short[] g = new short[fifoLen + tail.length];
                                    System.arraycopy(fifo, 0, g, 0, fifoLen);
                                    fifo = g;
                                }
                                for (float v : tail) { int s = Math.round(v * 32767f); fifo[fifoLen++] = (short) (s > 32767 ? 32767 : s < -32768 ? -32768 : s); }
                            }
                        }
                    }
                }
                if (enc == null) {
                    if (decDone) throw new Exception("the file has no playable audio");
                    continue;
                }
                // 3) fifo → encoder
                while (!encInEos && (fifoLen > 0 || decDone)) {
                    int i = enc.dequeueInputBuffer(decDone ? 2000 : 0);
                    if (i < 0) break;
                    ByteBuffer eb = enc.getInputBuffer(i);
                    eb.clear();
                    eb.order(ByteOrder.nativeOrder());
                    int capSamples = (eb.remaining() / 2) / outCh * outCh;
                    int n = Math.min(capSamples, fifoLen);
                    for (int k = 0; k < n; k++) eb.putShort(fifo[k]);
                    System.arraycopy(fifo, n, fifo, 0, fifoLen - n);
                    fifoLen -= n;
                    long pts = framesFed * 1_000_000L / outRate;
                    framesFed += n / outCh;
                    boolean last = decDone && fifoLen == 0;
                    enc.queueInputBuffer(i, 0, n * 2, pts, last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    if (last) encInEos = true;
                }
                // 4) encoder → file
                while (true) {
                    int o = enc.dequeueOutputBuffer(ei, encInEos ? 2000 : 0);
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break;
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

    /** Windowed-sinc (Blackman) polyphase resampler: clean, no audible aliasing or imaging; streams in pieces. */
    static final class Resampler {
        private final int ch, half, P = 512;
        private final double step;
        private final float[][] table;
        private float[] buf = new float[0];      // interleaved input not yet used up
        private int bufFrames = 0;
        private double pos;                       // position of the next output, in frames of buf

        Resampler(int inRate, int outRate, int ch) {
            this.ch = ch;
            step = inRate / (double) outRate;
            double fc = Math.min(1.0, outRate / (double) inRate) * 0.94;   // cut a little below Nyquist
            half = (int) Math.ceil(16 / fc);
            table = new float[P + 1][2 * half];
            for (int p = 0; p <= P; p++) {
                double frac = p / (double) P, sum = 0;
                for (int j = 0; j < 2 * half; j++) {
                    double x = (j - half + 1) - frac;            // distance from the output point, in input samples
                    double s = x == 0 ? 1 : Math.sin(Math.PI * fc * x) / (Math.PI * fc * x);
                    double w = Math.abs(x) >= half ? 0 : 0.42 + 0.5 * Math.cos(Math.PI * x / half) + 0.08 * Math.cos(2 * Math.PI * x / half);
                    table[p][j] = (float) (s * w);
                    sum += s * w;
                }
                for (int j = 0; j < 2 * half; j++) table[p][j] /= (float) sum;
            }
            // start with silence before the first sample so the very beginning is filtered too
            bufFrames = half - 1;
            buf = new float[bufFrames * ch];
            pos = half - 1;
        }

        float[] process(float[] in, boolean last) {
            int inFrames = in.length / ch;
            int pad = last ? half + 1 : 0;
            float[] nb = new float[(bufFrames + inFrames + pad) * ch];
            System.arraycopy(buf, 0, nb, 0, bufFrames * ch);
            System.arraycopy(in, 0, nb, bufFrames * ch, in.length);
            buf = nb;
            bufFrames += inFrames + pad;
            int maxOut = (int) ((bufFrames - pos) / step) + 2;
            float[] out = new float[Math.max(0, maxOut) * ch];
            int n = 0;
            while (true) {
                int i = (int) Math.floor(pos);
                if (i + half >= bufFrames) break;
                double frac = pos - i;
                float[] t = table[(int) Math.round(frac * P)];
                int start = i - half + 1;
                if (start < 0) { pos += step; continue; }
                for (int c = 0; c < ch; c++) {
                    float acc = 0;
                    int idx = start * ch + c;
                    for (int j = 0; j < 2 * half; j++, idx += ch) acc += buf[idx] * t[j];
                    out[n * ch + c] = acc;
                }
                n++;
                pos += step;
            }
            // keep only what future outputs still need
            int keepFrom = Math.max(0, (int) Math.floor(pos) - half + 1);
            if (keepFrom > 0) {
                int left = bufFrames - keepFrom;
                float[] kb = new float[left * ch];
                System.arraycopy(buf, keepFrom * ch, kb, 0, left * ch);
                buf = kb;
                bufFrames = left;
                pos -= keepFrom;
            }
            if (n * ch == out.length) return out;
            float[] r = new float[n * ch];
            System.arraycopy(out, 0, r, 0, n * ch);
            return r;
        }
    }
}
