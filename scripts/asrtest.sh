#!/bin/bash
# Word timing can listen with a speech model. This downloads that model in the emulator (the real download, the real unpacking), has it listen to a clip of read speech
# whose words are known, and checks that every word is found where it really is. Needs the app already installed + started by smoke.sh.
P=com.auraos.music.next
command -v ffmpeg >/dev/null || { echo 'FAIL ffmpeg is not installed'; exit 1; }
mkdir -p at
ffmpeg -v error -y -i scripts/asrtest/speech.wav -c:a aac -b:a 128k at/speech.m4a
adb shell am start -n $P/com.auraos.music.next.NextActivity --es asrTest init >/dev/null; sleep 3
D=/sdcard/Android/data/$P/files/asrtest
adb shell mkdir -p $D 2>/dev/null
adb push at/speech.m4a $D/ >/dev/null
adb shell am start -n $P/com.auraos.music.next.NextActivity --es asrTest run >/dev/null
for i in $(seq 1 120); do sleep 5; adb logcat -d -s Strata:W | grep -q 'ASRTEST done' && break; done
adb logcat -d -s Strata:W | grep ASRTEST > atlog.txt
echo '==ASRTEST=='; cat atlog.txt; echo '==ENDASRTEST=='
python3 - <<'PY'
import re
# where each word starts, as the same model + aligner put them on a computer (seconds)
EXPECT=[0.36,0.72,1.08,1.76,1.88,2.20,2.56,2.76,3.04,3.28,3.52,3.68,4.12,4.24,4.80,5.28,5.40,5.52]
bad=0; seen=False
for ln in open('atlog.txt'):
    if 'error=' in ln: print('FAIL', ln.strip()); bad+=1
    m=re.search(r'length=([\d.]+) conf=(-?[\d.]+) listen_ms=(\d+) starts=([\d.,-]+)',ln)
    if not m: continue
    seen=True
    conf=float(m.group(2)); st=[float(x) for x in m.group(4).split(',')]
    errs=[a-b for a,b in zip(st,EXPECT)]
    print('listened in %s ms, line confidence %.2f, %d words, worst difference from the computer result %.0f ms'%(m.group(3),conf,len(st),max(abs(e) for e in errs)*1000))
    if len(st)!=len(EXPECT): print('FAIL word count',len(st)); bad+=1
    elif max(abs(e) for e in errs)>0.15: print('FAIL words off by (ms):',', '.join('%+.0f'%(e*1000) for e in errs)); bad+=1
    if conf<-2.5: print('FAIL the model barely heard the clip'); bad+=1
if not seen and not bad: print('FAIL no result at all'); bad+=1
raise SystemExit(1 if bad else 0)
PY
