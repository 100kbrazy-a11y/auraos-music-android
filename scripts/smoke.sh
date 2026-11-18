#!/bin/bash
# Opens Strata Music Next in the emulator with the sample library, visits every main screen and prints small screenshots
# into the log (base64 between ==IMG name== / ==END== markers), plus any crash.
P=com.auraos.music.next
adb install -r Strata-Music-Next.apk
adb shell pm grant $P android.permission.POST_NOTIFICATIONS || true
adb logcat -c
(adb logcat -v brief '*:W' > live.txt 2>/dev/null &)
adb shell am start -n $P/com.auraos.music.next.NextActivity --ez seedDemo true --ez liquid false
sleep 25
adb shell dumpsys meminfo com.auraos.music.next | head -20
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
tapt() { xy=$(center "$1"); [ -n "$xy" ] && adb shell input tap $xy; sleep ${2:-3}; }
shot() { adb exec-out screencap -p > "$1.png"; }
back() { adb shell input keyevent 4; sleep 2; }
ycen() { center "$1" | cut -d' ' -f2; }
tapt "Albums"; shot 01-albums
tapt "Artists"; shot 02-artists
tapt "Songs"; shot 03-songs
tapt "Playlists"; shot 04-playlists
tapt "Albums"; tapt "Afterglow" 3; shot 05-album
back
tapt "Artists"; tapt "Kairo" 3; shot 06-artist
back
tapt "Songs"; y=$(ycen "Blue"); adb shell input tap 990 $y; sleep 2; shot 12-songmenu
back
adb shell input tap 774 210; sleep 3; adb shell input text blue; sleep 3; shot 11-search
back; sleep 1; back
adb shell input tap 886 210; sleep 3; shot 14-filter
back
tapt "Blue 2" 3; shot 07-mini
adb shell input tap 400 2129; sleep 3; shot 08-player
adb shell input tap 960 160; sleep 2; shot 09-npmenu
back
adb shell input tap 160 2290; sleep 3; shot 16-lyrics
adb shell input tap 160 2290; sleep 2
adb shell input tap 920 2290; sleep 3; shot 17-queue
back; back
adb shell input tap 997 210; sleep 3; shot 10-settings
adb shell input swipe 540 1900 540 600 400; sleep 2; shot 13-settings2
adb shell am force-stop $P; sleep 2
adb shell am start -n $P/com.auraos.music.next.NextActivity --ez liquid true; sleep 20
shot 12-liquid; adb shell input tap 540 1300; sleep 5; shot 13-liquid2
adb get-state || echo "EMULATOR GONE after liquid glass"
echo '==LOG=='; grep -E 'FATAL|AndroidRuntime|Strata|lowmemorykiller|ANR|OutOfMemory' live.txt | head -60; echo '==ENDLOG=='
for f in [0-9]*.png; do
  [ -s "$f" ] || continue
  python3 -c "from PIL import Image; import sys; i=Image.open(sys.argv[1]).convert('RGB'); i=i.resize((360, int(i.height*360/i.width))); i.save(sys.argv[2], quality=70)" "$f" "${f%.png}.jpg"
  echo "==IMG ${f%.png}=="; base64 -w 0 "${f%.png}.jpg"; echo; echo "==END=="
done
