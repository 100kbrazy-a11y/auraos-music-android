#ifndef STRATA_VOICE_H
#define STRATA_VOICE_H

#include <stdint.h>

/// A small, model-free voice finder: it looks at a stereo song, keeps what is sung (the centred, harmonic, non-percussive part) and notes where the
/// voice is and where its syllables begin. Lines of lyrics are then lined up against that.
typedef struct strata_voice strata_voice;

/// Analyses [n] samples of left/right audio at [sample_rate] Hz (22 050 is what Strata uses). Returns NULL when there is too little audio.
strata_voice *strata_voice_analyze(const float *left, const float *right, int64_t n, int sample_rate);

/// How many syllables a word has when sung (English-like spelling; other letters are ignored). Never less than 1.
int strata_voice_syllables(const char *word);

/// Lines one line of lyrics up against the voice.
///   ws, we: the stretch of the song (seconds) the line is somewhere inside; base: the time its stamp says it starts; slack: how long before that it may start;
///   after: when the line before stopped being sung (0 if unknown), so its tail isn't taken for this line's start
///   n_words / syll: how many words, and how many syllables each has; quick: 1 for a word that is sung fast and short (a stutter or a repeated "la la"), else 0 (may be NULL)
///   starts: out, when each word begins (seconds); ends: out (may be NULL), when each word stops being sung (before the next one starts if there is a pause); end: out, when the line stops being sung
/// Returns 0 when no singing was found there, 1 when the words were spread over the singing, 2 when they were matched to syllables.
int strata_voice_align_line(const strata_voice *v, double ws, double we, double after, double base, double slack, int n_words, const int *syll, const int *quick, double *starts, double *ends, double *end);

/// Where the voice comes back in after a pause of at least [min_gap] seconds, nearest to [near] and within [radius] of it. Returns 1 and sets [when], or 0.
int strata_voice_reentry(const strata_voice *v, double near, double radius, double min_gap, double *when);

/// How far off the lyrics' line times are from the singing: pass each line's start time ([n] of them); [used] gets how many lines gave a reading.
/// Returns seconds to ADD to the lyrics' times, or 0 when the lines agree with the singing already (within about 0.12 s) or the readings are too scattered to trust.
double strata_voice_residual(const strata_voice *v, int n, const double *bases, int *used);

/// seconds of song per analysis frame, and the loudness of the voice at [t] seconds (for tests and drawing)
double strata_voice_frame_seconds(const strata_voice *v);
double strata_voice_level(const strata_voice *v, double t);

void strata_voice_free(strata_voice *v);

#endif
