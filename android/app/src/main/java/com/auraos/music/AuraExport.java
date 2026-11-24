package com.auraos.music;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Song export, the parts that need the phone's codecs: decode any song to 16-bit PCM and hand it to a sink (FLAC, WAV), or
 * encode it to Opus / AAC (the same encoders the import compressor uses).
 */
public final class AuraExport {
    public interface Factory { AudioSink open(int sampleRate, int channels) throws IOException; }
    public interface Progress { void onProgress(float fraction); }

    public static boolean canEncodeOpus() { return AuraCompressor.hasOpusEncoder(); }

    /** Opus in an Ogg file (Android 10+) or AAC in an .m4a. */
    public static void encode(File in, File out, boolean opus, int kbps) throws Exception {
        AuraCompressor.encode(in, out, opus, kbps);
    }

    /** Decodes [in] completely; the sink is created when the decoder reports its real sample rate and channel count. */
    public static void decodeTo(File in, Factory factory, Progress progress) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        AudioSink sink = null;
        try {
            ex.setDataSource(in.getPath());
            int track = -1; MediaFormat inF = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; inF = f; break; }
            }
            if (track < 0) throw new Exception("there is no audio in this file");
            ex.selectTrack(track);
            long durUs = inF.containsKey(MediaFormat.KEY_DURATION) ? inF.getLong(MediaFormat.KEY_DURATION) : 0;
            dec = MediaCodec.createDecoderByType(inF.getString(MediaFormat.KEY_MIME));
            dec.configure(inF, null, null, 0);
            dec.start();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int inCh = 0, outCh = 0, pcmEnc = AudioFormat.ENCODING_PCM_16BIT;
            short[] pcm = new short[0]; float[] fl = new float[0]; short[] raw = new short[0];
            int idle = 0;
            while (!outDone) {
                boolean busy = false;
                if (!inDone) {
                    int i = dec.dequeueInputBuffer(4000);
                    if (i >= 0) {
                        busy = true;
                        ByteBuffer b = dec.getInputBuffer(i);
                        int n = b == null ? -1 : ex.readSampleData(b, 0);
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                        else {
                            long t = ex.getSampleTime();
                            dec.queueInputBuffer(i, 0, n, t, 0); ex.advance();
                            if (progress != null && durUs > 0) progress.onProgress(Math.min(1f, (float) t / durUs));
                        }
                    }
                }
                int o = dec.dequeueOutputBuffer(bi, 4000);
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!busy && ++idle > 3000) throw new Exception("the decoder stopped answering"); continue; }
                idle = 0;
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { sink = null; inCh = 0; }
                if (o < 0) continue;
                if (inCh == 0) {
                    MediaFormat f = dec.getOutputFormat();
                    inCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEnc = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    outCh = Math.min(2, Math.max(1, inCh));
                    if (sink == null) sink = factory.open(rate, outCh);
                }
                ByteBuffer ob = dec.getOutputBuffer(o);
                if (ob != null && bi.size > 0 && sink != null) {
                    ob.position(bi.offset); ob.limit(bi.offset + bi.size);
                    ByteBuffer src = ob.slice().order(ByteOrder.nativeOrder());
                    boolean flt = pcmEnc == AudioFormat.ENCODING_PCM_FLOAT;
                    int samples = bi.size / (flt ? 4 : 2), frames = samples / inCh;
                    if (pcm.length < frames * outCh) pcm = new short[frames * outCh];
                    if (flt) {
                        if (fl.length < samples) fl = new float[samples];
                        src.asFloatBuffer().get(fl, 0, samples);
                        for (int k = 0; k < frames; k++) for (int c = 0; c < outCh; c++) {
                            int s = Math.round(fl[k * inCh + c] * 32767f);
                            pcm[k * outCh + c] = (short) (s > 32767 ? 32767 : s < -32768 ? -32768 : s);
                        }
                    } else if (inCh == outCh) {
                        src.asShortBuffer().get(pcm, 0, samples);
                    } else {
                        if (raw.length < samples) raw = new short[samples];
                        src.asShortBuffer().get(raw, 0, samples);
                        for (int k = 0; k < frames; k++) for (int c = 0; c < outCh; c++) pcm[k * outCh + c] = raw[k * inCh + c];
                    }
                    if (frames > 0) sink.write(pcm, frames);
                }
                dec.releaseOutputBuffer(o, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
            }
            if (sink == null) throw new Exception("the file has no playable audio");
            sink.close(); sink = null;
            if (progress != null) progress.onProgress(1f);
        } finally {
            try { if (sink != null) sink.close(); } catch (Exception ignored) {}
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) {}
            ex.release();
        }
    }
}
