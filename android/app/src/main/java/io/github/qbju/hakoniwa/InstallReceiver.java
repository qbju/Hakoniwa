package io.github.qbju.hakoniwa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.File;

/** PackageInstaller セッションの結果を受け取る。 */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String pkg = i.getStringExtra("pkg");
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); return; } catch (Exception ignored) { }
            }
        }
        String apk = i.getStringExtra("apk");
        if (apk != null) new File(apk).delete();
        if (st != PackageInstaller.STATUS_SUCCESS && st != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            synchronized (Installer.class) {
                Installer.ERROR.put(pkg, st == PackageInstaller.STATUS_FAILURE_ABORTED ? "キャンセルしました"
                        : st == PackageInstaller.STATUS_FAILURE_CONFLICT ? "署名が違う同名アプリが入っています"
                        : msg != null ? msg : "インストールできませんでした");
            }
        }
        Installer.set(pkg, null);
    }
}
