package app.onelly.edit;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import com.getcapacitor.BridgeActivity;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FastRenderPlugin.class);
        super.onCreate(savedInstanceState);
        try {
            getBridge().getWebView().addJavascriptInterface(new SaveBridge(), "OnellySave");
        } catch (Throwable ignored) { }
    }

    /** Saves a file into the phone's Downloads/Onelly Edit folder, piece by piece (works for GB-sized videos). Called from www/bridge.js. */
    public class SaveBridge {
        private OutputStream os;
        private Uri uri;
        private File file;

        @JavascriptInterface
        public synchronized String begin(String name, String mime) {
            try {
                abortQuiet();
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Onelly Edit");
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    ContentResolver cr = getContentResolver();
                    uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "ERR:cannot create file in Downloads";
                    os = cr.openOutputStream(uri);
                    if (os == null) return "ERR:cannot open file in Downloads";
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    dir.mkdirs();
                    file = new File(dir, safe);
                    os = new BufferedOutputStream(new FileOutputStream(file));
                }
                return "ok";
            } catch (Throwable e) {
                return "ERR:" + e;
            }
        }

        @JavascriptInterface
        public synchronized String write(String b64) {
            try {
                os.write(Base64.decode(b64, Base64.DEFAULT));
                return "ok";
            } catch (Throwable e) {
                return "ERR:" + e;
            }
        }

        @JavascriptInterface
        public synchronized String end() {
            try {
                if (os != null) { os.flush(); os.close(); os = null; }
                if (uri != null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, v, null, null);
                    uri = null;
                    return "Downloads/Onelly Edit";
                }
                if (file != null) { String p = file.getAbsolutePath(); file = null; return p; }
                return "ok";
            } catch (Throwable e) {
                return "ERR:" + e;
            }
        }

        @JavascriptInterface
        public synchronized void abort() { abortQuiet(); }

        private void abortQuiet() {
            try { if (os != null) os.close(); } catch (Throwable ignored) { }
            os = null;
            try { if (uri != null) getContentResolver().delete(uri, null, null); } catch (Throwable ignored) { }
            uri = null;
            if (file != null) { file.delete(); file = null; }
        }
    }
}
