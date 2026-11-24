// Strata Music <-> Discord Social SDK bridge (native-next / "next" flavor only).
//
// Rich Presence only: no sign-in, no token. With the Discord app installed on the same phone, the SDK talks to it over
// Android's RPC service (Social SDK 1.10+), so this never connects a Client to Discord's servers.
// Every function here is called from ONE thread (DiscordRpc's worker), which is also the one that runs the callbacks.
#include <jni.h>
#include <android/log.h>
#include <memory>
#include <string>

#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

#define TAG "StrataDiscord"

static std::unique_ptr<discordpp::Client> gClient;
static std::string gResult = "pending";   // "ok", or the error text of the last UpdateRichPresence

static std::string str(JNIEnv* e, jstring s) {
    if (!s) return "";
    const char* c = e->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) e->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_auraos_music_next_DiscordBridge_nInit(JNIEnv*, jclass, jlong appId) {
    if (!gClient) {
        gClient = std::make_unique<discordpp::Client>();
        gClient->SetApplicationId(static_cast<uint64_t>(appId));
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_auraos_music_next_DiscordBridge_nTick(JNIEnv*, jclass) {
    discordpp::RunCallbacks();
}

// type 2 = Listening. displayType: 0 = app name, 1 = state, 2 = details (what the status line shows in the member list)
JNIEXPORT void JNICALL Java_com_auraos_music_next_DiscordBridge_nUpdate(
    JNIEnv* e, jclass, jstring name, jstring details, jstring state, jlong startMs, jlong endMs,
    jstring largeImage, jstring largeText, jint displayType) {
    if (!gClient) return;
    discordpp::Activity a;
    a.SetType(discordpp::ActivityTypes::Listening);
    std::string n = str(e, name), d = str(e, details), s = str(e, state);
    if (!n.empty()) a.SetName(n);
    if (!d.empty()) a.SetDetails(d);
    if (!s.empty()) a.SetState(s);
    switch (displayType) {
        case 0: a.SetStatusDisplayType(discordpp::StatusDisplayTypes::Name); break;
        case 1: a.SetStatusDisplayType(discordpp::StatusDisplayTypes::State); break;
        default: a.SetStatusDisplayType(discordpp::StatusDisplayTypes::Details); break;
    }
    if (startMs > 0) {
        discordpp::ActivityTimestamps t;
        t.SetStart(static_cast<uint64_t>(startMs));
        if (endMs > startMs) t.SetEnd(static_cast<uint64_t>(endMs));
        a.SetTimestamps(t);
    }
    std::string li = str(e, largeImage), lt = str(e, largeText);
    if (!li.empty()) {
        discordpp::ActivityAssets as;
        as.SetLargeImage(li);
        if (!lt.empty()) as.SetLargeText(lt);
        a.SetAssets(as);
    }
    gClient->UpdateRichPresence(std::move(a), [](discordpp::ClientResult r) {
        gResult = r.Successful() ? "ok" : (r.Error().empty() ? r.ToString() : r.Error());
        __android_log_print(r.Successful() ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, TAG, "UpdateRichPresence: %s", gResult.c_str());
    });
}

JNIEXPORT void JNICALL Java_com_auraos_music_next_DiscordBridge_nClear(JNIEnv*, jclass) {
    if (gClient) gClient->ClearRichPresence();
}

JNIEXPORT jstring JNICALL Java_com_auraos_music_next_DiscordBridge_nResult(JNIEnv* e, jclass) {
    return e->NewStringUTF(gResult.c_str());
}

JNIEXPORT void JNICALL Java_com_auraos_music_next_DiscordBridge_nShutdown(JNIEnv*, jclass) {
    if (gClient) gClient->ClearRichPresence();
    gClient.reset();
}

}  // extern "C"
