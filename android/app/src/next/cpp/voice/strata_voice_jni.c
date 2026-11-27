// The bridge between Kotlin (Voice.kt) and the voice finder: plain C, no libraries.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "strata_voice.h"

#define FN(name) Java_com_auraos_music_next_Voice_##name

/// left / right: 16-bit samples at [sr] Hz. The analysis works on floats, which live in native memory (not the app's heap) only while it runs.
JNIEXPORT jlong JNICALL FN(nAnalyze)(JNIEnv *env, jclass cls, jshortArray left, jshortArray right, jint n, jint sr) {
    (void)cls;
    if (n <= 0) return 0;
    float *fl = (float *)malloc(sizeof(float) * (size_t)n);
    float *fr = (float *)malloc(sizeof(float) * (size_t)n);
    if (!fl || !fr) { free(fl); free(fr); return 0; }
    jshort *pl = (*env)->GetShortArrayElements(env, left, NULL);
    jshort *pr = (*env)->GetShortArrayElements(env, right, NULL);
    if (!pl || !pr) {
        if (pl) (*env)->ReleaseShortArrayElements(env, left, pl, JNI_ABORT);
        if (pr) (*env)->ReleaseShortArrayElements(env, right, pr, JNI_ABORT);
        free(fl); free(fr); return 0;
    }
    for (jint i = 0; i < n; i++) { fl[i] = (float)pl[i] * (1.0f / 32768.0f); fr[i] = (float)pr[i] * (1.0f / 32768.0f); }
    (*env)->ReleaseShortArrayElements(env, left, pl, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, right, pr, JNI_ABORT);
    strata_voice *v = strata_voice_analyze(fl, fr, (int64_t)n, sr);
    free(fl); free(fr);
    return (jlong)(intptr_t)v;
}

JNIEXPORT void JNICALL FN(nFree)(JNIEnv *env, jclass cls, jlong v) {
    (void)env; (void)cls;
    strata_voice_free((strata_voice *)(intptr_t)v);
}

JNIEXPORT jint JNICALL FN(nSyllables)(JNIEnv *env, jclass cls, jstring word) {
    (void)cls;
    const char *c = (*env)->GetStringUTFChars(env, word, NULL);
    if (!c) return 1;
    int n = strata_voice_syllables(c);
    (*env)->ReleaseStringUTFChars(env, word, c);
    return n;
}

/// how far off the lyrics' line times are (seconds to add); used[0] = how many lines gave a reading
JNIEXPORT jdouble JNICALL FN(nResidual)(JNIEnv *env, jclass cls, jlong v, jdoubleArray bases, jintArray used) {
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, bases);
    jdouble *b = (*env)->GetDoubleArrayElements(env, bases, NULL);
    if (!b) return 0;
    int u = 0;
    double r = strata_voice_residual((const strata_voice *)(intptr_t)v, (int)n, (const double *)b, &u);
    (*env)->ReleaseDoubleArrayElements(env, bases, b, JNI_ABORT);
    (*env)->SetIntArrayRegion(env, used, 0, 1, (const jint *)&u);
    return r;
}

/// lines one line up: starts / ends have one slot per word, endOut[0] = when the line stops being sung. Returns 0 (nothing sung there), 1 (spread over the singing) or 2 (matched to syllables)
JNIEXPORT jint JNICALL FN(nAlignLine)(JNIEnv *env, jclass cls, jlong v, jdouble ws, jdouble we, jdouble after, jdouble base, jdouble slack,
                                      jintArray syll, jintArray quick, jdoubleArray starts, jdoubleArray ends, jdoubleArray endOut) {
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, syll);
    jint *sy = (*env)->GetIntArrayElements(env, syll, NULL);
    jint *qu = (*env)->GetIntArrayElements(env, quick, NULL);
    double *st = (double *)malloc(sizeof(double) * (size_t)(n > 0 ? n : 1));
    double *en = (double *)malloc(sizeof(double) * (size_t)(n > 0 ? n : 1));
    double e = 0;
    int q = 0;
    if (sy && qu && st && en) {
        for (jsize i = 0; i < n; i++) { st[i] = 0; en[i] = 0; }
        q = strata_voice_align_line((const strata_voice *)(intptr_t)v, ws, we, after, base, slack, (int)n, (const int *)sy, (const int *)qu, st, en, &e);
        (*env)->SetDoubleArrayRegion(env, starts, 0, n, (const jdouble *)st);
        (*env)->SetDoubleArrayRegion(env, ends, 0, n, (const jdouble *)en);
        (*env)->SetDoubleArrayRegion(env, endOut, 0, 1, (const jdouble *)&e);
    }
    if (sy) (*env)->ReleaseIntArrayElements(env, syll, sy, JNI_ABORT);
    if (qu) (*env)->ReleaseIntArrayElements(env, quick, qu, JNI_ABORT);
    free(st); free(en);
    return q;
}
