#!/bin/bash
# Opens Strata Music Next in the emulator with the sample library, visits every main screen and prints small screenshots
# into the log (base64 between ==IMG name== / ==END== markers), plus any crash.
P=com.auraos.music.next
adb install -r Strata-Music-Next.apk
adb shell pm grant $P android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -n $P/com.auraos.music.next.NextActivity --ez seedDemo true
sleep 25
center() {  # center of the first node whose text is $1
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml u.xml >/dev/null 2>&1
  python3 - "$1" <<'PY'
import re,sys
t=open('u.xml',encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>', t):
    n=m.group(0)
    if f'text="{sys.argv[1]}"' in n:
        a,b,c,d=map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n).groups()); print((a+c)//2,(b+d)//2); break
PY
}
tapt() { xy=$(center "$1"); [ -n "$xy" ] && adb shell input tap $xy; sleep ${2:-2}; }
shot() { adb exec-out screencap -p > "$1.png"; }
back() { adb shell input keyevent 4; sleep 2; }
tapt "Albums"; shot 01-albums
tapt "Artists"; shot 02-artists
tapt "Songs"; shot 03-songs
tapt "Playlists"; shot 04-playlists
tapt "Albums"; tapt "Neon Tides" 3; shot 05-album
back
tapt "Artists"; tapt "Kairo" 3; shot 06-artist
back
tapt "Songs"; tapt "Golden Hour" 3; shot 07-mini
tapt "Golden Hour" 3; shot 08-player
adb shell input tap 960 220; sleep 2; shot 09-npmenu
back; back
adb shell input tap 1000 150; sleep 3; shot 10-settings
back
adb shell input tap 795 150; sleep 3; shot 11-search
adb logcat -d > logcat.txt
grep -A40 "FATAL EXCEPTION" logcat.txt | head -60 || true
for f in [0-9]*.png; do
  python3 -c "from PIL import Image; import sys; i=Image.open(sys.argv[1]).convert('RGB'); i=i.resize((360, int(i.height*360/i.width))); i.save(sys.argv[2], quality=70)" "$f" "${f%.png}.jpg"
  echo "==IMG ${f%.png}=="; base64 -w 0 "${f%.png}.jpg"; echo; echo "==END=="
done
