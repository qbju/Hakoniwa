package io.github.qbju.hakoniwa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** バックグラウンドの更新チェック（約6時間ごと、通信できる時だけ）。更新があれば通知し、タップでマイアプリを開く。 */
public class UpdateCheck extends JobService {
    static final int JOB_ID = 1001, NOTIF_ID = 1;
    static final String CHANNEL = "updates", PREFS = "hakoniwa";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("notify", true); }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("notify", on).apply();
        if (on) schedule(c); else ((JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE)).cancel(JOB_ID);
    }

    /** 未登録なら登録（アプリ起動時・端末起動時）。 */
    static void schedule(Context c) {
        if (!enabled(c)) return;
        JobScheduler js = (JobScheduler) c.getSystemService(JOB_SCHEDULER_SERVICE);
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateCheck.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        Ui.IO.execute(() -> {
            boolean retry = false;
            try { check(this); } catch (Exception e) { retry = true; }
            jobFinished(p, retry);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return true; }

    /** 更新のあるアプリを数えて通知する。同じ内容は二度通知しない。 */
    static void check(Context c) throws Exception {
        List<Repo.App> apps = Repo.load(c);
        List<String> names = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (Repo.App a : apps) {
            long iv = Ui.installed(c, a.pkg);
            if (iv < 0 || a.latest() == null || iv >= a.latest().code) continue;
            if (a.signer != null && !a.signer.isEmpty()) {
                String s = Ui.installedSigner(c, a.pkg);
                if (s != null && !s.equalsIgnoreCase(a.signer)) continue;
            }
            names.add(a.name);
            key.append(a.pkg).append(':').append(a.latest().code).append(';');
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (names.isEmpty()) { nm.cancel(NOTIF_ID); prefs(c).edit().remove("notified").apply(); return; }
        if (key.toString().equals(prefs(c).getString("notified", ""))) return;
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "アプリの更新", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MyAppsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = names.size() + " 件のアプリを更新できます", text = android.text.TextUtils.join("、", names);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        nb.setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setColor(Ui.BLUE).setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(true);
        try { nm.notify(NOTIF_ID, nb.build()); } catch (SecurityException ignored) { return; }
        prefs(c).edit().putString("notified", key.toString()).apply();
    }

    /** 端末起動・アプリ更新後にジョブを登録し直す。 */
    public static class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) { schedule(c); }
    }
}
