package io.github.qbju.hakoniwa;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** APK のダウンロード（SHA-256 検証つき）と PackageInstaller でのインストール。 */
final class Installer {
    interface Listener { void changed(String pkg); }

    /** pkg → 進捗（0〜100 はダウンロード中、101 はインストール中）。無ければ待機中。 */
    static final Map<String, Integer> PROGRESS = new HashMap<>();
    static final Map<String, String> ERROR = new HashMap<>();
    static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    static synchronized boolean busy(String pkg) { return PROGRESS.containsKey(pkg); }

    static void notifyChanged(String pkg) { Ui.MAIN.post(() -> { for (Listener l : LISTENERS) l.changed(pkg); }); }

    static synchronized void set(String pkg, Integer p) {
        if (p == null) PROGRESS.remove(pkg); else PROGRESS.put(pkg, p);
        notifyChanged(pkg);
    }

    static void install(Context ctx, Repo.App app, Repo.Version v) {
        final Context c = ctx.getApplicationContext();
        synchronized (Installer.class) {
            if (busy(app.pkg)) return;
            ERROR.remove(app.pkg);
            PROGRESS.put(app.pkg, 0);
        }
        notifyChanged(app.pkg);
        Ui.IO.execute(() -> {
            File apk = new File(new File(c.getCacheDir(), "apk"), app.pkg + "_" + v.code + ".apk");
            try {
                apk.getParentFile().mkdirs();
                download(app.pkg, Repo.BASE + v.file, apk, v);
                PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
                if (pi == null || !app.pkg.equals(pi.packageName)) throw new IOException("パッケージ名が一致しません");
                set(app.pkg, 101);
                session(c, app.pkg, apk);
            } catch (Exception e) {
                apk.delete();
                synchronized (Installer.class) { ERROR.put(app.pkg, e.getMessage()); }
                set(app.pkg, null);
            }
        });
    }

    private static void download(String pkg, String url, File out, Repo.Version v) throws Exception {
        HttpURLConnection conn = Repo.open(url);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long total = v.size > 0 ? v.size : conn.getContentLengthLong(), done = 0;
        int last = -1;
        try (InputStream in = conn.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) {
                o.write(b, 0, n);
                md.update(b, 0, n);
                done += n;
                if (total > 0) {
                    int p = (int) Math.min(100, done * 100 / total);
                    if (p != last) { last = p; set(pkg, p); }
                }
            }
        } finally {
            conn.disconnect();
        }
        if (!Repo.hex(md.digest()).equalsIgnoreCase(v.sha256)) throw new SecurityException("APKのハッシュがリポジトリと一致しません");
    }

    private static void session(Context c, String pkg, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(pkg);
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("base.apk", 0, apk.length())) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                s.fsync(o);
            }
            Intent i = new Intent(c, InstallReceiver.class).putExtra("pkg", pkg).putExtra("apk", apk.getPath());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, i, flags).getIntentSender());
        }
    }
}
