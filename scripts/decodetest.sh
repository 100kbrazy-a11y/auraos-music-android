#!/bin/bash
# Word timing listens to a song through the phone's own decoder. This makes test sounds with known burst times, runs them through that decoder
# in the emulator and checks that every burst comes out where it really is (and about as loud). Needs the app already installed + started by smoke.sh.
P=com.auraos.music.next
python3 - <<'PY'
import math, struct, wave
BURSTS=[1.5,4.0,9.25,14.0,21.5,27.0]
def make(path, rate, secs=30):
    w=wave.open(path,'wb'); w.setnchannels(2); w.setsampwidth(2); w.setframerate(rate)
    buf=bytearray()
    for i in range(rate*secs):
        t=i/rate; v=0.0
        for b in BURSTS:
            if b<=t<b+0.15: v=0.5*math.sin(2*math.pi*1000*(t-b))
        s=struct.pack('<h',int(v*32767)); buf+=s+s
    w.writeframes(bytes(buf)); w.close()
make('t44.wav',44100); make('t48.wav',48000)
PY
command -v ffmpeg >/dev/null || { echo 'FAIL ffmpeg is not installed'; exit 1; }
mkdir -p dt
for r in 44 48; do
  ffmpeg -v error -y -i t$r.wav -c:a libmp3lame -b:a 128k dt/r$r.mp3
  ffmpeg -v error -y -i t$r.wav -c:a aac -b:a 128k dt/r$r.m4a
  ffmpeg -v error -y -i t$r.wav -c:a libopus -b:a 96k dt/r$r.opus
  ffmpeg -v error -y -i t$r.wav -c:a libvorbis -q:a 4 dt/r$r.ogg
done
ffmpeg -v error -y -i t44.wav -c:a libopus -b:a 96k dt/r44_webm.webm
adb shell am start -n $P/com.auraos.music.next.NextActivity --es decodeTest init >/dev/null; sleep 3
D=/sdcard/Android/data/$P/files/decodetest
adb shell mkdir -p $D 2>/dev/null
for f in dt/*; do adb push "$f" $D/ >/dev/null; done
adb shell ls -la $D | head -20
adb shell am start -n $P/com.auraos.music.next.NextActivity --es decodeTest run >/dev/null
for i in $(seq 1 60); do sleep 3; adb logcat -d -s Strata:W | grep -q 'DECODETEST done' && break; done
adb logcat -d -s Strata:W | grep DECODETEST > dtlog.txt
echo '==DECODETEST=='; cat dtlog.txt; echo '==ENDDECODETEST=='
python3 - <<'PY'
import re
BURSTS=[1.5,4.0,9.25,14.0,21.5,27.0]
bad=0; seen=0
for ln in open('dtlog.txt'):
    m=re.search(r'file=(\S+) length=([\d.]+) onsets=([\d.,]*) peaks=([\d,]*)',ln)
    if not m:
        if 'error=' in ln: print('FAIL', ln.strip()); bad+=1
        continue
    seen+=1
    name=m.group(1); length=float(m.group(2)); on=[float(x) for x in m.group(3).split(',') if x]; pk=[int(x) for x in m.group(4).split(',') if x]
    res=[]
    if abs(length-30)>0.15: res.append(f'length {length:.3f}s (expected 30)')
    if len(on)!=len(BURSTS): res.append(f'found {len(on)} bursts (expected {len(BURSTS)})')
    else:
        errs=[(o-b)*1000 for o,b in zip(on,BURSTS)]
        if max(abs(e) for e in errs)>30: res.append('burst starts off by ms: '+', '.join(f'{e:+.0f}' for e in errs))
        lv=[p/16384 for p in pk]
        if min(lv)<0.7 or max(lv)>1.3: res.append('loudness ratio '+', '.join(f'{x:.2f}' for x in lv))
    print(('FAIL ' if res else 'ok   ')+name+(' — '+'; '.join(res) if res else f' (starts {", ".join(f"{(o-b)*1000:+.0f}" for o,b in zip(on,BURSTS))} ms)'))
    bad+=bool(res)
print('files checked:',seen); 
if seen==0: print('FAIL no test file was read at all'); bad+=1
raise SystemExit(1 if bad else 0)
PY
