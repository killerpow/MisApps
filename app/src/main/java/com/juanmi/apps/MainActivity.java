package com.juanmi.apps;

import android.Manifest;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Contenedor nativo: muestra la app web que va dentro del APK (assets/index.html),
 * guarda los datos en el propio móvil y conecta con Android (archivos, compartir,
 * WhatsApp, ubicación, imprimir, pantalla completa).
 */
public class MainActivity extends Activity {
    private Object extra;
    private Object escaner;
    private PermissionRequest camRequest;
    private static final int REQ_CAM = 31;


    private static final int REQ_FILE = 10, REQ_LOC = 11, REQ_RESTORE = 12, REQ_STORAGE = 13;
    private static final String ACT_RESTORE = "com.juanmi.apps.RESTAURAR", ACT_BACKUP = "com.juanmi.apps.COPIA";
    private static final int KEEP_DAYS = 7;
    /** Lee todo lo que la app tiene guardado (localStorage) como JSON. */
    private static final String DUMP_JS = "(function(){var o={};try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);o[k]=localStorage.getItem(k);}}catch(e){}return JSON.stringify(o);})()";
    private SharedPreferences prefs;

    private WebView web;
    private FrameLayout root;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setGeolocationEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        // Extras propios de una app (por ejemplo, Salud en Juanmi Booster), si existen en su variante
        try {
            Class<?> k = Class.forName("com.juanmi.apps.Salud");
            extra = k.getConstructor(android.app.Activity.class, WebView.class).newInstance(this, web);
            web.addJavascriptInterface(extra, "Salud");
        } catch (Throwable ignored) { extra = null; }
        // Escáner de documentos (solo en las apps que lo traen, como Mis Décimos)
        try {
            Class<?> k = Class.forName("com.juanmi.apps.Escaner");
            escaner = k.getConstructor(android.app.Activity.class, WebView.class).newInstance(this, web);
            web.addJavascriptInterface(escaner, "Escaner");
        } catch (Throwable ignored) { escaner = null; }
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (url.startsWith("data:")) saveDataUrl(guessName(cd, mime), url);
            else if (url.startsWith("blob:")) web.evaluateJavascript(
                    "window.__androidSave&&window.__androidSave('archivo'," + jsString(url) + ")", null);
            else openExternal(url);
        });

        prefs = getSharedPreferences("misapps", MODE_PRIVATE);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/" + startPage());

        setupShortcuts();
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermissionCompat()) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
        if (!handleAction(getIntent()) && !prefs.getBoolean("preguntado", false)) {
            prefs.edit().putBoolean("preguntado", true).apply();
            web.postDelayed(this::askFirstRestore, 900);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAction(intent);
    }

    private boolean pedidoNotif = false;
    private void pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT < 33 || pedidoNotif) return;
        pedidoNotif = true;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 14));
    }

    private boolean checkSelfPermissionCompat() {
        return Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    // ---------- Copia de seguridad automática ----------

    private String backupFolder() {
        return Environment.DIRECTORY_DOWNLOADS + "/MisApps/" + getString(R.string.app_name);
    }

    private boolean handleAction(Intent i) {
        if (i == null || i.getAction() == null) return false;
        if (ACT_RESTORE.equals(i.getAction())) { web.postDelayed(this::pickBackup, 600); return true; }
        if (ACT_BACKUP.equals(i.getAction())) { web.postDelayed(() -> doBackup(true), 1200); return true; }
        return false;
    }

    /** Accesos directos al mantener pulsado el icono: guardar copia / restaurar copia. */
    private void setupShortcuts() {
        if (Build.VERSION.SDK_INT < 25) return;
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null) return;
            ShortcutInfo a = new ShortcutInfo.Builder(this, "copia")
                    .setShortLabel("Guardar copia")
                    .setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_save))
                    .setIntent(new Intent(ACT_BACKUP).setClass(this, MainActivity.class)).build();
            ShortcutInfo b = new ShortcutInfo.Builder(this, "restaurar")
                    .setShortLabel("Restaurar copia")
                    .setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_revert))
                    .setIntent(new Intent(ACT_RESTORE).setClass(this, MainActivity.class)).build();
            sm.setDynamicShortcuts(Arrays.asList(a, b));
        } catch (Exception ignored) { }
    }

    private void askFirstRestore() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("¿Recuperar tus datos?")
                .setMessage("Si ya usabas esta app y tienes una copia de seguridad (en Descargas/MisApps), puedes recuperarla ahora.\n\n"
                        + "Desde ahora la app guardará sola una copia cada día en Descargas/MisApps/" + getString(R.string.app_name) + ".")
                .setPositiveButton("Elegir copia", (d, w) -> pickBackup())
                .setNegativeButton("Empezar de cero", null)
                .show();
    }

    /** Guarda una copia si algo ha cambiado desde la última (o siempre, si force). */
    private void doBackup(boolean force) {
        web.evaluateJavascript(DUMP_JS, res -> {
            try {
                if (res == null || res.equals("null")) return;
                String json = (String) new JSONTokener(res).nextValue();
                JSONObject datos = new JSONObject(json);
                if (datos.length() == 0) { if (force) toast("Todavía no hay datos que guardar"); return; }
                int hash = json.hashCode();
                if (!force && hash == prefs.getInt("hash", 0)) return;
                JSONObject w = new JSONObject();
                w.put("copiaMisApps", 1);
                w.put("app", getString(R.string.app_name));
                w.put("paquete", getPackageName());
                w.put("fecha", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()));
                w.put("datos", datos);
                String name = "copia-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".json";
                byte[] bytes = w.toString().getBytes(StandardCharsets.UTF_8);
                new Thread(() -> {
                    boolean ok = writeBackup(name, bytes);
                    if (ok) {
                        prefs.edit().putInt("hash", hash).apply();
                        cleanOldBackups();
                    }
                    if (force) toast(ok ? "Copia guardada en Descargas/MisApps/" + getString(R.string.app_name)
                            : "No se pudo guardar la copia");
                }).start();
            } catch (Exception e) {
                if (force) toast("No se pudo guardar la copia");
            }
        });
    }

    private boolean writeBackup(String name, byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                String rel = backupFolder() + "/";
                Uri found = null;
                try (Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns._ID},
                        MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                        new String[]{name, rel}, null)) {
                    if (c != null && c.moveToFirst())
                        found = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(c.getLong(0)));
                }
                if (found == null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                    found = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                }
                if (found == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(found, "wt")) {
                    if (out == null) return false;
                    out.write(bytes);
                }
                return true;
            } else {
                File dir = checkSelfPermissionCompat()
                        ? new File(getExternalFilesDir(null), "MisApps")
                        : new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "MisApps/" + getString(R.string.app_name));
                dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { out.write(bytes); }
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Deja solo las copias de los últimos días. */
    private void cleanOldBackups() {
        try {
            String limit = "copia-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .format(new Date(System.currentTimeMillis() - KEEP_DAYS * 86400000L)) + ".json";
            if (Build.VERSION.SDK_INT >= 29) {
                List<Long> old = new ArrayList<>();
                try (Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME},
                        MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{backupFolder() + "/"}, null)) {
                    while (c != null && c.moveToNext()) {
                        String n = c.getString(1);
                        if (n != null && n.startsWith("copia-") && n.compareTo(limit) < 0) old.add(c.getLong(0));
                    }
                }
                for (long id : old)
                    getContentResolver().delete(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id)), null, null);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "MisApps/" + getString(R.string.app_name));
                File[] fs = dir.listFiles();
                if (fs != null) for (File f : fs)
                    if (f.getName().startsWith("copia-") && f.getName().compareTo(limit) < 0) f.delete();
            }
        } catch (Exception ignored) { }
    }

    private void pickBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(i, REQ_RESTORE);
        } catch (Exception e) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*"), REQ_RESTORE);
            } catch (Exception e2) { toast("No hay explorador de archivos"); }
        }
    }

    private void restoreFrom(Uri uri) {
        try {
            StringBuilder sb = new StringBuilder();
            try (InputStream in = getContentResolver().openInputStream(uri);
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            String text = sb.toString();
            JSONObject w = null;
            try { w = new JSONObject(text.substring(Math.max(0, text.indexOf('{')))); } catch (Exception ignored) { }
            if (w == null || !w.has("copiaMisApps") || !w.has("datos")) {
                // Copia hecha con la propia app (o con la versión antigua): se la pasamos a la app
                final String txt = text;
                web.evaluateJavascript("typeof window.restaurarCopiaApp==='function'", has -> {
                    if ("true".equals(has)) web.evaluateJavascript("window.restaurarCopiaApp(" + JSONObject.quote(txt) + ")", null);
                    else toast("Esta app no reconoce ese archivo de copia");
                });
                return;
            }
            JSONObject datos = w.getJSONObject("datos");
            String origen = w.optString("app", "");
            String msg = "Copia del " + w.optString("fecha", "?")
                    + (origen.isEmpty() || origen.equals(getString(R.string.app_name)) ? "" : "\n⚠️ Es de la app «" + origen + "».")
                    + "\n\nSe sustituirán los datos actuales por los de la copia.";
            new AlertDialog.Builder(this)
                    .setTitle("¿Restaurar esta copia?")
                    .setMessage(msg)
                    .setPositiveButton("Restaurar", (d, x) -> {
                        prefs.edit().putInt("hash", 0).apply();
                        web.evaluateJavascript("(function(d){try{localStorage.clear();for(var k in d)localStorage.setItem(k,d[k]);}catch(e){}location.reload();})("
                                + datos.toString() + ")", null);
                        toast("Copia restaurada");
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        } catch (Exception e) {
            toast("No se pudo leer la copia");
        }
    }

    /** En el televisor usa la versión para TV si la app la trae. */
    private String startPage() {
        UiModeManager ui = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        boolean tv = ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        if (tv) {
            try {
                getAssets().open("tv.html").close();
                return "tv.html";
            } catch (Exception ignored) { }
        }
        return "index.html";
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { hideCustom(); return; }
        if (web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        doBackup(false);
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        goFullscreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    /** Pantalla completa: oculta la barra de estado y los botones; se ven deslizando desde el borde. */
    @SuppressWarnings("deprecation")
    private void goFullscreen() {
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    // ---------- Navegación ----------

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return handleUrl(req.getUrl().toString(), req.isForMainFrame());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url, true);
        }
    }

    private boolean handleUrl(String url, boolean mainFrame) {
        if (url.startsWith("file:") || url.startsWith("about:") || url.startsWith("javascript:")) return false;
        if (url.startsWith("blob:") || url.startsWith("data:")) return false;
        if (!mainFrame) return false;
        openExternal(url);
        return true;
    }

    private void openExternal(String url) {
        try {
            Intent i;
            if (url.startsWith("intent:")) {
                i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (getPackageManager().resolveActivity(i, 0) == null) {
                    String fb = i.getStringExtra("browser_fallback_url");
                    if (fb == null) { toast("No hay ninguna app para abrir esto"); return; }
                    i = new Intent(Intent.ACTION_VIEW, Uri.parse(fb));
                }
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("No hay ninguna app para abrir esto");
        }
    }

    // ---------- Archivos, ubicación, pantalla completa ----------

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            Intent i;
            try {
                i = params.createIntent();
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            } catch (Exception e) {
                i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            }
            String[] types = params.getAcceptTypes();
            boolean onlyExt = true;
            for (String t : types) if (t != null && t.contains("/")) onlyExt = false;
            if (onlyExt) i.setType("*/*"); // ".json" no es un tipo válido para Android: mostrar todo
            try {
                startActivityForResult(Intent.createChooser(i, "Elegir archivo"), REQ_FILE);
            } catch (Exception e) {
                fileCallback = null;
                toast("No hay explorador de archivos");
                return false;
            }
            return true;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                geoOrigin = origin;
                geoCallback = cb;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            } else {
                cb.invoke(origin, true, false);
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> {
                boolean video = false;
                for (String r : request.getResources())
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) video = true;
                if (!video) { request.deny(); return; }
                if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    camRequest = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
                }
            });
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) { callback.onCustomViewHidden(); return; }
            customView = view;
            customCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            web.setVisibility(View.GONE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override
        public void onHideCustomView() { hideCustom(); }
    }

    private void hideCustom() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        goFullscreen();
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (escaner != null) {
            try {
                Object ok = escaner.getClass().getMethod("resultado", int.class, int.class, Intent.class).invoke(escaner, requestCode, resultCode, data);
                if (Boolean.TRUE.equals(ok)) return;
            } catch (Throwable ignored) {}
        }
        if (requestCode == REQ_RESTORE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) restoreFrom(data.getData());
            return;
        }
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                result = new Uri[cd.getItemCount()];
                for (int i = 0; i < cd.getItemCount(); i++) result[i] = cd.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (extra != null) {
            try { extra.getClass().getMethod("permisosResultado", int.class).invoke(extra, code); } catch (Throwable ignored) {}
        }
        if (code == REQ_CAM && camRequest != null) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) camRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}); else camRequest.deny();
            camRequest = null;
        }
        if (code == REQ_LOC && geoCallback != null) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            geoCallback.invoke(geoOrigin, ok, false);
            geoCallback = null;
        }
    }

    // ---------- Guardar archivos en Descargas ----------

    private void saveDataUrl(String name, String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            String meta = dataUrl.substring(5, comma);
            String payload = dataUrl.substring(comma + 1);
            String mime = meta.split(";")[0];
            if (mime.isEmpty()) mime = "application/octet-stream";
            byte[] bytes = meta.contains(";base64")
                    ? Base64.decode(payload, Base64.DEFAULT)
                    : URLDecoder.decode(payload, "UTF-8").getBytes(StandardCharsets.UTF_8);
            saveBytes(name, bytes, mime);
        } catch (Exception e) {
            toast("No se pudo guardar el archivo");
        }
    }

    private void saveBytes(String name, byte[] bytes, String mime) {
        String folder = getString(R.string.app_name);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("sin uri");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new Exception("sin salida");
                    out.write(bytes);
                }
                toast("Guardado en Descargas/" + folder + ": " + name);
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = getFilesDir();
                File f = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
                toast("Guardado en: " + f.getAbsolutePath());
            }
        } catch (Exception e) {
            toast("No se pudo guardar el archivo");
        }
    }

    private static String guessName(String contentDisposition, String mime) {
        if (contentDisposition != null) {
            int i = contentDisposition.indexOf("filename=");
            if (i >= 0) return contentDisposition.substring(i + 9).replace("\"", "").trim();
        }
        if (mime != null && mime.contains("json")) return "archivo.json";
        if (mime != null && mime.contains("pdf")) return "archivo.pdf";
        if (mime != null && mime.contains("csv")) return "archivo.csv";
        return "archivo";
    }

    private static String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    // ---------- Puente JavaScript ----------

    private class Bridge {
        /** Petición HTTP hecha por Android (sin las limitaciones del navegador). Responde con window.__httpFin(id, estado, texto). */
        @JavascriptInterface
        public void http(String id, String method, String url, String headers, String body) {
            new Thread(() -> {
                int code = 0; String txt;
                java.net.HttpURLConnection c = null;
                try {
                    c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(20000); c.setReadTimeout(60000);
                    c.setRequestMethod(method == null || method.isEmpty() ? "GET" : method);
                    if (headers != null && !headers.isEmpty()) {
                        JSONObject h = new JSONObject(headers);
                        java.util.Iterator<String> it = h.keys();
                        while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, h.optString(k)); }
                    }
                    if (body != null && !body.isEmpty() && !"GET".equalsIgnoreCase(method)) {
                        c.setDoOutput(true);
                        try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
                    }
                    code = c.getResponseCode();
                    java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    if (in != null) { byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close(); }
                    txt = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                } catch (Throwable t) {
                    code = 0; txt = String.valueOf(t.getMessage());
                } finally { if (c != null) c.disconnect(); }
                final String js = "window.__httpFin&&window.__httpFin(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(txt) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        @JavascriptInterface
        public void saveText(String name, String text, String mime) {
            saveBytes(name, text.getBytes(StandardCharsets.UTF_8),
                    mime == null || mime.isEmpty() ? "text/plain" : mime);
        }

        @JavascriptInterface
        public void saveDataUrl(String name, String dataUrl) {
            MainActivity.this.saveDataUrl(name, dataUrl);
        }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("texto", text));
            });
        }

        @JavascriptInterface
        public String paste() {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) return "";
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
            return t == null ? "" : t.toString();
        }

        @JavascriptInterface
        public void share(String title, String text) {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
            if (title != null && !title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
            i.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(i, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }

        @JavascriptInterface
        public void shareFile(String name, String dataUrl, String text) {
            try {
                int comma = dataUrl.indexOf(',');
                String mime = dataUrl.substring(5, comma).split(";")[0];
                byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                File dir = new File(getCacheDir(), "compartir");
                dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
                Uri uri = Uri.parse("content://" + getPackageName() + ".archivos/" + Uri.encode(name));
                Intent i = new Intent(Intent.ACTION_SEND).setType(mime.isEmpty() ? "*/*" : mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                toast("No se pudo compartir");
            }
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (pm == null) return;
                String job = getString(R.string.app_name);
                pm.print(job, web.createPrintDocumentAdapter(job), new PrintAttributes.Builder().build());
            });
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> MainActivity.this.openExternal(url));
        }

        @JavascriptInterface
        public void programarAviso(String id, double when, String title, String text) {
            pedirPermisoNotificaciones();
            Avisos.programar(MainActivity.this, id, (long) when, title, text);
        }

        @JavascriptInterface
        public void cancelarAviso(String id) {
            Avisos.cancelar(MainActivity.this, id);
        }

        @JavascriptInterface
        public void toast(String msg) {
            MainActivity.this.toast(msg);
        }
    }
}
