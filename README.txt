==============================================================
 ONELLY EDIT  -  Video Editor (Android + iPhone + Web)
 "International of all worlds"
==============================================================

1. WANTA KUN MAALI?
-------------------
Onelly Edit editor viidiyoo guutuu dha. Bilbila irratti (Chrome,
Android APK, iPhone) viidiyoo muruu, qoodu, barruu/istikera
dabaluu, filtarii, sagalee, fi kkf hojjachuuf tolfame.
Faayilli `www/index.html` = app guutuu (HTML+JS tokko). Capacitor
fayyadamuun APK Android fi app iPhone ta'a.

Afaan: English, Afaan Oromoo, Amharic, Arabic (fuula olii irratti filadhu).


2. FAAYILOTA (STRUCTURE)
------------------------
Onelly_Edit_App/
  www/index.html            Editor guutuu (kan ijoo)
  www/bridge.js             Native bridge (APK/iOS keessatti faayila olkaa'uu / Share)
  .github/workflows/android.yml   APK ijaaruu (GitHub Actions)
  .github/workflows/ios.yml       iOS simulator ijaaruu (Mac)
  capacitor.config.json     Qindaa'ina Capacitor (appId: app.onelly.edit)
  package.json              Capacitor plugins
  .gitignore
  README.md                 Ibsa gabaabaa
  README.txt                Ibsa guutuu (kana)


3. DANDEETTIIWWAN
-----------------
3.1 Kan duraanuu jiru
  EDIT      : qoodi (split), freeze, reverse, saffisa (presets: Hero,
              Montage, Bullet, Ramp up/down) + speed curve, tolfama
              (transitions), sagalee (volume), rotate (90 degree), zoom,
              mask/shape, mallattoo (markers), mirror, koppii, tartiiba
              jijjiiruu, haquu
  TIMELINE  : trim (fiixee adii harkisuun dheeressi/gabaabsi), snap, undo/redo,
              thumbnails + waveform
  AUDIO     : muuziqaa, sagalee ofii (voice), SFX, beat detect,
              audio extract (viidiyoo irraa)
  LOOK      : Filter (7) + LUT, Adjust, Grade (curves/shadow/highlight),
              FX (blur, vignette, grain, glitch)
  TEXT      : barruu + animation, istikera (emoji), draw, SRT subtitle
  OVERLAY   : viidiyoo/suuraa overlay (PiP), keyframes, Green screen (chroma key)
  KAN BIROO : Template, Background (color/blur), Smart cuts, Project save/load,
              Export (idilee)

3.2 Kan haaraa (yeroo dhiyootti dabalame)
  TIMELINE
   - Ruler CapCut fakkaata: 2f, 10f, 00:01 ... 1:00, 2:00 (zoom irratti hundaa'a)
   - Zoom: quba lamaan (pinch) ykn  ... -> Zoom +/-  (2f hanga viidiyoo sa'aatii)
   - Bakka duwwaa timeline irratti harkisuun scrub
   - Overlay/kilipii harkisuun dheeressuu/gabaabsuu sirreeffame (NaN dogoggora)
   - Double-tap viidiyoo irratti: giddu = play/pause, bitaa = -3s, mirga = +3s
  EDIT (meeshaalee gadii)
   - Angle  : rotate fi'a (-45 hanga +45 degree), auto-zoom
   - Repeat : kilipii irra deebi'ee koppii
   - Boomerang : kilipii + reverse
  FILTER / LOOK (meeshaalee gadii)
   - Filter 48+ (Bright, Story, Dew, Pink, Vintage-01..06, ST, Food, Urban,
     Style, Film, Amber, Grape, Citrus, Coral ...)
   - Sharpen (Low/Medium/High), Motion blur (horizontal/vertical)
   - Look: Copy / Paste (filtarii fi halluu kilipii tokkoo irraa kan biraatti)
   - Fade: project jalqaba (in) fi dhuma (out)  (viidiyoo + sagalee)
   - Platform presets: TikTok/Reels/Shorts (9:16), YouTube (16:9),
     Instagram (4:5), Square (1:1)  - ratio + resolution + fps
  SHAPE / RATIO
   - 16:9, 9:16, 1:1, 4:5, 4:3, 3:4, 2:1, 5:8, 8.5:15.5, 15.5:8.5, 2.35:1,
     1.85:1 + Custom (W:H)
  AUDIO
   - Music: Import + Recent (muuziqaa dhiyoo, phone keessatti olkaa'ama)
   - Audio fade kilipii tokko tokkoo irratti (in/out)
  OVERLAY
   - Picker amma faayila hunda agarsiisa (Files, Downloads, Photos)
  EXPORT
   - FAST EXPORT (koppii, re-encode malee) - gadi ilaali (#4)
  KAN BIROO
   - Button  ...  (violet): daandii gabaabaa (undo, split, trim before/after, ...)
   - Sirreeffama: "Continue last project" (project olkaa'ame banuu)

   Hubadhu: Kan armaan olii hunda tokko tokkoon meeshaalee gadii (toolbar)
   keessatti iddoo idileetti jira; button "..." keessattis jira.


4. FAST EXPORT (KOPPII, SAFFISAA)
---------------------------------
Export tuqxu, project kee muruu/qoodu/haquu qofa yoo ta'e
(barruu, filtarii, saffisa, sagalee dabalataa hin qabu) fuulli
"Fast Export" ni banama:
  - "Saffisaa (koppii)": viidiyoo deebisee encode godhuu malee koppii gochuu.
    Fakkeenya: viidiyoo sa'aatii 2:22 (994 MB) -> ~30 sekondii. Qulqullinni
    hin jijjiiramu.
  - "Export idilee": akkuma duraan (effects hunda waliin).
Daangaa:
  - Faayila MP4/MOV (H.264 ykn H.265) tokko irraa qofa. AVI, MKV, WebM = hin jiru.
  - Sagaleen AAC ta'uu qaba.
  - Murri keyframe irratti ta'a (iddoo muruu irraa ~1-2 sekondii jijjiiramuu danda'a).
  - Fade, filtarii, barruu, saffisa, rotate, Angle, Sharpen, Motion blur,
    audio fade, overlay, music yoo jiraate Export idilee ofumaan banama.
  - Libraries jsDelivr irraa buufamu (interneetii barbaachisa): mp4box 0.5.2,
    mp4-muxer 5.1.3.
  - Output diskii (OPFS) irratti barreeffama (RAM hin guutu). Browser OPFS hin
    deeggarre yoo ta'e RAM fayyadama.
  - APK keessatti faayila guddaa (GBs) olkaa'uun bridge.js (Share) RAM baay'ee
    fayyadama. Kana irratti mirkaneessuu barbaachisa.


5. AKKAMITTI APK IJAARU (GitHub)
--------------------------------
1. github.com irratti repository haaraa uumi (fkn `onelly-edit`).
2. Faayila foldara kanaa hunda olkaa'i (zip kana bantee, `Onelly_Edit_App`
   keessa jiru hunda; `www`, `.github`, `package.json`, `capacitor.config.json`, ...).
3. GitHub -> Actions -> "Build Android APK" -> Run workflow
   (ykn main branch irratti push godhi, ofumaan hojjata).
4. ~5 daqiiqaa booda: Artifacts -> onelly-edit-apk (app-debug.apk) buufadhu.
5. Bilbila Android irratti install godhi ("Install unknown apps" eeyyami).

iPhone:
  - "Build iOS" workflow simulator qofaaf dha (Mac).
  - iPhone dhugaa irratti install gochuuf Apple Developer account ($99/waggaa)
    fi Xcode/TestFlight barbaachisa.
  - Karaa salphaa: Safari irratti editor banii Share -> Add to Home Screen.

Fooyya'insa: `www/index.html` jijjiirii, commit godhi, APK haaraan ofumaan ijaarama.


6. WANTA MIRKANA'E / HIN MIRKANA'IN (DHUGAA)
--------------------------------------------
Mirkana'e:
  - Browser (Chromium) keessatti: viidiyoo galchuu, text/sticker/video overlay,
    overlay dheeressuu/gabaabsuu, filtarii 48, rotate Angle, Fast Export fi
    bu'aa isaa (viidiyoo guddaa irratti 100x-300x).
  - Fast Export: viidiyoo sa'aatii 2:22 irratti suuraa fi sagaleen sirrii ta'uu
    nama dhuunfaa tokko bilbila irratti mirkaneesse.
Hin mirkana'in (bilbila dhugaa irratti hin yaalamne):
  - Sharpen, Motion blur, Fade, Copy/Paste Look, Platform presets, Audio fade.
  - APK ijaaruu/install (workflows duraanuu jiru, jijjiiramni hin jiru).
  - iPhone irratti Fast Export (WebCodecs Safari irratti dhiphataa).


7. HIN JIRU (KAAYYOO ITTI AANU - APK NATIVE)
--------------------------------------------
Kunniin biraauzarii keessatti hin danda'amu; APK native irratti qofa:
  - Auto captions (sagalee -> barruu), Background removal (AI), Stabilization,
    Enhance (AI), Noise reduction, Auto reframe
  - Format AVI/MKV/FLV (FFmpeg native, FFmpegKit)
  - Effects/barruu waliin export saffisaa (MediaCodec native)
  - Filter library "Featured" (server barbaachisa), HSL, Curves guutuu
Karoora: Capacitor plugin native (FFmpegKit/MediaCodec) itti dabaluu.


8. LICENSE / KUTAALEE KAN BIROO
-------------------------------
  - Capacitor (MIT)
  - mp4box.js (BSD-3-Clause), mp4-muxer (MIT): runtime irratti jsDelivr irraa.
  Licence kee mataa kee ofii murteessi; kutaalee kana fayyadamuu keessan dura
  licence isaanii ilaalaa.


--------------------------------------------------------------
ENGLISH SUMMARY
--------------------------------------------------------------
Onelly Edit is a mobile-first video editor (single-file web app in
www/index.html) packaged for Android/iOS with Capacitor.
Build the APK: upload this folder to a GitHub repo, then Actions ->
"Build Android APK" -> Run workflow -> download "onelly-edit-apk".
New in this version: CapCut-style adaptive ruler (2f ... 1:00), pinch zoom,
48 filters, sharpen, motion blur, fine rotate, repeat/boomerang, fade (project
and per-clip audio), platform presets, 12+ aspect ratios + custom, music
"Recent", copy/paste look, and "Fast Export": near-instant lossless export
(stream copy via WebCodecs-free MP4 remux, mp4box + mp4-muxer) for cut-only
projects (e.g. 2h22m video in ~30 s). Limits: MP4/MOV with H.264/H.265 and
AAC audio only; cuts land on keyframes. AI tools (auto captions, background
removal, stabilization) and AVI/MKV support require the native APK
(planned: FFmpegKit/MediaCodec plugin).
