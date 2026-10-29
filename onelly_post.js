// Runs automatically after "npm install" (package.json -> postinstall).
// Sets up, WITHOUT touching .github/workflows/android.yml:
//   1) the fixed signing key  -> a new APK installs over the old one (no uninstall)
//   2) the build number       -> an older APK can never be installed over a newer one
const fs = require('fs'), os = require('os'), path = require('path'), cp = require('child_process');
const home = os.homedir();
try {
  const cands = ['native/debug.keystore', 'debug.keystore', 'ci/debug.keystore'].map(p => path.resolve(p));
  const ks = cands.find(p => fs.existsSync(p));
  if (ks) {
    let ok = true;
    try { cp.execFileSync('keytool', ['-list', '-keystore', ks, '-storepass', 'android'], { stdio: 'ignore' }); } catch (e) { ok = false; }
    if (ok) {
      fs.mkdirSync(path.join(home, '.android'), { recursive: true });
      fs.copyFileSync(ks, path.join(home, '.android', 'debug.keystore'));
      console.log('[onelly] fixed signing key installed');
    } else console.log('[onelly] keystore not readable -> skipped');
  } else console.log('[onelly] no debug.keystore found -> skipped');
} catch (e) { console.log('[onelly] keystore step skipped:', e.message); }
try {
  const d = path.join(home, '.gradle', 'init.d');
  fs.mkdirSync(d, { recursive: true });
  fs.writeFileSync(path.join(d, 'onelly.gradle'), `
allprojects { p ->
  p.afterEvaluate {
    try {
      if (p.plugins.hasPlugin('com.android.application')) {
        def n = (System.getenv('GITHUB_RUN_NUMBER') ?: '1') as int
        p.android.defaultConfig.versionCode = n
        p.android.defaultConfig.versionName = 'v13-build-' + n
        println '[onelly] versionCode ' + n
      }
    } catch (Throwable t) { println '[onelly] build number skipped: ' + t }
  }
}
`);
  console.log('[onelly] build number script installed');
} catch (e) { console.log('[onelly] build number step skipped:', e.message); }
