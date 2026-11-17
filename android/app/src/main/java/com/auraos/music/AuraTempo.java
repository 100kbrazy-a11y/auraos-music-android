package com.auraos.music;

/**
 * Finds the tempo and where the beats fall, in one stretch of a song (fed sample by sample while it's being decoded
 * for the silence check anyway, so it costs no extra decoding).
 *
 * How: the sound is cut into 10 ms slices; for each, how much louder it got than the slice before (overall, and in
 * the bass, where kicks live) — a beat is a sudden rise. That "onset" curve repeats at the beat: its self-similarity
 * (autocorrelation) gives the rough beat length, then a fine comb search over beat lengths and offsets lines a grid up
 * with the onsets as exactly as possible. The grid is measured right where it's used: the first 30 s (a song coming
 * in) and the last 30 s (a song going out).
 *
 * Result: { bpm, a beat's time in seconds (song time), confidence } — confidence is how strongly onsets sit on that
 * grid compared with anywhere else (≈1 means no steady beat: such songs are never tempo-matched).
 */
final class AuraTempo {
    private int rate, win;
    private double hop, a;
    private double t0 = -1, accF, accL, lp;
    private int cnt, n;
    private float[] eF = new float[4096], eL = new float[4096];

    AuraTempo(int rate) { setRate(rate); }

    /** the decoder's real output rate (before anything is fed) */
    void setRate(int rate) {
        if (n > 0 || cnt > 0) return;
        this.rate = Math.max(8000, rate);
        this.win = Math.max(1, this.rate / 100);
        this.hop = win / (double) this.rate;
        this.a = 1 - Math.exp(-2 * Math.PI * 150.0 / this.rate);          // the bass: below ~150 Hz
    }

    /** one sample (channels averaged), at song time t (only the first one's time is used) */
    void feed(float m, double t) {
        if (t0 < 0) t0 = t;
        lp += a * (m - lp);
        accF += m * m; accL += lp * lp;
        if (++cnt == win) {
            if (n == eF.length) { eF = java.util.Arrays.copyOf(eF, n * 2); eL = java.util.Arrays.copyOf(eL, n * 2); }
            eF[n] = (float) (accF / win); eL[n] = (float) (accL / win); n++;
            accF = accL = 0; cnt = 0;
        }
    }

    /** null when there's too little to go on */
    double[] analyze() {
        if (n < 800) return null;                                       // under 8 s
        float[] o = new float[n];
        double pf = Math.log1p(eF[0] * 1e4), pl = Math.log1p(eL[0] * 1e4);
        for (int i = 1; i < n; i++) {
            double f = Math.log1p(eF[i] * 1e4), l = Math.log1p(eL[i] * 1e4);
            o[i] = (float) (Math.max(0, f - pf) + Math.max(0, l - pl));
            pf = f; pl = l;
        }
        // keep only rises above the local average (a steady loud passage isn't a beat)
        float[] d = new float[n];
        double run = 0; int W = 15;
        for (int i = 0; i < n; i++) {
            run += o[i]; if (i - 2 * W - 1 >= 0) run -= o[i - 2 * W - 1];
            int c = i - W; if (c < 0) continue;
            int cntw = Math.min(i, 2 * W) + 1;
            d[c] = (float) Math.max(0, o[c] - run / cntw);
        }
        // rough beat length: 60–200 BPM
        int lo = (int) Math.floor(60 / (200 * hop)), hi = (int) Math.ceil(60 / (60 * hop));
        double[] acf = new double[2 * hi + 2];
        for (int L = lo; L <= Math.min(2 * hi + 1, n - 1); L++) {
            double s = 0;
            for (int i = 0; i + L < n; i++) s += d[i] * d[i + L];
            acf[L] = s / (n - L);
        }
        int best = -1; double bs = -1;
        for (int L = lo; L <= hi; L++) {
            double bpm = 60 / (L * hop), oc = Math.log(bpm / 120) / Math.log(2);
            double s = (acf[L] + 0.5 * (2 * L < acf.length ? acf[2 * L] : 0)) * Math.exp(-0.5 * (oc / 0.9) * (oc / 0.9));
            if (s > bs) { bs = s; best = L; }
        }
        if (best < 0 || bs <= 0) return null;
        // fine: the beat length and offset that line up best with the onsets
        double bestP = best, bestPh = 0, bestScore = -1;
        for (double P = best - 1.5; P <= best + 1.5; P += 0.02) {
            for (double ph = 0; ph < P; ph += 0.5) {
                double s = comb(d, P, ph);
                if (s > bestScore) { bestScore = s; bestP = P; bestPh = ph; }
            }
        }
        for (double ph = bestPh - 0.5; ph <= bestPh + 0.5; ph += 0.05) {
            double s = comb(d, bestP, ph);
            if (s > bestScore) { bestScore = s; bestPh = ph; }
        }
        // confidence: the best grid against every other offset of the same beat length
        double sum = 0; int k = 0;
        for (double ph = 0; ph < bestP; ph += 0.5) { sum += comb(d, bestP, ph); k++; }
        double conf = k > 0 && sum > 0 ? bestScore / (sum / k) : 0;
        double ph = ((bestPh % bestP) + bestP) % bestP;
        return new double[] { 60 / (bestP * hop), t0 + ph * hop, conf };
    }

    private static double comb(float[] d, double P, double ph) {
        double s = 0; int n = d.length;
        for (double x = ph; x < n - 1; x += P) {
            int i = (int) x; double f = x - i;
            s += d[i] * (1 - f) + d[i + 1] * f;
        }
        return s;
    }
}
