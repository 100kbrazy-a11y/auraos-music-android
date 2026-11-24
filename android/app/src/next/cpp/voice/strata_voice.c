#include "strata_voice.h"
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#include <math.h>
#include <stdlib.h>
#include <string.h>
#ifdef STRATA_DEBUG
#include <stdio.h>
#endif

#define NFFT 1024
#define HOP 512
#define KLO 5            // first bin kept (about 108 Hz at 22 050 Hz)
#define KHI 325          // last bin kept (about 7 kHz)
#define NB (KHI - KLO + 1)
#define TWIN 7           // time median: 15 frames (about 0.35 s)
#define FWIN 7           // frequency median: 15 bins (about 320 Hz)
#define MAXSYL 400
#define MAXCAND 600

struct strata_voice {
    int frames;
    double sr;
    double frame_s;      // seconds per frame
    double centre_s;     // seconds from the start of a frame to its middle
    float *level;        // loudness of the voice band, dB, lightly smoothed
    float *flux;         // how fast the voice is getting louder, summed over frequency
    float thr;           // above this level there is singing (a rough, song-wide guess)
    unsigned char *voiced;   // 1 where the voice is singing, judged against what is playing around it at that moment
};

typedef struct { float re, im; } cpx;

static void fft(cpx *x, const cpx *tw, const int *rev) {
    for (int i = 0; i < NFFT; i++) { int j = rev[i]; if (j > i) { cpx t = x[i]; x[i] = x[j]; x[j] = t; } }
    for (int len = 2; len <= NFFT; len <<= 1) {
        int half = len >> 1, step = NFFT / len;
        for (int i = 0; i < NFFT; i += len) {
            for (int k = 0; k < half; k++) {
                cpx w = tw[k * step], a = x[i + k], b = x[i + k + half];
                cpx t = { b.re * w.re - b.im * w.im, b.re * w.im + b.im * w.re };
                x[i + k] = (cpx){ a.re + t.re, a.im + t.im };
                x[i + k + half] = (cpx){ a.re - t.re, a.im - t.im };
            }
        }
    }
}

static int cmpf(const void *a, const void *b) { float x = *(const float *)a, y = *(const float *)b; return x < y ? -1 : (x > y); }

/// median of 15 values (insertion sort; the window is tiny)
static float median15(const float *v) {
    float a[15];
    for (int i = 0; i < 15; i++) {
        float x = v[i]; int j = i;
        while (j > 0 && a[j - 1] > x) { a[j] = a[j - 1]; j--; }
        a[j] = x;
    }
    return a[7];
}

void strata_voice_free(strata_voice *v) {
    if (!v) return;
    free(v->level); free(v->flux); free(v->voiced); free(v);
}

double strata_voice_frame_seconds(const strata_voice *v) { return v->frame_s; }

static double time_of(const strata_voice *v, int t) { return t * v->frame_s + v->centre_s; }
static int frame_of(const strata_voice *v, double sec) {
    int t = (int)floor((sec - v->centre_s) / v->frame_s + 0.5);
    return t < 0 ? 0 : (t >= v->frames ? v->frames - 1 : t);
}

double strata_voice_level(const strata_voice *v, double t) { return v->level[frame_of(v, t)]; }

strata_voice *strata_voice_analyze(const float *left, const float *right, int64_t n, int sample_rate) {
    if (n < NFFT * 8 || sample_rate < 8000) return NULL;
    int T = (int)((n - NFFT) / HOP) + 1;
    float *mag = malloc(sizeof(float) * (size_t)T * NB);
    float *sim = malloc(sizeof(float) * (size_t)T * NB);
    float *vm = malloc(sizeof(float) * (size_t)T * NB);
    strata_voice *v = calloc(1, sizeof(strata_voice));
    cpx *buf = malloc(sizeof(cpx) * NFFT), *tw = malloc(sizeof(cpx) * NFFT / 2);
    int *rev = malloc(sizeof(int) * NFFT);
    float *win = malloc(sizeof(float) * NFFT);
    if (!mag || !sim || !vm || !v || !buf || !tw || !rev || !win) goto fail;
    v->level = malloc(sizeof(float) * T);
    v->flux = malloc(sizeof(float) * T);
    if (!v->level || !v->flux) goto fail;
    v->frames = T; v->sr = sample_rate; v->frame_s = (double)HOP / sample_rate; v->centre_s = 0.5 * NFFT / sample_rate;
    for (int i = 0; i < NFFT / 2; i++) { double a = -2 * M_PI * i / NFFT; tw[i] = (cpx){ (float)cos(a), (float)sin(a) }; }
    int bits = 0; while ((1 << bits) < NFFT) bits++;
    for (int i = 0; i < NFFT; i++) { int r = 0; for (int b = 0; b < bits; b++) if (i & (1 << b)) r |= 1 << (bits - 1 - b); rev[i] = r; }
    for (int i = 0; i < NFFT; i++) win[i] = (float)(0.5 - 0.5 * cos(2 * M_PI * i / NFFT));

    // 1. the spectrum of the middle of the picture, and how alike left and right are at each frequency (1 = dead centre)
    for (int t = 0; t < T; t++) {
        const float *l = left + (int64_t)t * HOP, *r = right + (int64_t)t * HOP;
        for (int i = 0; i < NFFT; i++) buf[i] = (cpx){ l[i] * win[i], r[i] * win[i] };
        fft(buf, tw, rev);
        for (int k = KLO; k <= KHI; k++) {
            cpx a = buf[k], b = buf[(NFFT - k) % NFFT];
            b.im = -b.im;                                   // conj of the mirror bin
            float lr = 0.5f * (a.re + b.re), li = 0.5f * (a.im + b.im);       // left spectrum
            float rr = 0.5f * (a.im - b.im), ri = -0.5f * (a.re - b.re);      // right spectrum
            float pl = lr * lr + li * li, pr = rr * rr + ri * ri;
            float cross = lr * rr + li * ri;
            float s = 2 * cross / (pl + pr + 1e-9f);
            if (s < 0) s = 0;
            if (s > 1) s = 1;
            float mr = 0.5f * (lr + rr), mi = 0.5f * (li + ri);
            size_t o = (size_t)t * NB + (k - KLO);
            mag[o] = sqrtf(mr * mr + mi * mi);
            sim[o] = s;
        }
    }

    // 2. keep what is steady in time (voices, held notes) and drop what is a sudden wideband hit (drums); keep what is in the middle
    for (int t = 0; t < T; t++) {
        for (int k = 0; k < NB; k++) {
            float tv[15], fv[15];
            for (int i = -TWIN; i <= TWIN; i++) { int tt = t + i; tt = tt < 0 ? 0 : (tt >= T ? T - 1 : tt); tv[i + TWIN] = mag[(size_t)tt * NB + k]; }
            for (int i = -FWIN; i <= FWIN; i++) { int kk = k + i; kk = kk < 0 ? 0 : (kk >= NB ? NB - 1 : kk); fv[i + FWIN] = mag[(size_t)t * NB + kk]; }
            float h = median15(tv), p = median15(fv);
            float mh = (h * h) / (h * h + p * p + 1e-12f);
            float s = sim[(size_t)t * NB + k];
            vm[(size_t)t * NB + k] = mag[(size_t)t * NB + k] * mh * s * s;
        }
    }

    // 3. how loud the voice is (300 Hz to 3.4 kHz), and how fast it swells (150 Hz to 5 kHz)
    double binhz = (double)sample_rate / NFFT;
    int b0 = (int)(300 / binhz) - KLO, b1 = (int)(3400 / binhz) - KLO;
    int f0 = (int)(150 / binhz) - KLO, f1 = (int)(5000 / binhz) - KLO;
    if (b0 < 0) b0 = 0;
    if (f0 < 0) f0 = 0;
    if (b1 >= NB) b1 = NB - 1;
    if (f1 >= NB) f1 = NB - 1;
    float *raw = malloc(sizeof(float) * T);
    if (!raw) goto fail;
    for (int t = 0; t < T; t++) {
        double e = 0;
        for (int k = b0; k <= b1; k++) { float x = vm[(size_t)t * NB + k]; e += (double)x * x; }
        raw[t] = (float)(10 * log10(e + 1e-6));
        double fl = 0;
        if (t > 0) for (int k = f0; k <= f1; k++) {
            double d = log1p(0.05 * vm[(size_t)t * NB + k]) - log1p(0.05 * vm[(size_t)(t - 1) * NB + k]);
            if (d > 0) fl += d;
        }
        v->flux[t] = (float)fl;
    }
    for (int t = 0; t < T; t++) {            // a light smoothing (1 2 1)
        float a = raw[t > 0 ? t - 1 : 0], b = raw[t], c = raw[t + 1 < T ? t + 1 : T - 1];
        v->level[t] = 0.25f * a + 0.5f * b + 0.25f * c;
    }
    {   // where singing is: well above the quiet parts of the song
        float *sorted = malloc(sizeof(float) * T);
        if (!sorted) { free(raw); goto fail; }
        memcpy(sorted, v->level, sizeof(float) * T);
        qsort(sorted, T, sizeof(float), cmpf);
        float lo = sorted[(int)(0.15 * (T - 1))], hi = sorted[(int)(0.92 * (T - 1))];
        v->thr = lo + 0.33f * (hi - lo);
        free(sorted);
    }
    {   // singing or not, decided locally: in a loud chorus the band plays all the time, so "loud" means little. What matters is how far the voice stands above what is under it right now.
        v->voiced = malloc((size_t)T);
        unsigned char *bin = malloc((size_t)T);
        enum { NBIN = 256 };
        int *cnt = calloc(NBIN, sizeof(int));
        if (!v->voiced || !bin || !cnt) { free(bin); free(cnt); free(raw); goto fail; }
        float lmin = v->level[0], lmax = v->level[0];
        for (int t = 1; t < T; t++) { if (v->level[t] < lmin) lmin = v->level[t]; if (v->level[t] > lmax) lmax = v->level[t]; }
        const float bw = (lmax - lmin) / (NBIN - 1) + 1e-6f;
        for (int t = 0; t < T; t++) { int b = (int)((v->level[t] - lmin) / bw); bin[t] = (unsigned char)(b < 0 ? 0 : (b > NBIN - 1 ? NBIN - 1 : b)); }
        const int H = (int)(5.0 / v->frame_s + 0.5);
        int in = 0;
        for (int t = 0; t <= H && t < T; t++) { cnt[bin[t]]++; in++; }
        for (int t = 0; t < T; t++) {
            if (t > 0) {
                int add = t + H, rem = t - H - 1;
                if (add < T) { cnt[bin[add]]++; in++; }
                if (rem >= 0) { cnt[bin[rem]]--; in--; }
            }
            int tl = (int)(0.08 * in), th = (int)(0.92 * in), acc = 0, blo = -1, bhi = NBIN - 1;
            for (int b = 0; b < NBIN; b++) { acc += cnt[b]; if (blo < 0 && acc > tl) blo = b; if (acc > th) { bhi = b; break; } }
            float lo = lmin + (blo < 0 ? 0 : blo) * bw, hi = lmin + bhi * bw;
            float thr = (hi - lo >= 5.0f) ? lo + 0.4f * (hi - lo) : v->thr;
            v->voiced[t] = v->level[t] > thr;
        }
        free(bin); free(cnt);
        // a gap of under 90 ms inside the singing is a consonant or a breath, not a pause; a blip of under 70 ms is not singing
        for (int t = 0; t < T; ) {
            if (v->voiced[t]) { t++; continue; }
            int u = t; while (u < T && !v->voiced[u]) u++;
            if (t > 0 && u < T && u - t < 4) memset(v->voiced + t, 1, (size_t)(u - t));
            t = u;
        }
        for (int t = 0; t < T; ) {
            if (!v->voiced[t]) { t++; continue; }
            int u = t; while (u < T && v->voiced[u]) u++;
            if (u - t < 3) memset(v->voiced + t, 0, (size_t)(u - t));
            t = u;
        }
    }
    free(raw);
    free(mag); free(sim); free(vm); free(buf); free(tw); free(rev); free(win);
    return v;
fail:
    free(mag); free(sim); free(vm); free(buf); free(tw); free(rev); free(win);
    strata_voice_free(v);
    return NULL;
}

// Syllable starts inside the singing [s, e]: places where the voice swells (a new note or word), and loudness peaks taken back to where they started to swell.
// A sensitive pass (smaller steps count, peaks closer together) is for lines with stutters ("eh eh everyone") where the normal pass finds too few.
static int gather(const strata_voice *v, int s, int e, int sensitive, double *cand, double *strength, int cap) {
    int T = v->frames, Nc = 0;
    const float fluxFrac = sensitive ? 0.09f : 0.2f, promMin = sensitive ? 0.6f : 1.2f;
    const int radius = sensitive ? 1 : 2;
    const double merge = sensitive ? 0.045 : 0.07;
    float fmax = 1e-6f;
    for (int t = s; t <= e; t++) if (v->flux[t] > fmax) fmax = v->flux[t];
    for (int t = s; t <= e && Nc < cap - 1; t++) {            // swells
        float x = v->flux[t];
        if (x < fluxFrac * fmax) continue;
        int ok = 1;
        for (int d = 1; d <= radius; d++) {
            int a = t - d < 0 ? 0 : t - d, b = t + d >= T ? T - 1 : t + d;
            if (v->flux[a] > x || v->flux[b] > x) { ok = 0; break; }
        }
        if (!ok) continue;
        cand[Nc] = time_of(v, t) - 0.5 * v->frame_s; strength[Nc] = x / fmax > 1 ? 1 : x / fmax; Nc++;
    }
    for (int t = s; t <= e && Nc < cap - 1; t++) {            // loudness peaks
        float x = v->level[t];
        int ok = 1;
        for (int d = 1; d <= radius; d++) {
            int a = t - d < 0 ? 0 : t - d, b = t + d >= T ? T - 1 : t + d;
            if (v->level[a] > x || v->level[b] > x) { ok = 0; break; }
        }
        if (!ok) continue;
        float lmin = x, rmin = x;
        int vt = t;
        for (int d = 1; d <= 6; d++) {
            int a = t - d < 0 ? 0 : t - d, b = t + d >= T ? T - 1 : t + d;
            if (v->level[a] < lmin) { lmin = v->level[a]; vt = a; }
            if (v->level[b] < rmin) rmin = v->level[b];
        }
        float prom = x - (lmin > rmin ? lmin : rmin);
        if (prom < promMin) continue;
        int on = vt; float best = -1;
        for (int u = vt; u <= t; u++) if (v->flux[u] > best) { best = v->flux[u]; on = u; }
        cand[Nc] = time_of(v, on) - 0.5 * v->frame_s; strength[Nc] = prom / 8 > 1 ? 1 : prom / 8; Nc++;
    }
    for (int i = 1; i < Nc; i++) {                              // in time order
        double ct = cand[i], cs = strength[i]; int j = i;
        while (j > 0 && cand[j - 1] > ct) { cand[j] = cand[j - 1]; strength[j] = strength[j - 1]; j--; }
        cand[j] = ct; strength[j] = cs;
    }
    int m = 0;                                                  // two starts closer than [merge] are one
    for (int i = 0; i < Nc; i++) {
        if (m > 0 && cand[i] - cand[m - 1] < merge) { if (strength[i] > strength[m - 1]) { cand[m - 1] = cand[i]; strength[m - 1] = strength[i]; } continue; }
        cand[m] = cand[i]; strength[m] = strength[i]; m++;
    }
    return m;
}

// Lines the syllables up with the starts found: a gap-allowing match that likes starts near where the syllables are expected (by their weights).
static int match(int Ns, const double *cand, const double *strength, int Nc, const double *exp_t, double scale, double *tsyl, int *matched) {
    int nmatch = 0;
    if (Nc <= 0) return 0;
    size_t W = (size_t)Nc + 1;
    float *F = malloc(sizeof(float) * (size_t)(Ns + 1) * W);
    unsigned char *B = malloc((size_t)(Ns + 1) * W);
    if (F && B) {
        const float skipS = 1.2f;
        F[0] = 0; B[0] = 0;
        for (int c = 1; c <= Nc; c++) { F[c] = F[c - 1] + 0.4f + 0.3f * (float)strength[c - 1]; B[c] = 2; }
        for (int j = 1; j <= Ns; j++) {
            F[(size_t)j * W] = F[(size_t)(j - 1) * W] + skipS; B[(size_t)j * W] = 1;
            double ej = exp_t[j - 1];
            for (int c = 1; c <= Nc; c++) {
                double d = (cand[c - 1] - ej) / scale;
                float m = F[(size_t)(j - 1) * W + (c - 1)] + (float)(d * d - 0.6 * strength[c - 1]);
                float a = F[(size_t)(j - 1) * W + c] + skipS;
                float b = F[(size_t)j * W + (c - 1)] + 0.4f + 0.3f * (float)strength[c - 1];
                float best = m; unsigned char how = 0;
                if (a < best) { best = a; how = 1; }
                if (b < best) { best = b; how = 2; }
                F[(size_t)j * W + c] = best; B[(size_t)j * W + c] = how;
            }
        }
        int j = Ns, c = Nc;
        while (j > 0 && c > 0) {
            unsigned char how = B[(size_t)j * W + c];
            if (how == 0) { tsyl[j - 1] = cand[c - 1]; matched[j - 1] = 1; nmatch++; j--; c--; }
            else if (how == 1) j--;
            else c--;
        }
    }
    free(F); free(B);
    return nmatch;
}

// where the voice comes back in after a pause of at least [min_gap] seconds, nearest to [near] and within [radius] of it
int strata_voice_reentry(const strata_voice *v, double near, double radius, double min_gap, double *when) {
    if (!v) return 0;
    int a = frame_of(v, near - radius), b = frame_of(v, near + radius);
    int need = (int)ceil(min_gap / v->frame_s);
    double best = 0; int found = 0;
    for (int t = a + 1; t <= b; t++) {
        if (!v->voiced[t] || v->voiced[t - 1]) continue;
        int g = 0;
        for (int u = t - 1; u >= 0 && !v->voiced[u]; u--) g++;
        if (g < need) continue;
        double w = time_of(v, t) - 0.5 * v->frame_s;
        if (!found || fabs(w - near) < fabs(best - near)) { best = w; found = 1; }
    }
    if (found && when) *when = best;
    return found;
}

static int cmpd(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : (x > y ? 1 : 0); }

// How far off the lyrics' times are from the singing. For each line start, the nearest place the voice comes back in after a pause says where the line
// really begins; the typical difference, if it is steady, is the answer (seconds to ADD to the lyrics' times; 0 when it is small or can't be trusted).
double strata_voice_residual(const strata_voice *v, int n, const double *bases, int *used) {
    double d[512];
    int m = 0;
    for (int i = 0; i < n && m < 512; i++) {
        double w;
        if (strata_voice_reentry(v, bases[i], 1.0, 0.12, &w)) { double x = w - bases[i]; if (fabs(x) < 0.9) d[m++] = x; }
    }
    if (used) *used = m;
    if (m < 4 || m < n / 4) return 0;
    qsort(d, (size_t)m, sizeof(double), cmpd);
    double med = (m % 2) ? d[m / 2] : 0.5 * (d[m / 2 - 1] + d[m / 2]);
    double dev[512];
    for (int i = 0; i < m; i++) dev[i] = fabs(d[i] - med);
    qsort(dev, (size_t)m, sizeof(double), cmpd);
    double mad = (m % 2) ? dev[m / 2] : 0.5 * (dev[m / 2 - 1] + dev[m / 2]);
    if (mad > 0.3) return 0;
    return fabs(med) >= 0.12 ? med : 0;
}

int strata_voice_align_line(const strata_voice *v, double ws, double we, double after, double base, double slack, int n_words, const int *syll, const int *quick, double *starts, double *ends, double *end) {
    if (!v || n_words <= 0 || we <= ws) return 0;
    // the line before was still being sung until [after]: its tail is not where this one starts
    int w0 = frame_of(v, after > 0 && after + 0.04 > ws ? after + 0.04 : ws), w1 = frame_of(v, we);
    if (w1 - w0 < 8) return 0;

    // where the line is sung: the first steady stretch of voice in the window, to the last
    int s = -1;
    for (int t = w0; t < w1 - 1; t++) if (v->voiced[t] && v->voiced[t + 1]) { s = t; break; }
    if (s < 0) return 0;
    if (s <= w0 + 1) { int b = frame_of(v, base - 0.1); if (b > s && b < w1 - 6) s = b; }   // singing already, from the line before: trust the stamp
    int e = s;
    for (int t = w1 - 1; t > s; t--) if (v->voiced[t] && v->voiced[t - 1]) { e = t; break; }
    if (e - s < 6) return 0;
    int Ns = 0;
    for (int i = 0; i < n_words; i++) Ns += syll[i] < 1 ? 1 : syll[i];
    // the stamp is a good guide to where the line starts, and a line has only so many syllables to sing: don't let a held instrument stretch it
    double ts = time_of(v, s) - 0.5 * v->frame_s, te = time_of(v, e) + 0.5 * v->frame_s;
    if (ts < base - slack) { ts = base - slack; s = frame_of(v, ts); }   // a breath, an ad-lib or the end of the line before doesn't start this one
    double longest = 0.9 * Ns + 0.8;
    if (te - ts > longest) { te = ts + longest; e = frame_of(v, te); }
    if (e - s < 6) return 0;
    if (end) *end = te;
    if (Ns > MAXSYL) Ns = MAXSYL;

    // how long each syllable is expected to take: a stutter or a repeated "la la" is quick, and the last note of a line is held
    double wt[MAXSYL];
    {
        int j = 0;
        for (int i = 0; i < n_words && j < Ns; i++) {
            int sy = syll[i] < 1 ? 1 : syll[i];
            for (int k = 0; k < sy && j < Ns; k++) wt[j++] = (quick && quick[i]) ? 0.55 : 1.0;
        }
        wt[Ns - 1] *= 1.2;
    }
    double cum[MAXSYL + 1];
    cum[0] = 0;
    for (int j = 0; j < Ns; j++) cum[j + 1] = cum[j] + wt[j];
    double dur = te - ts;
    double exp_t[MAXSYL], tsyl[MAXSYL];
    int matched[MAXSYL];
    for (int j = 0; j < Ns; j++) { exp_t[j] = ts + dur * cum[j] / cum[Ns]; matched[j] = 0; }
    {   // a pause inside the line (a breath, a beat of music) takes no syllables: spread them over the time that is actually sung
        int len = e - s + 1;
        int *cv = malloc(sizeof(int) * (size_t)(len + 1));
        if (cv) {
            cv[0] = 0;
            for (int k = 0; k < len; k++) cv[k + 1] = cv[k] + (v->voiced[s + k] ? 1 : 0);
            if (cv[len] >= 6) {
                int k = 0;
                for (int j = 0; j < Ns; j++) {
                    double target = cv[len] * cum[j] / cum[Ns];
                    while (k < len && cv[k] < target) k++;
                    double tt = time_of(v, s + (k > 0 ? k - 1 : 0)) - 0.5 * v->frame_s;
                    exp_t[j] = tt < ts ? ts : (tt > te ? te : tt);
                }
            }
            free(cv);
        }
    }
    for (int j = 0; j < Ns; j++) tsyl[j] = exp_t[j];
    double scale = 1.5 * (dur / (Ns > 0 ? Ns : 1)) + 0.1;

    double cand[MAXCAND], strength[MAXCAND];
    int Nc = gather(v, s, e, 0, cand, strength, MAXCAND);
    int nmatch = match(Ns, cand, strength, Nc, exp_t, scale, tsyl, matched);
    if (nmatch < Ns * 4 / 5 && Nc < Ns * 3 / 2) {          // too few starts for the syllables: look harder (stutters), keep it if it matches more
        double cand2[MAXCAND], strength2[MAXCAND], tsyl2[MAXSYL];
        int matched2[MAXSYL];
        int Nc2 = gather(v, s, e, 1, cand2, strength2, MAXCAND);
        for (int j = 0; j < Ns; j++) { tsyl2[j] = exp_t[j]; matched2[j] = 0; }
        int nm2 = match(Ns, cand2, strength2, Nc2, exp_t, scale, tsyl2, matched2);
        if (nm2 > nmatch) { nmatch = nm2; Nc = Nc2; for (int j = 0; j < Ns; j++) { tsyl[j] = tsyl2[j]; matched[j] = matched2[j]; } }
    }
#ifdef STRATA_DEBUG
    fprintf(stderr, "region %.2f-%.2f Ns=%d Nc=%d matched=%d\n", ts, te, Ns, Nc, nmatch);
#endif
    int quality = 1;
    if (nmatch >= (Ns * 2 + 4) / 5 && nmatch >= 2) {
        quality = 2;
        // unmatched syllables sit between the matched ones around them, in proportion to how long each is expected to take
        int prev = -1;
        for (int j = 0; j <= Ns; j++) {
            if (j < Ns && !matched[j]) continue;
            double tn = j < Ns ? tsyl[j] : te;
            double tp = prev >= 0 ? tsyl[prev] : ts;
            double c0 = prev >= 0 ? cum[prev] : 0, c1 = j < Ns ? cum[j] : cum[Ns];
            for (int q = prev + 1; q < j; q++) {
                double f = c1 > c0 ? (cum[q] - c0) / (c1 - c0) : 0;
                tsyl[q] = tp + (tn - tp) * f;
            }
            prev = j;
        }
    }
    // a start that lands in silence moves to where the voice comes in (if it does soon): nothing is lit while nobody sings
    for (int j = 0; j < Ns; j++) {
        int f = frame_of(v, tsyl[j]), lim = frame_of(v, tsyl[j] + 0.7), any = 0;
        for (int k = f; k < f + 5 && k < v->frames; k++) if (v->voiced[k]) { any = 1; break; }
        if (any) continue;
        int k = f;
        while (k < lim && k < w1 && !v->voiced[k]) k++;
        if (k < lim && v->voiced[k]) tsyl[j] = time_of(v, k) - 0.5 * v->frame_s;
    }
    for (int j = 1; j < Ns; j++) if (tsyl[j] < tsyl[j - 1] + 0.04) tsyl[j] = tsyl[j - 1] + 0.04;

    // the start of each word is the start of its first syllable
    int j = 0;
    for (int i = 0; i < n_words; i++) {
        int sy = syll[i] < 1 ? 1 : syll[i];
        double t = tsyl[j < Ns ? j : Ns - 1];
        starts[i] = t < ws ? ws : (t > we ? we : t);
        j += sy;
    }
    for (int i = 1; i < n_words; i++) if (starts[i] < starts[i - 1]) starts[i] = starts[i - 1];
    if (ends) {
        // a word ends where the voice stops after its last syllable (a breath, a gap), or where the next word starts
        int j0 = 0;
        for (int i = 0; i < n_words; i++) {
            int sy = syll[i] < 1 ? 1 : syll[i];
            int jl = j0 + sy - 1; if (jl >= Ns) jl = Ns - 1;
            double next = i + 1 < n_words ? starts[i + 1] : te;
            if (next < starts[i] + 0.12) next = starts[i] + 0.12;
            int f = frame_of(v, (tsyl[jl] > starts[i] ? tsyl[jl] : starts[i]) + 0.06), fe = frame_of(v, next);
            if (f > fe) f = fe;
            int k = f;
            while (k < fe && v->voiced[k]) k++;
            double tend = k >= fe ? next : time_of(v, k) - 0.5 * v->frame_s + 0.03;
            if (tend < starts[i] + 0.12) tend = starts[i] + 0.12;
            if (tend > next) tend = next;
            ends[i] = tend;
            j0 += sy;
        }
    }
    return quality;
}
