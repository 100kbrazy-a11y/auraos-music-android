package com.auraos.music;

import java.io.IOException;

/** Where decoded audio goes when a song is exported: 16-bit interleaved PCM in, a finished file out. */
public interface AudioSink {
    /** @param pcm interleaved 16-bit samples; @param frames how many frames (samples per channel) of it to use */
    void write(short[] pcm, int frames) throws IOException;
    void close() throws IOException;
}
