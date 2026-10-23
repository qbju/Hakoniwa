package io.github.qbju.hakoniwa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** マイアプリ：端末に入っている Hakoniwa 収録アプリの管理（更新・まとめて更新・開く・アンインストール）。 */
public class MyAppsActivity extends Activity implements Installer.Listener {
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("マイアプリ");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body);
        setContentView(sv);
        if (Repo.cached() == null) {
            body.addView(new ProgressBar(this), new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));
            Ui.IO.execute(() -> {
                String err = null;
                try { Repo.load(this); } catch (Exception e) { err = e.getMessage(); }
                final String fe = err;
                Ui.MAIN.post(() -> { if (fe != null && Repo.cached() == null) message("読み込めませんでした: " + fe); else build(); });
            });
        }
    }

    @Override protected void onResume() { super.onResume(); Installer.LISTENERS.add(this); if (Repo.cached() != null) build(); }
    @Override protected void onPause() { super.onPause(); Installer.LISTENERS.remove(this); }
    @Override public void changed(String pkg) { build(); }

    @Override
    public boolean onOptionsItemSelected(MenuItem i) {
        if (i.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(i);
    }

    private void message(String s) {
        body.removeAllViews();
        TextView t = Ui.text(this, s, 15, Ui.mut(this), false);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p * 2, p, p);
        t.setGravity(Gravity.CENTER);
        body.addView(t);
    }

    private static boolean outdated(Context c, Repo.App a) {
        long iv = Ui.installed(c, a.pkg);
        return iv >= 0 && a.latest() != null && iv < a.latest().code;
    }

    private boolean signerMismatch(Repo.App a) {
        if (a.signer == null || a.signer.isEmpty()) return false;
        String s = Ui.installedSigner(this, a.pkg);
        return s != null && !s.equalsIgnoreCase(a.signer);
    }

    private void build() {
        List<Repo.App> c = Repo.cached();
        if (c == null) return;
        List<Repo.App> ups = new ArrayList<>(), rest = new ArrayList<>();
        for (Repo.App a : c) {
            if (Ui.installed(this, a.pkg) < 0) continue;
            if (outdated(this, a) && !signerMismatch(a)) ups.add(a); else rest.add(a);
        }
        Collections.sort(ups, (l, r) -> l.name.compareToIgnoreCase(r.name));
        Collections.sort(rest, (l, r) -> l.name.compareToIgnoreCase(r.name));
        if (ups.isEmpty() && rest.isEmpty()) { message("Hakoniwa から入れたアプリはまだありません。\n一覧からアプリを選んでインストールしてください。"); return; }
        body.removeAllViews();
        int p = Ui.dp(this, 16);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(p, p, p, p / 2);
        long total = 0;
        for (Repo.App a : ups) total += a.latest().size;
        TextView ht = Ui.text(this, ups.isEmpty() ? "すべて最新です" : "更新できるアプリ " + ups.size() + " 件（" + Ui.mib(total) + "）", 15, Ui.fg(this), true);
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        if (!ups.isEmpty()) {
            Button all = Ui.button(this, "すべて更新", true);
            all.setTextSize(13);
            boolean any = false;
            for (Repo.App a : ups) if (!Installer.busy(a.pkg)) any = true;
            all.setEnabled(any);
            all.setOnClickListener(v -> { for (Repo.App a : ups) if (!Installer.busy(a.pkg)) MainActivity.startInstall(this, a, a.latest()); });
            head.addView(all, new LinearLayout.LayoutParams(-2, Ui.dp(this, 36)));
        }
        body.addView(head);

        if (!ups.isEmpty()) { section("更新あり"); for (Repo.App a : ups) row(a); }
        section("インストール済み " + rest.size());
        for (Repo.App a : rest) row(a);
    }

    private void section(String s) {
        TextView t = Ui.text(this, s, 13, Ui.BLUE, true);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p / 3);
        body.addView(t);
    }

    private void row(Repo.App a) {
        Context c = this;
        int p = Ui.dp(c, 12);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(p, p, p, p);
        row.setOnClickListener(v -> startActivity(new Intent(c, DetailActivity.class).putExtra("pkg", a.pkg)));
        ImageView icon = new ImageView(c);
        Ui.image(icon, a.icon, Ui.dp(c, 48));
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(p, 0, p, 0);
        col.addView(Ui.text(c, a.name, 16, Ui.fg(c), true));
        String in = Ui.installedName(c, a.pkg);
        Repo.Version v = a.latest();
        String err;
        synchronized (Installer.class) { err = Installer.ERROR.get(a.pkg); }
        String meta;
        if (err != null) meta = "⚠ " + err;
        else if (signerMismatch(a)) meta = "⚠ 別の署名で入っているため更新できません（v" + in + "）";
        else if (outdated(c, a)) meta = "v" + in + " → v" + v.name + " ・ " + Ui.mib(v.size);
        else if (Ui.installed(c, a.pkg) > v.code) meta = "v" + in + "（リポジトリより新しい版）";
        else meta = "v" + in + " ・ 最新";
        TextView mt = Ui.text(c, meta, 13, err != null || signerMismatch(a) ? 0xFFD32F2F : Ui.mut(c), false);
        col.addView(mt);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout btns = new LinearLayout(c);
        btns.setOrientation(LinearLayout.VERTICAL);
        Button act = Ui.button(c, "", true);
        act.setTextSize(13);
        MainActivity.bindAction(this, act, a);
        if (signerMismatch(a) && outdated(c, a)) {
            act.setText("開く");
            act.setOnClickListener(x -> { Intent li = getPackageManager().getLaunchIntentForPackage(a.pkg); if (li != null) startActivity(li); });
        }
        btns.addView(act, new LinearLayout.LayoutParams(-1, Ui.dp(c, 36)));
        if (!a.pkg.equals(getPackageName())) {
            Button del = Ui.button(c, "削除", false);
            del.setTextSize(13);
            del.setOnClickListener(x -> {
                try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))); } catch (Exception ignored) { }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 36));
            lp.topMargin = Ui.dp(c, 6);
            btns.addView(del, lp);
        }
        row.addView(btns, new LinearLayout.LayoutParams(Ui.dp(c, 104), -2));
        body.addView(row);
        View line = new View(c);
        line.setBackgroundColor(Ui.line(c));
        body.addView(line, new LinearLayout.LayoutParams(-1, 1));
    }
}
