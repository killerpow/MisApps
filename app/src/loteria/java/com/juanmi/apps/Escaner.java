package com.juanmi.apps;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Escáner de documentos de Google (el mismo tipo que trae la cámara): décimos planos, rectos y limpios. */
public class Escaner {
    static final int REQ = 88;
    private final Activity act;
    private final WebView web;

    public Escaner(Activity a, WebView w) { act = a; web = w; }

    @JavascriptInterface
    public boolean disponible() { return true; }

    @JavascriptInterface
    public void escanear(int maxPaginas) {
        act.runOnUiThread(() -> {
            try {
                GmsDocumentScannerOptions o = new GmsDocumentScannerOptions.Builder()
                        .setGalleryImportAllowed(true)
                        .setPageLimit(Math.max(1, Math.min(maxPaginas, 30)))
                        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                        .build();
                GmsDocumentScanner sc = GmsDocumentScanning.getClient(o);
                sc.getStartScanIntent(act)
                        .addOnSuccessListener(is -> {
                            try { act.startIntentSenderForResult(is, REQ, null, 0, 0, 0); }
                            catch (Exception e) { error(String.valueOf(e.getMessage())); }
                        })
                        .addOnFailureListener(e -> error(String.valueOf(e.getMessage())));
            } catch (Throwable t) { error(t.toString()); }
        });
    }

    /** Lo llama MainActivity desde onActivityResult; devuelve true si era nuestro. */
    public boolean resultado(int req, int code, Intent data) {
        if (req != REQ) return false;
        if (code != Activity.RESULT_OK || data == null) { js("window.__escaneado&&window.__escaneado([])"); return true; }
        new Thread(() -> {
            try {
                GmsDocumentScanningResult r = GmsDocumentScanningResult.fromActivityResultIntent(data);
                JSONArray arr = new JSONArray();
                if (r != null && r.getPages() != null)
                    for (GmsDocumentScanningResult.Page p : r.getPages()) arr.put(aDataUrl(p.getImageUri()));
                js("window.__escaneado&&window.__escaneado(" + arr.toString() + ")");
            } catch (Throwable t) { error(t.toString()); }
        }).start();
        return true;
    }

    /** Imagen reducida a 1800 px como data:image/jpeg;base64 */
    private String aDataUrl(Uri u) throws Exception {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        try (InputStream in = act.getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, b); }
        int lado = Math.max(b.outWidth, b.outHeight), s = 1;
        while (lado / (s * 2) >= 1800) s *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap bm;
        try (InputStream in = act.getContentResolver().openInputStream(u)) { bm = BitmapFactory.decodeStream(in, null, o); }
        float k = Math.min(1f, 1800f / Math.max(bm.getWidth(), bm.getHeight()));
        if (k < 1f) bm = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * k), Math.round(bm.getHeight() * k), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 88, out);
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private void js(String s) { act.runOnUiThread(() -> web.evaluateJavascript(s, null)); }
    private void error(String m) { js("window.__escaneadoError&&window.__escaneadoError(" + JSONObject.quote(m) + ")"); }
}
