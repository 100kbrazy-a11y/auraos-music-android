#include "strata_voice.h"
#include <string.h>
#include <ctype.h>

// How many syllables an English-like word has when sung. Rules of thumb plus a short table of words the rules get wrong.
static const struct { const char *w; int n; } EXC[] = {
    {"every", 2}, {"everyone", 3}, {"everybody", 4}, {"everything", 3}, {"everywhere", 3}, {"everyday", 3},
    {"different", 2}, {"interesting", 3}, {"comfortable", 3}, {"favorite", 3}, {"favourite", 3}, {"evening", 2},
    {"business", 2}, {"beautiful", 3}, {"family", 3}, {"camera", 3}, {"generally", 3}, {"actually", 4}, {"probably", 3},
    {"memory", 3}, {"history", 3}, {"flower", 2}, {"flowers", 2}, {"power", 2}, {"tower", 2}, {"hour", 1}, {"hours", 1},
    {"fire", 1}, {"desire", 2}, {"higher", 2}, {"lion", 2}, {"cruel", 1}, {"real", 1}, {"really", 2}, {"seems", 1}, {"people", 2},
    {"heaven", 2}, {"seven", 2}, {"eleven", 3}, {"even", 2}, {"given", 2}, {"driven", 2}, {"broken", 2}, {"spoken", 2},
    {"yeah", 1}, {"yea", 1}, {"yeahh", 1}, {"whoa", 1}, {"woah", 1}, {"oh", 1}, {"ooh", 1}, {"aah", 1}, {"uh", 1}, {"eh", 1},
    {"ayy", 1}, {"hey", 1}, {"mmm", 1}, {"hmm", 1}, {"aye", 1}, {"the", 1}, {"one", 1}, {"once", 1}, {"were", 1}, {"where", 1},
    {"idea", 3}, {"area", 3}, {"video", 3}, {"radio", 3}, {"piano", 3}, {"violin", 3}, {"diamond", 2}, {"poem", 2}, {"quiet", 2},
    {"science", 2}, {"being", 2}, {"doing", 2}, {"going", 2}, {"seeing", 2}, {"lying", 2}, {"dying", 2}, {"trying", 2}, {"crying", 2},
    {"ruin", 2}, {"fluid", 2}, {"create", 2}, {"creating", 3}, {"ocean", 2}, {"nation", 2}, {"medium", 3}, {"genius", 2},
    {"rhythm", 2}, {"rhythms", 2}, {"prism", 2}, {"chasm", 2}, {"lyrics", 2}, {"bottom", 2}, {"button", 2}, {"gotta", 2}, {"wanna", 2},
    {"gonna", 2}, {"tryna", 2}, {"imma", 2}, {"lemme", 2}, {"gimme", 2}, {"kinda", 2}, {"sorta", 2}, {"cuz", 1}, {"cause", 1}, {"somebody", 3}, {"goodbye", 2}, {"someone", 2}
};

static int is_v(char c, int first, char next) {
    if (c == 'y') return !(first && next && strchr("aeiou", next));      // "yes" and "yeah": the y is a consonant
    return c && strchr("aeiou", c) != NULL;
}

static int groups(const char *w, int len) {
    int g = 0, in = 0;
    for (int i = 0; i < len; i++) {
        int v = is_v(w[i], i == 0, i + 1 < len ? w[i + 1] : 0);
        if (v) { if (!in) g++; in = 1; } else in = 0;
    }
    return g;
}

static int has(const char *w, int len, const char *pat) {      // does the word contain this, counted once per place
    int n = 0, pl = (int)strlen(pat);
    for (int i = 0; i + pl <= len; i++) if (strncmp(w + i, pat, (size_t)pl) == 0) n++;
    return n;
}

int strata_voice_syllables(const char *word) {
    char w[64];
    int len = 0;
    for (const char *p = word; *p && len < 60; p++) {
        unsigned char c = (unsigned char)*p;
        if (c < 128 && isalpha(c)) w[len++] = (char)tolower(c);
    }
    w[len] = 0;
    if (len == 0) return 1;
    // "couldn't", "didn't", "isn't": the n't after a consonant is a beat of its own ("don't", "can't" and "won't" are not)
    int nt = (strstr(word, "n't") || strstr(word, "n\xe2\x80\x99t")) && len >= 4 && !strchr("aeiouy", w[len - 3]);
    // a stretched sound ("heeeey", "noooo") is still one: squeeze runs of three or more of a letter down to one
    char s[64]; int sl = 0;
    for (int i = 0; i < len; i++) {
        int run = 1; while (i + run < len && w[i + run] == w[i]) run++;
        if (run >= 3) { s[sl++] = w[i]; i += run - 1; } else { for (int k = 0; k < run; k++) s[sl++] = w[i]; i += run - 1; }
    }
    s[sl] = 0;
    for (size_t i = 0; i < sizeof(EXC) / sizeof(EXC[0]); i++) if (strcmp(s, EXC[i].w) == 0) return EXC[i].n;
    int n = sl, extra = 0;
    if (n > 4 && strcmp(s + n - 3, "ing") == 0) { n -= 3; extra = 1; }      // "-ing" is always its own beat ("going", "making")
    int g = groups(s, n);
    if (n >= 2 && s[n - 1] == 'e' && g > 1) {
        int le = n >= 3 && s[n - 2] == 'l' && !strchr("aeiouy", s[n - 3]);
        if (!le) g--;                                                          // silent e: "love", "make"
    } else if (n >= 3 && strcmp(s + n - 2, "ed") == 0 && g > 1 && s[n - 3] != 't' && s[n - 3] != 'd') {
        g--;                                                                   // "loved", but not "wanted"
    } else if (n >= 4 && strcmp(s + n - 2, "es") == 0 && g > 1 && !strchr("sxzcg", s[n - 3]) && !(s[n - 3] == 'h' && n >= 4 && strchr("sc", s[n - 4]))) {
        g--;                                                                   // "makes", but not "dances" or "wishes"
    }
    // vowels that sit side by side but are sung apart: "media", "radio" (not "nation"), "usual", "video"
    g += has(s, n, "ia") + has(s, n, "iu") + has(s, n, "eo") + has(s, n, "uo");
    g += has(s, n, "io") - has(s, n, "tio") - has(s, n, "sio") - has(s, n, "cio") - has(s, n, "gio") - has(s, n, "xio");
    g += has(s, n, "ua") - has(s, n, "qua") - has(s, n, "gua");
    g += extra + nt;
    return g < 1 ? 1 : (g > 12 ? 12 : g);
}
