#!/usr/bin/env bash
# AuraOS Music for Android — one command that gets everything it needs, builds the app,
# signs it, and puts AuraOS-Music.apk in your Downloads folder.
#
#   ./build-android.sh            build the APK
#   ./build-android.sh --install  …and install it on a phone plugged in with USB debugging on
#   ./build-android.sh --clean    rebuild the Android project from scratch first
#
# The first run downloads about 1–2 GB (Android SDK, Java 17, Gradle). Later runs take about a minute.
set -Eeuo pipefail
cd "$(dirname "$(readlink -f "$0")")"
HERE="$(pwd)"

APK_NAME="AuraOS-Music.apk"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/auraos-music-android"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
KEYDIR="${XDG_CONFIG_HOME:-$HOME/.config}/auraos-music"
DOWNLOADS="$(xdg-user-dir DOWNLOAD 2>/dev/null || true)"
[ -n "$DOWNLOADS" ] && [ "$DOWNLOADS" != "$HOME" ] || DOWNLOADS="$HOME/Downloads"
CMDLINE_TOOLS="commandlinetools-linux-11076708_latest.zip"
PLATFORM="android-34"
BUILD_TOOLS="34.0.0"
INSTALL=0 CLEAN=0
for a in "$@"; do
  case "$a" in
    --install) INSTALL=1 ;;
    --clean) CLEAN=1 ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $a (try --help)"; exit 1 ;;
  esac
done

step() { printf '\n\033[1;35m==>\033[0m \033[1m%s\033[0m\n' "$*"; }
note() { printf '    %s\n' "$*"; }
die()  { trap - ERR; printf '\n\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }
trap 'die "Something went wrong (line $LINENO). The messages above say what — fix that and run ./build-android.sh again; it picks up where it left off."' ERR
mkdir -p "$CACHE" "$KEYDIR" "$DOWNLOADS"
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64) JARCH=x64; NARCH=x64 ;;
  aarch64|arm64) JARCH=aarch64; NARCH=arm64 ;;
  *) die "This build needs a 64-bit Intel/AMD or ARM computer (found $ARCH)." ;;
esac
fetch() { curl -fL --retry 3 --progress-bar -o "$2.part" "$1" && mv "$2.part" "$2"; }

# ------------------------------------------------------------------ 1. basic tools
step "Checking basic tools"
missing=()
for t in curl unzip tar xz; do command -v "$t" >/dev/null || missing+=("$t"); done
if [ ${#missing[@]} -gt 0 ]; then
  note "Installing: ${missing[*]}"
  if command -v dnf >/dev/null; then sudo dnf install -y curl unzip tar xz
  elif command -v apt-get >/dev/null; then sudo apt-get update && sudo apt-get install -y curl unzip tar xz-utils
  elif command -v pacman >/dev/null; then sudo pacman -S --needed --noconfirm curl unzip tar xz
  elif command -v zypper >/dev/null; then sudo zypper install -y curl unzip tar xz
  else die "Please install these with your package manager: ${missing[*]}"; fi
fi
note "ok"

# ------------------------------------------------------------------ 2. Node.js 22+
step "Node.js"
node_ok() { command -v node >/dev/null && node -e 'const [a,b]=process.versions.node.split(".").map(Number);process.exit(a>22||(a===22&&b>=12)?0:1)' 2>/dev/null; }
if ! node_ok && [ -x "$CACHE/node/bin/node" ]; then export PATH="$CACHE/node/bin:$PATH"; fi
if ! node_ok && command -v dnf >/dev/null; then
  note "Installing Node.js from Fedora…"
  sudo dnf install -y nodejs npm >/dev/null 2>&1 || sudo dnf install -y nodejs22 nodejs22-npm >/dev/null 2>&1 || true
fi
if ! node_ok; then
  note "Downloading a private copy of Node.js 22 (only used for this build)…"
  ver="$(curl -fsSL https://nodejs.org/dist/latest-v22.x/SHASUMS256.txt | grep -o "node-v22[0-9.]*-linux-$NARCH.tar.xz" | head -1)"
  [ -n "$ver" ] || die "Couldn't find a Node.js download. Check your internet connection."
  fetch "https://nodejs.org/dist/latest-v22.x/$ver" "$CACHE/node.tar.xz"
  rm -rf "$CACHE/node" && mkdir -p "$CACHE/node" && tar -xJf "$CACHE/node.tar.xz" -C "$CACHE/node" --strip-components=1 && rm -f "$CACHE/node.tar.xz"
  export PATH="$CACHE/node/bin:$PATH"
fi
node_ok || die "Node.js 22.12 or newer is needed."
note "Node $(node -v)"

# ------------------------------------------------------------------ 3. Java 17 (what Android's build tools want)
step "Java 17"
java_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }
JDK=""
for cand in "${JAVA_HOME:-}" /usr/lib/jvm/java-17-openjdk* /usr/lib/jvm/temurin-17* /usr/lib/jvm/jdk-17* "$CACHE/jdk-17"; do
  [ -n "$cand" ] && [ -x "$cand/bin/javac" ] && [ "$(java_major "$cand/bin/java")" = "17" ] && { JDK="$cand"; break; }
done
if [ -z "$JDK" ] && command -v dnf >/dev/null && sudo dnf install -y java-17-openjdk-devel >/dev/null 2>&1; then
  for cand in /usr/lib/jvm/java-17-openjdk*; do [ -x "$cand/bin/javac" ] && { JDK="$cand"; break; }; done
fi
if [ -z "$JDK" ]; then
  note "Downloading a private copy of Java 17 (Eclipse Temurin, only used for this build)…"
  fetch "https://api.adoptium.net/v3/binary/latest/17/ga/linux/$JARCH/jdk/hotspot/normal/eclipse" "$CACHE/jdk17.tar.gz"
  rm -rf "$CACHE/jdk-17" && mkdir -p "$CACHE/jdk-17" && tar -xzf "$CACHE/jdk17.tar.gz" -C "$CACHE/jdk-17" --strip-components=1 && rm -f "$CACHE/jdk17.tar.gz"
  JDK="$CACHE/jdk-17"
fi
export JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH"
note "Java $(java_major "$JDK/bin/java") at $JDK"

# ------------------------------------------------------------------ 4. Android SDK
step "Android SDK"
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKM" ]; then
  note "Downloading Android command-line tools to $SDK…"
  mkdir -p "$SDK/cmdline-tools"
  fetch "https://dl.google.com/android/repository/$CMDLINE_TOOLS" "$CACHE/$CMDLINE_TOOLS"
  rm -rf "$SDK/cmdline-tools/latest" "$SDK/cmdline-tools/cmdline-tools"
  unzip -q "$CACHE/$CMDLINE_TOOLS" -d "$SDK/cmdline-tools" && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
if [ ! -d "$SDK/platforms/$PLATFORM" ] || [ ! -x "$SDK/build-tools/$BUILD_TOOLS/apksigner" ] || [ ! -d "$SDK/platform-tools" ]; then
  note "Accepting the Android SDK licences and installing the pieces the build needs…"
  yes 2>/dev/null | "$SDKM" --sdk_root="$SDK" --licenses >/dev/null || true
  "$SDKM" --sdk_root="$SDK" --install "platform-tools" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" | grep -v '^\[=*' || true
fi
[ -x "$SDK/build-tools/$BUILD_TOOLS/apksigner" ] || die "The Android SDK didn't install correctly. Run again, or delete $SDK and retry."
export PATH="$SDK/platform-tools:$PATH"
note "SDK at $SDK"

# ------------------------------------------------------------------ 5. the app
step "Building the app"
if [ -f package-lock.json ]; then npm ci --no-audit --no-fund --loglevel=error; else npm install --no-audit --no-fund --loglevel=error; fi
npm run build --silent
if [ "$CLEAN" = 1 ]; then rm -rf android; fi
if [ ! -d android ]; then
  note "Creating the Android project…"
  npx cap add android
  npx capacitor-assets generate --android --iconBackgroundColor '#0b0b0e' --iconBackgroundColorDark '#0b0b0e' \
    --splashBackgroundColor '#0b0b0e' --splashBackgroundColorDark '#0b0b0e' >/dev/null
fi
node scripts/patch-android.mjs
npx cap telemetry off >/dev/null 2>&1 || true
npx cap sync android
echo "sdk.dir=$SDK" > android/local.properties

step "Compiling the Android app (Gradle — the first time downloads a lot, give it a few minutes)"
( cd android && chmod +x gradlew && ./gradlew --no-daemon assembleRelease )
UNSIGNED="android/app/build/outputs/apk/release/app-release-unsigned.apk"
[ -f "$UNSIGNED" ] || die "Gradle finished but no APK was made."

# ------------------------------------------------------------------ 6. sign it (your own key, kept so updates install over the old app)
step "Signing"
KS="$KEYDIR/android-release.jks"
PASSF="$KEYDIR/android-keystore.pass"
# the project's own key (the one GitHub builds use too), so an APK from here and one from GitHub update each other
if [ -f "$HERE/signing/release.jks" ] && [ -f "$HERE/signing/keystore.pass" ]; then
  KS="$HERE/signing/release.jks"; PASSF="$HERE/signing/keystore.pass"
fi
if [ ! -f "$KS" ]; then
  umask 077
  head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$PASSF"
  keytool -genkeypair -keystore "$KS" -alias auraos -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass:file "$PASSF" -keypass:file "$PASSF" -dname "CN=AuraOS Music, O=AuraOS" >/dev/null 2>&1
  umask 022
  note "Made your signing key: $KS"
  note "Keep it (and android-keystore.pass) — phones only accept updates signed with the same key."
fi
BT="$SDK/build-tools/$BUILD_TOOLS"
ALIGNED="$CACHE/aligned.apk"
"$BT/zipalign" -f -p 4 "$UNSIGNED" "$ALIGNED"
OUT="$DOWNLOADS/$APK_NAME"
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$PASSF" --ks-key-alias auraos --out "$OUT" "$ALIGNED"
rm -f "$ALIGNED" "$OUT.idsig"
"$BT/apksigner" verify "$OUT" >/dev/null
SIZE="$(du -h "$OUT" | cut -f1)"

# ------------------------------------------------------------------ 7. done: show it
step "Done"
printf '    \033[1;32m%s\033[0m  (%s)\n' "$OUT" "$SIZE"
if command -v gdbus >/dev/null; then
  gdbus call --session --dest org.freedesktop.FileManager1 --object-path /org/freedesktop/FileManager1 \
    --method org.freedesktop.FileManager1.ShowItems "['file://$OUT']" "" >/dev/null 2>&1 || xdg-open "$DOWNLOADS" >/dev/null 2>&1 || true
fi
if [ "$INSTALL" = 1 ]; then
  step "Installing on your phone"
  if adb get-state >/dev/null 2>&1; then adb install -r "$OUT" && note "Installed — open AuraOS Music on your phone."
  else note "No phone found. Plug it in with USB debugging turned on (Settings → Developer options), then run with --install again."; fi
else
  note "To install: send the APK to your phone (USB, cloud, or a cable) and open it."
  note "Android will ask you to allow installing apps from that source — allow it once."
  note "Or plug the phone in with USB debugging on and run:  ./build-android.sh --install"
fi
