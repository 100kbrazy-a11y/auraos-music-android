package com.auraos.music;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the audio YouTube hands out (Opus inside a WebM file) into a real Ogg Opus file (.opus) without touching the sound:
 * the Opus packets are copied as they are, so nothing is re-encoded and nothing is lost. Tags (title, artist, album...) and a
 * cover picture can be written into the new file. Plain Java so it can be tested off the phone.
 */
public final class WebmOpus {
    private WebmOpus() {}

    private static final int EBML = 0x1A45DFA3, SEGMENT = 0x18538067, TRACKS = 0x1654AE6B, TRACK_ENTRY = 0xAE, TRACK_NUMBER = 0xD7,
            CODEC_ID = 0x86, CODEC_PRIVATE = 0x63A2, CLUSTER = 0x1F43B675, SIMPLE_BLOCK = 0xA3, BLOCK_GROUP = 0xA0, BLOCK = 0xA1;

    /** true when the file starts like a WebM / Matroska file */
    public static boolean isWebm(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            return r.length() > 4 && r.readInt() == EBML;
        } catch (IOException e) {
            return false;
        }
    }

    /** true when the file is WebM and its audio is Opus (so it can be re-wrapped losslessly) */
    public static boolean hasOpus(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r"); FileChannel ch = r.getChannel()) {
            if (r.length() < 4 || r.readInt() != EBML) return false;
            ByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            return parse(b, null).head != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Re-wraps the Opus audio of a WebM file as Ogg Opus.
     *
     * @param tags  KEY to value (TITLE, ARTIST, ALBUM, ALBUMARTIST, DATE, GENRE, TRACKNUMBER, DISCNUMBER...), may be null
     * @param cover a JPEG or PNG picture to put inside the file, may be null
     */
    public static void toOgg(File in, File out, Map<String, String> tags, byte[] cover) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(in, "r"); FileChannel ch = r.getChannel();
             RandomAccessFile o = new RandomAccessFile(out, "rw")) {
            o.setLength(0);
            ByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            final List<int[]> packets = new ArrayList<>();      // {offset, length}
            Parsed p = parse(b, packets);
            if (p.head == null) throw new IOException("This file has no Opus audio");
            if (packets.isEmpty()) throw new IOException("This file has no audio in it");
            OggOut ogg = new OggOut(new FileOut(o), 0x53747261);
            int preSkip = (p.head[10] & 0xff) | ((p.head[11] & 0xff) << 8);
            ogg.header(p.head, true);
            ogg.header(opusTags(tags, cover), false);
            long granule = preSkip;
            // the last packet usually carries padding that the WebM file says to cut (DiscardPadding); Ogg says it with the last granule
            long trim = Math.max(0, p.trimNs * 48 / 1_000_000);
            for (int i = 0; i < packets.size(); i++) {
                int[] pk = packets.get(i);
                byte[] data = new byte[pk[1]];
                b.position(pk[0]); b.get(data);
                int d = duration48k(data);
                granule += d;
                boolean last = i == packets.size() - 1;
                ogg.audio(data, last ? granule - Math.min(trim, d) : granule, last);
            }
            ogg.finish();
        }
    }

    // ------------------------------------------------------------------ WebM reading

    private static final int DISCARD_PADDING = 0x75A2;
    private static final class Parsed { byte[] head; long trimNs; }

    /** a flat walk: containers are entered, everything else is skipped, so unknown-size (live) files work too */
    private static Parsed parse(ByteBuffer b, List<int[]> packets) throws IOException {
        Parsed res = new Parsed();
        int pos = 0, end = b.limit();
        long curTrack = -1, opusTrack = -1; boolean curIsOpus = false; byte[] curPrivate = null;
        while (pos + 2 <= end) {
            long[] idv = vint(b, pos, true);
            if (idv == null) break;
            int idLen = (int) idv[1]; int id = (int) idv[0];
            long[] szv = vint(b, pos + idLen, false);
            if (szv == null) break;
            int hdr = idLen + (int) szv[1];
            long size = szv[0];                                   // -1 = unknown
            int body = pos + hdr;
            if (id == SEGMENT || id == TRACKS || id == CLUSTER || id == BLOCK_GROUP) { pos = body; continue; }
            if (id == TRACK_ENTRY) {
                if (curIsOpus && curPrivate != null && res.head == null) { res.head = curPrivate; opusTrack = curTrack; }
                curTrack = -1; curIsOpus = false; curPrivate = null; pos = body; continue;
            }
            if (size < 0) { pos = body; continue; }
            if (body + size > end) size = end - body;
            int n = (int) size;
            if (id == TRACK_NUMBER) { curTrack = uint(b, body, n); }
            else if (id == CODEC_ID) { curIsOpus = "A_OPUS".equals(new String(bytes(b, body, n), StandardCharsets.US_ASCII).trim()); }
            else if (id == CODEC_PRIVATE) { curPrivate = bytes(b, body, n); }
            else if (id == DISCARD_PADDING) { long v = 0; for (int i = 0; i < n && i < 8; i++) v = (v << 8) | (b.get(body + i) & 0xff); if (n > 0 && n < 8 && (b.get(body) & 0x80) != 0) v -= 1L << (8 * n); res.trimNs = v; }   // signed nanoseconds cut from the end of the last block
            else if (id == SIMPLE_BLOCK || id == BLOCK) {
                if (curIsOpus && curPrivate != null && res.head == null) { res.head = curPrivate; opusTrack = curTrack; }   // last TrackEntry
                if (packets == null) { if (res.head != null) return res; }
                else {
                    long[] tv = vint(b, body, false);
                    if (tv != null && tv[0] == opusTrack) {
                        int off = body + (int) tv[1] + 2;
                        int flags = b.get(off) & 0xff; off++;
                        if ((flags & 0x06) != 0) throw new IOException("This WebM file packs several frames together, which isn't supported");
                        int len = body + n - off;
                        if (len > 0) packets.add(new int[]{off, len});
                    }
                }
            }
            pos = body + n;
        }
        if (res.head == null && curIsOpus && curPrivate != null) res.head = curPrivate;
        if (res.head != null && (res.head.length < 19 || res.head[0] != 'O' || res.head[1] != 'p' || res.head[2] != 'u' || res.head[3] != 's'))
            res.head = null;
        return res;
    }

    /** EBML variable-size integer: {value, length}; for ids the length marker stays in the value; size -1 means "unknown" */
    private static long[] vint(ByteBuffer b, int pos, boolean keepMarker) {
        if (pos >= b.limit()) return null;
        int first = b.get(pos) & 0xff;
        if (first == 0) return null;
        int len = Integer.numberOfLeadingZeros(first) - 24 + 1;
        if (pos + len > b.limit()) return null;
        long v = keepMarker ? first : (first & (0xff >> len));
        boolean allOnes = (first & (0xff >> len)) == (0xff >> len);
        for (int i = 1; i < len; i++) { int x = b.get(pos + i) & 0xff; v = (v << 8) | x; if (x != 0xff) allOnes = false; }
        if (!keepMarker && allOnes) v = -1;
        return new long[]{v, len};
    }

    private static long uint(ByteBuffer b, int pos, int n) { long v = 0; for (int i = 0; i < n && i < 8; i++) v = (v << 8) | (b.get(pos + i) & 0xff); return v; }

    private static byte[] bytes(ByteBuffer b, int pos, int n) { byte[] a = new byte[n]; for (int i = 0; i < n; i++) a[i] = b.get(pos + i); return a; }

    /** how many 48 kHz samples an Opus packet holds, from its first bytes (RFC 6716 section 3.1) */
    static int duration48k(byte[] p) {
        if (p.length == 0) return 0;
        int toc = p[0] & 0xff, cfg = toc >> 3, code = toc & 3, fs;
        if (cfg < 12) fs = new int[]{480, 960, 1920, 2880}[cfg & 3];
        else if (cfg < 16) fs = (cfg & 1) == 0 ? 480 : 960;
        else fs = new int[]{120, 240, 480, 960}[cfg & 3];
        int frames = code == 0 ? 1 : (code == 3 ? (p.length > 1 ? (p[1] & 0x3f) : 1) : 2);
        return fs * frames;
    }

    // ------------------------------------------------------------------ tags

    private static byte[] opusTags(Map<String, String> tags, byte[] cover) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Map<String, String> t = new LinkedHashMap<>();
        if (tags != null) for (Map.Entry<String, String> e : tags.entrySet()) if (e.getValue() != null && !e.getValue().isEmpty()) t.put(e.getKey(), e.getValue());
        if (cover != null && cover.length > 0) t.put("METADATA_BLOCK_PICTURE", base64(picture(cover)));
        byte[] vendor = "Strata Music".getBytes(StandardCharsets.UTF_8);
        write(o, "OpusTags".getBytes(StandardCharsets.US_ASCII));
        le32(o, vendor.length); write(o, vendor);
        le32(o, t.size());
        for (Map.Entry<String, String> e : t.entrySet()) {
            byte[] c = (e.getKey() + "=" + e.getValue()).getBytes(StandardCharsets.UTF_8);
            le32(o, c.length); write(o, c);
        }
        return o.toByteArray();
    }

    /** a FLAC "picture" block (type 3, front cover), the form Ogg comments and FLAC files both use */
    static byte[] picture(byte[] img) {
        boolean png = img.length > 4 && (img[0] & 0xff) == 0x89 && img[1] == 'P';
        byte[] mime = (png ? "image/png" : "image/jpeg").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        be32(o, 3); be32(o, mime.length); write(o, mime); be32(o, 0);
        be32(o, 0); be32(o, 0); be32(o, 24); be32(o, 0);
        be32(o, img.length); write(o, img);
        return o.toByteArray();
    }

    static String base64(byte[] d) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder s = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int v = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            s.append(A.charAt(v >> 18 & 63)).append(A.charAt(v >> 12 & 63));
            s.append(i + 1 < d.length ? A.charAt(v >> 6 & 63) : '=').append(i + 2 < d.length ? A.charAt(v & 63) : '=');
        }
        return s.toString();
    }

    private static void write(ByteArrayOutputStream o, byte[] b) { o.write(b, 0, b.length); }
    private static void le32(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24); }
    private static void be32(ByteArrayOutputStream o, int v) { o.write(v >> 24); o.write(v >> 16); o.write(v >> 8); o.write(v); }

    // ------------------------------------------------------------------ Ogg writing

    private interface Sink { void write(byte[] b, int off, int len) throws IOException; }

    private static final class FileOut implements Sink {
        final RandomAccessFile f; FileOut(RandomAccessFile f) { this.f = f; }
        public void write(byte[] b, int off, int len) throws IOException { f.write(b, off, len); }
    }

    private static final int[] CRC = new int[256];
    static {
        for (int i = 0; i < 256; i++) { int r = i << 24; for (int k = 0; k < 8; k++) r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1; CRC[i] = r; }
    }

    private static final class OggOut {
        final Sink out; final int serial; int seq = 0;
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final List<Integer> lacing = new ArrayList<>();
        long lastGranule = 0; boolean started = false;

        OggOut(Sink out, int serial) { this.out = out; this.serial = serial; }

        /** a header packet gets pages of its own (a big cover picture is spread over several pages) */
        void header(byte[] pkt, boolean first) throws IOException {
            List<Integer> lac = new ArrayList<>();
            for (int n = pkt.length; ; n -= 255) { if (n >= 255) lac.add(255); else { lac.add(n); break; } }
            int at = 0, i = 0; boolean cont = false;
            while (i < lac.size()) {
                int cnt = Math.min(255, lac.size() - i); int bytes = 0;
                for (int k = 0; k < cnt; k++) bytes += lac.get(i + k);
                boolean last = i + cnt >= lac.size();
                byte[] seg = new byte[cnt]; for (int k = 0; k < cnt; k++) seg[k] = (byte) (int) lac.get(i + k);
                page((first && i == 0 ? 0x02 : 0) | (cont ? 0x01 : 0), last ? 0 : -1L, seg, pkt, at, bytes);
                at += bytes; i += cnt; cont = true;
            }
        }

        void audio(byte[] pkt, long granuleAfter, boolean last) throws IOException {
            int need = pkt.length / 255 + 1;
            if (lacing.size() + need > 255 || (body.size() > 6000 && !lacing.isEmpty())) flush(false);
            for (int n = pkt.length; ; n -= 255) { if (n >= 255) lacing.add(255); else { lacing.add(n); break; } }
            body.write(pkt, 0, pkt.length);
            lastGranule = granuleAfter;
            if (last) flush(true);
        }

        void flush(boolean eos) throws IOException {
            if (lacing.isEmpty()) return;
            byte[] seg = new byte[lacing.size()]; for (int k = 0; k < seg.length; k++) seg[k] = (byte) (int) lacing.get(k);
            byte[] data = body.toByteArray();
            page(eos ? 0x04 : 0, lastGranule, seg, data, 0, data.length);
            lacing.clear(); body.reset();
        }

        void finish() throws IOException { flush(true); }

        void page(int flags, long granule, byte[] seg, byte[] data, int off, int len) throws IOException {
            byte[] p = new byte[27 + seg.length + len];
            p[0] = 'O'; p[1] = 'g'; p[2] = 'g'; p[3] = 'S'; p[4] = 0; p[5] = (byte) flags;
            ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putLong(6, granule).putInt(14, serial).putInt(18, seq++);
            p[26] = (byte) seg.length;
            System.arraycopy(seg, 0, p, 27, seg.length);
            System.arraycopy(data, off, p, 27 + seg.length, len);
            int crc = 0;
            for (byte x : p) crc = (crc << 8) ^ CRC[((crc >>> 24) ^ (x & 0xff)) & 0xff];
            ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc);
            out.write(p, 0, p.length);
        }
    }
}
