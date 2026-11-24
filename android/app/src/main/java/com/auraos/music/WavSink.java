package com.auraos.music;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 16-bit PCM WAV with a LIST/INFO chunk for the title, artist and album. */
public final class WavSink implements AudioSink {
    private final RandomAccessFile f; private final int channels; private long dataBytes = 0; private final long dataStart;
    private byte[] buf = new byte[0];

    public WavSink(File out, int sampleRate, int channels, Map<String, String> tags) throws IOException {
        this.channels = channels;
        f = new RandomAccessFile(out, "rw"); f.setLength(0);
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        if (tags != null) {
            put(info, "INAM", tags.get("title")); put(info, "IART", tags.get("artist")); put(info, "IPRD", tags.get("album"));
            put(info, "IGNR", tags.get("genre")); put(info, "ICRD", tags.get("year")); put(info, "ITRK", tags.get("tracknumber"));
        }
        byte[] list = info.toByteArray();
        f.write(new byte[12]);                                             // RIFF header, patched in close()
        f.write("fmt ".getBytes(StandardCharsets.US_ASCII)); le32(16); le16(1); le16(channels); le32(sampleRate);
        le32(sampleRate * channels * 2); le16(channels * 2); le16(16);
        if (list.length > 0) { f.write("LIST".getBytes(StandardCharsets.US_ASCII)); le32(4 + list.length); f.write("INFO".getBytes(StandardCharsets.US_ASCII)); f.write(list); }
        f.write("data".getBytes(StandardCharsets.US_ASCII)); le32(0);
        dataStart = f.getFilePointer();
    }

    private static void put(ByteArrayOutputStream o, String id, String v) throws IOException {
        if (v == null || v.isEmpty()) return;
        byte[] b = v.getBytes(StandardCharsets.UTF_8);
        int n = b.length + 1;                                              // zero-terminated, padded to an even length
        o.write(id.getBytes(StandardCharsets.US_ASCII));
        o.write(n); o.write(n >> 8); o.write(n >> 16); o.write(n >> 24);
        o.write(b); o.write(0); if ((n & 1) != 0) o.write(0);
    }
    private void le16(int v) throws IOException { f.write(v & 0xff); f.write((v >> 8) & 0xff); }
    private void le32(int v) throws IOException { le16(v); le16(v >>> 16); }

    @Override public void write(short[] pcm, int frames) throws IOException {
        int n = frames * channels;
        if (buf.length < n * 2) buf = new byte[n * 2];
        for (int i = 0; i < n; i++) { buf[2 * i] = (byte) pcm[i]; buf[2 * i + 1] = (byte) (pcm[i] >> 8); }
        f.write(buf, 0, n * 2); dataBytes += n * 2L;
    }

    @Override public void close() throws IOException {
        if ((dataBytes & 1) != 0) f.write(0);
        long end = f.getFilePointer();
        f.seek(0); f.write("RIFF".getBytes(StandardCharsets.US_ASCII)); le32((int) Math.min(0xFFFFFFFFL, end - 8)); f.write("WAVE".getBytes(StandardCharsets.US_ASCII));
        f.seek(dataStart - 4); le32((int) Math.min(0xFFFFFFFFL, dataBytes));
        f.close();
    }
}
