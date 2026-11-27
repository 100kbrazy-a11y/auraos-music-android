package com.auraos.music;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * A small, complete FLAC encoder for 16-bit PCM (mono or stereo, any rate up to 655 kHz): fixed predictors of order 0-4,
 * Rice-coded residuals with the best partitioning, constant and verbatim fallbacks, and the best of the four stereo layouts
 * (left/right, left/side, right/side, mid/side) per block. Writes STREAMINFO (with the MD5 of the audio), a Vorbis comment
 * block for the tags, and a front-cover PICTURE block. The result is a normal FLAC file any player or "flac -t" accepts.
 */
public final class FlacSink implements AudioSink {
    private static final int BLOCK = 4096;
    private final RandomAccessFile f; private final int rate, chn;
    private final MessageDigest md5;
    private final short[] pend; private int pendFrames = 0;
    private long total = 0, frameNo = 0; private int minFrame = Integer.MAX_VALUE, maxFrame = 0;
    private byte[] pcmBytes = new byte[0];

    public FlacSink(File out, int sampleRate, int channels, Map<String, String> tags, byte[] cover) throws IOException {
        if (channels < 1 || channels > 2) throw new IOException("FLAC export handles mono and stereo");
        rate = sampleRate; chn = channels;
        try { md5 = MessageDigest.getInstance("MD5"); } catch (Exception e) { throw new IOException(e); }
        pend = new short[BLOCK * channels];
        f = new RandomAccessFile(out, "rw"); f.setLength(0);
        f.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        byte[] comment = vorbis(tags);
        byte[] pic = cover != null && cover.length > 0 ? picture(cover) : null;
        f.write(0); f.write(0); f.write(0); f.write(34); f.write(streamInfo(0, 0, 0, new byte[16]));     // patched in close()
        block(4, comment, pic == null);
        if (pic != null) block(6, pic, true);
    }

    private void block(int type, byte[] body, boolean last) throws IOException {
        f.write((last ? 0x80 : 0) | type); f.write(body.length >> 16); f.write(body.length >> 8); f.write(body.length); f.write(body);
    }

    private byte[] streamInfo(int minF, int maxF, long totalSamples, byte[] md) {
        BitWriter b = new BitWriter();
        b.write(BLOCK, 16); b.write(BLOCK, 16); b.write(minF, 24); b.write(maxF, 24);
        b.write(rate, 20); b.write(chn - 1, 3); b.write(15, 5); b.write(totalSamples, 36);
        byte[] h = b.toBytes(); byte[] r = new byte[34]; System.arraycopy(h, 0, r, 0, 18); System.arraycopy(md, 0, r, 18, 16);
        return r;
    }

    private static void le32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >>> 24); }
    private static void be32(ByteArrayOutputStream o, int v) { o.write(v >>> 24); o.write(v >> 16); o.write(v >> 8); o.write(v); }

    private static byte[] vorbis(Map<String, String> tags) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] vendor = "Strata Music".getBytes(StandardCharsets.UTF_8);
        le32(o, vendor.length); o.write(vendor);
        List<byte[]> list = new ArrayList<>();
        if (tags != null) for (Map.Entry<String, String> e : tags.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            list.add((e.getKey().toUpperCase(Locale.ROOT) + "=" + e.getValue()).getBytes(StandardCharsets.UTF_8));
        }
        le32(o, list.size()); for (byte[] c : list) { le32(o, c.length); o.write(c); }
        return o.toByteArray();
    }

    private static byte[] picture(byte[] img) {
        boolean png = img.length > 4 && (img[0] & 0xff) == 0x89 && img[1] == 'P';
        byte[] mime = (png ? "image/png" : "image/jpeg").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        be32(o, 3); be32(o, mime.length); o.write(mime, 0, mime.length); be32(o, 0); be32(o, 0); be32(o, 0); be32(o, 0); be32(o, 0);
        be32(o, img.length); o.write(img, 0, img.length);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ input

    @Override public void write(short[] pcm, int frames) throws IOException {
        int pos = 0;
        while (pos < frames) {
            int take = Math.min(BLOCK - pendFrames, frames - pos);
            System.arraycopy(pcm, pos * chn, pend, pendFrames * chn, take * chn);
            pendFrames += take; pos += take;
            if (pendFrames == BLOCK) { encode(pend, BLOCK); pendFrames = 0; }
        }
    }

    @Override public void close() throws IOException {
        if (pendFrames > 0) encode(pend, pendFrames);
        f.seek(8); f.write(streamInfo(minFrame == Integer.MAX_VALUE ? 0 : minFrame, maxFrame, total, md5.digest()));
        f.close();
    }

    // ------------------------------------------------------------------ one frame

    private void encode(short[] src, int n) throws IOException {
        int c = n * chn;
        if (pcmBytes.length < c * 2) pcmBytes = new byte[c * 2];
        for (int i = 0; i < c; i++) { pcmBytes[2 * i] = (byte) src[i]; pcmBytes[2 * i + 1] = (byte) (src[i] >> 8); }
        md5.update(pcmBytes, 0, c * 2);
        int[] l = new int[n], r = new int[n];
        for (int i = 0; i < n; i++) { l[i] = src[i * chn]; if (chn == 2) r[i] = src[i * chn + 1]; }

        BitWriter h = new BitWriter();
        h.write(0xFFF8, 16);
        h.write(n == BLOCK ? 12 : 7, 4); h.write(0, 4);
        BitWriter[] subs; int assign;
        if (chn == 1) { subs = new BitWriter[]{sub(l, n, 16)}; assign = 0; }
        else {
            int[] m = new int[n], s = new int[n];
            for (int i = 0; i < n; i++) { m[i] = (l[i] + r[i]) >> 1; s[i] = l[i] - r[i]; }
            BitWriter bl = sub(l, n, 16), br = sub(r, n, 16), bm = sub(m, n, 16), bs = sub(s, n, 17);
            long lr = bl.bits() + br.bits(), ls = bl.bits() + bs.bits(), rs = br.bits() + bs.bits(), ms = bm.bits() + bs.bits();
            long best = Math.min(Math.min(lr, ls), Math.min(rs, ms));
            if (best == lr) { subs = new BitWriter[]{bl, br}; assign = 1; }
            else if (best == ls) { subs = new BitWriter[]{bl, bs}; assign = 8; }
            else if (best == rs) { subs = new BitWriter[]{bs, br}; assign = 9; }
            else { subs = new BitWriter[]{bm, bs}; assign = 10; }
        }
        h.write(assign, 4); h.write(4, 3); h.write(0, 1);
        utf8(h, frameNo++);
        if (n != BLOCK) h.write(n - 1, 16);
        byte[] hb = h.toBytes();
        BitWriter fr = new BitWriter();
        for (byte b : hb) fr.write(b & 0xff, 8);
        fr.write(crc8(hb), 8);
        for (BitWriter s : subs) fr.append(s);
        fr.align();
        byte[] body = fr.toBytes();
        int crc = crc16(body);
        f.write(body); f.write(crc >> 8); f.write(crc);
        int size = body.length + 2; minFrame = Math.min(minFrame, size); maxFrame = Math.max(maxFrame, size);
        total += n;
    }

    private static void utf8(BitWriter b, long v) {
        if (v < 0x80) { b.write(v, 8); return; }
        int extra = v < 0x800 ? 1 : v < 0x10000 ? 2 : v < 0x200000 ? 3 : v < 0x4000000 ? 4 : 5;
        int lead = (0xFF00 >> (extra + 1)) & 0xFF;
        b.write(lead | (v >> (6 * extra)), 8);
        for (int i = extra - 1; i >= 0; i--) b.write(0x80 | ((v >> (6 * i)) & 0x3F), 8);
    }

    // ------------------------------------------------------------------ one channel

    private static BitWriter sub(int[] x, int n, int bps) {
        BitWriter best;
        boolean constant = true;
        for (int i = 1; i < n; i++) if (x[i] != x[0]) { constant = false; break; }
        if (constant) { BitWriter b = new BitWriter(); b.write(0, 8); b.write(x[0] & ((1L << bps) - 1), bps); return b; }
        // pick the predictor order by the size of its residual
        int bestOrder = 0; long bestSum = Long.MAX_VALUE; int[] bestRes = null;
        for (int o = 0; o <= 4 && o < n; o++) {
            int[] res = residual(x, n, o);
            long sum = 0; for (int i = o; i < n; i++) sum += Math.abs((long) res[i]);
            if (sum < bestSum) { bestSum = sum; bestOrder = o; bestRes = res; }
        }
        best = fixed(x, n, bps, bestOrder, bestRes);
        if (best.bits() >= 8L + (long) n * bps) {                                 // noise: store it as it is
            BitWriter v = new BitWriter(); v.write(2, 8);                         // 0 + 000001 + 0
            for (int i = 0; i < n; i++) v.write(x[i] & ((1L << bps) - 1), bps);
            return v;
        }
        return best;
    }

    private static int[] residual(int[] x, int n, int o) {
        int[] r = new int[n];
        for (int i = o; i < n; i++) {
            switch (o) {
                case 0: r[i] = x[i]; break;
                case 1: r[i] = x[i] - x[i - 1]; break;
                case 2: r[i] = x[i] - 2 * x[i - 1] + x[i - 2]; break;
                case 3: r[i] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]; break;
                default: r[i] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4];
            }
        }
        return r;
    }

    private static BitWriter fixed(int[] x, int n, int bps, int o, int[] res) {
        BitWriter b = new BitWriter();
        b.write(0, 1); b.write(8 | o, 6); b.write(0, 1);
        for (int i = 0; i < o; i++) b.write(x[i] & ((1L << bps) - 1), bps);
        // choose the partition order with the fewest bits
        int bestP = 0; long bestBits = Long.MAX_VALUE; int[] bestK = null;
        for (int p = 0; p <= 8; p++) {
            int parts = 1 << p;
            if ((n % parts) != 0 || (n >> p) <= o) break;
            int[] ks = new int[parts]; long bits = 0; int pos = o;
            for (int i = 0; i < parts; i++) {
                int cnt = (n >> p) - (i == 0 ? o : 0);
                long sum = 0; for (int j = 0; j < cnt; j++) sum += zig(res[pos + j]);
                int k = 0; long mean = cnt == 0 ? 0 : sum / cnt; while (k < 14 && (1L << (k + 1)) <= mean + 1) k++;
                long best = Long.MAX_VALUE; int bk = k;
                for (int kk = Math.max(0, k - 1); kk <= Math.min(14, k + 1); kk++) {
                    long c = 0; for (int j = 0; j < cnt; j++) c += (zig(res[pos + j]) >> kk) + 1 + kk;
                    if (c < best) { best = c; bk = kk; }
                }
                ks[i] = bk; bits += best + 4; pos += cnt;
            }
            if (bits < bestBits) { bestBits = bits; bestP = p; bestK = ks; }
        }
        b.write(0, 2); b.write(bestP, 4);
        int parts = 1 << bestP, pos = o;
        for (int i = 0; i < parts; i++) {
            int cnt = (n >> bestP) - (i == 0 ? o : 0), k = bestK[i];
            b.write(k, 4);
            for (int j = 0; j < cnt; j++) { long u = zig(res[pos + j]); b.unary(u >> k); if (k > 0) b.write(u & ((1L << k) - 1), k); }
            pos += cnt;
        }
        return b;
    }

    private static long zig(int v) { return ((long) v << 1) ^ (v >> 31) & 0xFFFFFFFFL; }

    // ------------------------------------------------------------------ checksums and bits

    private static int crc8(byte[] d) { int c = 0; for (byte x : d) { c ^= x & 0xff; for (int i = 0; i < 8; i++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xff : (c << 1) & 0xff; } return c; }
    private static int crc16(byte[] d) { int c = 0; for (byte x : d) { c ^= (x & 0xff) << 8; for (int i = 0; i < 8; i++) c = (c & 0x8000) != 0 ? ((c << 1) ^ 0x8005) & 0xffff : (c << 1) & 0xffff; } return c; }

    private static final class BitWriter {
        private byte[] buf = new byte[64]; private int len = 0; private long acc = 0; private int nacc = 0;
        long bits() { return len * 8L + nacc; }
        void write(long v, int n) {
            while (n > 0) {
                int take = Math.min(n, 32);
                long part = (v >>> (n - take)) & ((1L << take) - 1);
                acc = (acc << take) | part; nacc += take; n -= take;
                while (nacc >= 8) { put((byte) (acc >>> (nacc - 8))); nacc -= 8; }
                acc &= (1L << nacc) - 1;
            }
        }
        void unary(long q) { while (q >= 32) { write(0, 32); q -= 32; } write(1, (int) q + 1); }
        private void put(byte b) { if (len == buf.length) buf = Arrays.copyOf(buf, len * 2); buf[len++] = b; }
        void align() { if (nacc > 0) write(0, 8 - nacc); }
        void append(BitWriter o) { for (int i = 0; i < o.len; i++) write(o.buf[i] & 0xff, 8); if (o.nacc > 0) write(o.acc, o.nacc); }
        byte[] toBytes() { BitWriter c = new BitWriter(); c.buf = Arrays.copyOf(buf, Math.max(len, 1)); c.len = len; c.acc = acc; c.nacc = nacc; c.align(); return Arrays.copyOf(c.buf, c.len); }
    }
}
