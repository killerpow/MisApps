package com.juanmi.apps;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Iterator;

/** Recordatorios que suenan aunque la app esté cerrada (y se vuelven a programar al reiniciar el móvil). */
public class Avisos extends BroadcastReceiver {

    private static final String PREFS = "avisos", CANAL = "avisos";

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static PendingIntent pending(Context c, String id, String title, String text) {
        Intent i = new Intent(c, Avisos.class).setAction("com.juanmi.apps.AVISO")
                .putExtra("id", id).putExtra("title", title).putExtra("text", text);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, flags);
    }

    public static void programar(Context c, String id, long when, String title, String text) {
        try {
            JSONObject all = new JSONObject(prefs(c).getString("lista", "{}"));
            JSONObject old = all.optJSONObject(id);
            if (when <= System.currentTimeMillis()) { cancelar(c, id); return; }
            if (old != null && old.optLong("when") == when && title.equals(old.optString("title"))
                    && text.equals(old.optString("text"))) return; // ya estaba igual
            JSONObject o = new JSONObject();
            o.put("when", when); o.put("title", title); o.put("text", text);
            all.put(id, o);
            prefs(c).edit().putString("lista", all.toString()).apply();
            alarm(c, id, when, title, text);
        } catch (Exception ignored) { }
    }

    private static void alarm(Context c, String id, long when, String title, String text) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c, id, title, text);
        boolean exacta = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (Build.VERSION.SDK_INT >= 23 && exacta) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        else if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        else am.set(AlarmManager.RTC_WAKEUP, when, pi);
    }

    public static void cancelar(Context c, String id) {
        try {
            JSONObject all = new JSONObject(prefs(c).getString("lista", "{}"));
            JSONObject o = all.optJSONObject(id);
            if (o != null) {
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                if (am != null) am.cancel(pending(c, id, o.optString("title"), o.optString("text")));
                all.remove(id);
                prefs(c).edit().putString("lista", all.toString()).apply();
            }
        } catch (Exception ignored) { }
    }

    private static void reprogramarTodo(Context c) {
        try {
            JSONObject all = new JSONObject(prefs(c).getString("lista", "{}"));
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject o = all.getJSONObject(id);
                if (o.optLong("when") > System.currentTimeMillis())
                    alarm(c, id, o.optLong("when"), o.optString("title"), o.optString("text"));
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.MY_PACKAGE_REPLACED".equals(a)) {
            reprogramarTodo(c);
            return;
        }
        if (!"com.juanmi.apps.AVISO".equals(a)) return;
        String id = intent.getStringExtra("id"), title = intent.getStringExtra("title"), text = intent.getStringExtra("text");
        try {
            JSONObject all = new JSONObject(prefs(c).getString("lista", "{}"));
            all.remove(id);
            prefs(c).edit().putString("lista", all.toString()).apply();
        } catch (Exception ignored) { }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CANAL, "Recordatorios", NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(c, CANAL);
        } else {
            b = new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        }
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        b.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 0, open, flags));
        nm.notify(id == null ? 1 : id.hashCode(), b.build());
    }
}
