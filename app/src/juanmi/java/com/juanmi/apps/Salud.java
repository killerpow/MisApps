package com.juanmi.apps;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.AggregateRecordsResponse;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.ExerciseSessionType;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.StepsRecord;
import android.health.connect.datatypes.units.Energy;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Puente con Health Connect (Android 14+): leer pasos y guardar entrenamientos. */
public class Salud {
    static final int REQ = 77;
    static final String[] PERMISOS = {
            "android.permission.health.READ_STEPS",
            "android.permission.health.WRITE_EXERCISE",
            "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED"
    };

    private final Activity act;
    private final WebView web;

    public Salud(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    private HealthConnectManager hc() {
        if (Build.VERSION.SDK_INT < 34) return null;
        try { return act.getSystemService(HealthConnectManager.class); } catch (Throwable t) { return null; }
    }

    private void js(String code) {
        act.runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    /** "ok", "sinpermiso" o "no" (móvil sin Health Connect integrado). */
    @JavascriptInterface
    public String estado() {
        if (hc() == null) return "no";
        for (String p : PERMISOS)
            if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return "sinpermiso";
        return "ok";
    }

    @JavascriptInterface
    public void pedirPermisos() {
        if (hc() == null) { js("window.saludPermisos&&window.saludPermisos('no')"); return; }
        act.runOnUiThread(() -> act.requestPermissions(PERMISOS, REQ));
    }

    /** Llamado desde MainActivity.onRequestPermissionsResult. */
    public void permisosResultado(int code) {
        if (code != REQ) return;
        js("window.saludPermisos&&window.saludPermisos(" + JSONObject.quote(estado()) + ")");
    }

    /** Pasos totales de un día (yyyy-mm-dd). Responde con window.saludPasos(dia, pasos). */
    @JavascriptInterface
    public void leerPasos(String dia) {
        HealthConnectManager m = hc();
        if (m == null) return;
        try {
            ZoneId z = ZoneId.systemDefault();
            LocalDate d = LocalDate.parse(dia);
            Instant ini = d.atStartOfDay(z).toInstant();
            Instant fin = d.plusDays(1).atStartOfDay(z).toInstant();
            Instant ahora = Instant.now();
            if (fin.isAfter(ahora)) fin = ahora;
            TimeInstantRangeFilter f = new TimeInstantRangeFilter.Builder().setStartTime(ini).setEndTime(fin).build();
            AggregateRecordsRequest<Long> req = new AggregateRecordsRequest.Builder<Long>(f)
                    .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL).build();
            m.aggregate(req, act.getMainExecutor(), new OutcomeReceiver<AggregateRecordsResponse<Long>, HealthConnectException>() {
                @Override public void onResult(AggregateRecordsResponse<Long> r) {
                    Long n = r.get(StepsRecord.STEPS_COUNT_TOTAL);
                    js("window.saludPasos&&window.saludPasos(" + JSONObject.quote(dia) + "," + (n == null ? 0 : n) + ")");
                }
                @Override public void onError(HealthConnectException e) {
                    js("window.saludError&&window.saludError(" + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            });
        } catch (Throwable t) {
            js("window.saludError&&window.saludError(" + JSONObject.quote(String.valueOf(t.getMessage())) + ")");
        }
    }

    private static int tipo(String t) {
        switch (t == null ? "" : t) {
            case "fuerza": return ExerciseSessionType.EXERCISE_SESSION_TYPE_STRENGTH_TRAINING;
            case "spin": return ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING_STATIONARY;
            case "futbol": case "futbol7": return ExerciseSessionType.EXERCISE_SESSION_TYPE_SOCCER;
            case "tenis": return ExerciseSessionType.EXERCISE_SESSION_TYPE_TENNIS;
            case "correr": return ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING;
            case "caminar": return ExerciseSessionType.EXERCISE_SESSION_TYPE_WALKING;
            case "bici": return ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING;
            case "natacion": return ExerciseSessionType.EXERCISE_SESSION_TYPE_SWIMMING_POOL;
            case "baloncesto": return ExerciseSessionType.EXERCISE_SESSION_TYPE_BASKETBALL;
            case "senderismo": return ExerciseSessionType.EXERCISE_SESSION_TYPE_HIKING;
            case "baile": return ExerciseSessionType.EXERCISE_SESSION_TYPE_DANCING;
            case "boxeo": return ExerciseSessionType.EXERCISE_SESSION_TYPE_BOXING;
            default: return ExerciseSessionType.EXERCISE_SESSION_TYPE_OTHER_WORKOUT;
        }
    }

    /**
     * Guarda un entrenamiento. JSON: {id, fin (ms), min, kcal, titulo, tipo}.
     * Responde con window.saludGuardado(id, ok).
     */
    @JavascriptInterface
    public void guardarEjercicio(String json) {
        HealthConnectManager m = hc();
        if (m == null) return;
        String id = "";
        try {
            JSONObject o = new JSONObject(json);
            id = o.optString("id");
            final String fid = id;
            Instant fin = Instant.ofEpochMilli(o.getLong("fin"));
            Instant ini = fin.minusSeconds(Math.max(1, o.optInt("min", 1)) * 60L);
            List<Record> rs = new ArrayList<>();
            rs.add(new ExerciseSessionRecord.Builder(
                    new Metadata.Builder().setClientRecordId("jb-s-" + id).build(), ini, fin, tipo(o.optString("tipo")))
                    .setTitle(o.optString("titulo", "Entrenamiento"))
                    .build());
            double kcal = o.optDouble("kcal", 0);
            if (kcal > 0) rs.add(new ActiveCaloriesBurnedRecord.Builder(
                    new Metadata.Builder().setClientRecordId("jb-c-" + id).build(), ini, fin, Energy.fromCalories(kcal * 1000)).build());
            m.insertRecords(rs, act.getMainExecutor(), new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
                @Override public void onResult(InsertRecordsResponse r) {
                    js("window.saludGuardado&&window.saludGuardado(" + JSONObject.quote(fid) + ",true)");
                }
                @Override public void onError(HealthConnectException e) {
                    js("window.saludGuardado&&window.saludGuardado(" + JSONObject.quote(fid) + ",false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            });
        } catch (Throwable t) {
            js("window.saludGuardado&&window.saludGuardado(" + JSONObject.quote(id) + ",false," + JSONObject.quote(String.valueOf(t.getMessage())) + ")");
        }
    }

    /** Abre los ajustes de Health Connect. */
    @JavascriptInterface
    public void abrirAjustes() {
        act.runOnUiThread(() -> {
            try {
                act.startActivity(new android.content.Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)
                        .putExtra(android.content.Intent.EXTRA_PACKAGE_NAME, act.getPackageName()));
            } catch (Throwable t) {
                try { act.startActivity(new android.content.Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")); } catch (Throwable ignored) {}
            }
        });
    }
}
