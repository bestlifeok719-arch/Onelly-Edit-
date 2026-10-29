ONELLY EDIT - NATIVE FAST EXPORT (Android APK, GitHub Actions)
================================================================

Wanta haaraa
- native/FastRenderPlugin.java : plugin Android (Media3 Transformer). Viidiyoo hardware decode
  -> GPU irratti barruu/istikera/suuraa kaa'uu -> hardware encode. GPU read-back hin jiru
  (CapCut / KineMaster akka kanaan).
- native/MainActivity.java      : plugin galmeessa.
- .github/workflows/android.yml : APK ijaaruu (plugin + Media3 dependency ofumaan dabalama).
- www/index.html                : editor (Native export path ofumaan filata yoo APK keessa ta'e).

Akkamitti APK ijaaruu (GitHub)
1. Faayila hunda (ZIP kana keessa jiru) repository GitHub tokko keessa olkaa'i (branch main).
2. GitHub > Actions > "Build Android APK" > Run workflow (ykn push godhi, ofumaan jalqaba).
3. Xumuramee booda (daqiiqaa 5-10) > Artifacts > onelly-edit-apk > app-debug.apk buusi, bilbila irratti dhaabi.
4. Yoo dogoggora jiraate: Actions > run sana > "Build debug APK" step > log sana natti ergi.

Native export yeroo hojjatu
- Android APK keessa qofa (browser/Chrome keessatti hin hojjatu, sana irratti browser export itti fufa).
- Kilipii cut qofa yoo ta'e (effect hin qabne) Smart copy (browser keessatti) saffisaadha, kanaaf native hin fayyadamu.
- Text / sticker / suuraa (image) overlay baay'ee irratti (copy <50%) native GPU export fayyadama.
- Native export hin deeggaru: speed, rotate, flip, filter/LUT, volume, voice/muuziqaa dabalataa, fade,
  viidiyoo overlay, blend mode, chroma key. Sanaaf ofumaan browser export itti fufa.
- Bu'aan Movies/OnellyEdit keessatti galmaa'a (gallery).
