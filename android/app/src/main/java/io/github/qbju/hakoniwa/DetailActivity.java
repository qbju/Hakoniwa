package io.github.qbju.hakoniwa;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Date;
import java.util.List;

/** アプリ詳細（F-Droid 風）。スクリーンショット・説明・情報・バージョン一覧・インストール。 */
public class DetailActivity extends Activity implements Installer.Listener {
    private String pkg;
    private Repo.App app;
    private LinearLayout body;
    private Button action;
    private ProgressBar bar;
    private TextView err;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        pkg = getIntent().getStringExtra("pkg");
        Uri u = getIntent().getData();
        if (pkg == null && u != null && u.getPathSegments().size() >= 2) pkg = u.getPathSegments().get(1);
        ScrollView sv = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        body.setPadding(p, p, p, p);
        sv.addView(body);
        setContentView(sv);
        app = Repo.find(pkg);
        if (app != null) build();
        else {
            body.addView(new ProgressBar(this));
            Ui.IO.execute(() -> {
                try { Repo.load(this); } catch (Exception ignored) { }
                Ui.MAIN.post(() -> {
                    app = Repo.find(pkg);
                    body.removeAllViews();
                    if (app != null) build();
                    else body.addView(Ui.text(this, "このアプリは見つかりませんでした。", 16, Ui.fg(this), false));
                });
            });
        }
    }

    @Override protected void onResume() { super.onResume(); Installer.LISTENERS.add(this); refresh(); }
    @Override protected void onPause() { super.onPause(); Installer.LISTENERS.remove(this); }
    @Override public void changed(String p) { if (p.equals(pkg)) refresh(); }

    @Override
    public boolean onOptionsItemSelected(MenuItem i) {
        if (i.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(i);
    }

    private void build() {
        setTitle(app.name);
        int gap = Ui.dp(this, 8);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        Ui.image(icon, app.icon, Ui.dp(this, 72));
        head.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 16), 0, 0, 0);
        col.addView(Ui.text(this, app.name, 24, Ui.fg(this), false));
        col.addView(Ui.text(this, app.summary, 14, Ui.mut(this), false));
        if (!app.author.isEmpty()) {
            TextView au = Ui.text(this, app.author, 13, Ui.BLUE, false);
            if (app.authorWeb.startsWith("https://")) au.setOnClickListener(x -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(app.authorWeb))); } catch (Exception ignored) { }
            });
            col.addView(au);
        }
        head.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(head);

        action = Ui.button(this, "", true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 44));
        alp.setMargins(0, Ui.dp(this, 16), 0, 0);
        body.addView(action, alp);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        body.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        err = Ui.text(this, "", 13, 0xFFD32F2F, false);
        body.addView(err);
        Repo.Version v = app.latest();
        body.addView(Ui.text(this, "v" + v.name + " ・ " + Ui.mib(v.size) + " ・ Android " + Ui.android(v.minSdk) + " 以上", 12, Ui.mut(this), false));

        if (!app.screenshots.isEmpty()) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            for (String s : app.screenshots) {
                ImageView iv = new ImageView(this);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                GradientDrawable g = new GradientDrawable();
                g.setStroke(1, Ui.line(this));
                iv.setBackground(g);
                Ui.image(iv, s, Ui.dp(this, 400));
                iv.setOnClickListener(x -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(s))));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 340));
                lp.setMargins(0, 0, gap, 0);
                row.addView(iv, lp);
            }
            hs.addView(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, Ui.dp(this, 16), 0, 0);
            body.addView(hs, lp);
        }

        LinearLayout tags = new LinearLayout(this);
        tags.setPadding(0, Ui.dp(this, 12), 0, 0);
        for (String c : app.categories) {
            TextView t = Ui.text(this, Ui.cat(c), 12, Ui.mut(this), false);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(this, 2));
            g.setStroke(1, Ui.line(this));
            t.setBackground(g);
            t.setPadding(gap, 2, gap, 2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, gap, 0);
            tags.addView(t, lp);
        }
        body.addView(tags);

        TextView desc = Ui.text(this, app.description, 15, Ui.fg(this), false);
        desc.setPadding(0, Ui.dp(this, 12), 0, 0);
        desc.setLineSpacing(0, 1.2f);
        desc.setTextIsSelectable(true);
        body.addView(desc);

        section("リンク・情報");
        info("作者", app.author, app.authorWeb.isEmpty() ? null : app.authorWeb);
        info("公式サイト", app.website, app.website);
        info("ライセンス", app.license, null);
        info("ソースコード", app.source, app.source);
        info("Issue", app.issues, app.issues);
        info("変更履歴", app.changelog, app.changelog);
        info("ウェブ版", "hakoniwa.i2p.workers.dev/app/" + app.pkg, "https://hakoniwa.i2p.workers.dev/app/" + app.pkg);
        info("パッケージ名", app.pkg, null);

        section("パッケージ");
        List<Repo.Version> vs = app.versions;
        for (int i = 0; i < vs.size(); i++) {
            Repo.Version x = vs.get(i);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable g = new GradientDrawable();
            g.setStroke(1, Ui.line(this));
            box.setBackground(g);
            box.setPadding(Ui.dp(this, 12), gap, Ui.dp(this, 12), gap);
            box.addView(Ui.text(this, "バージョン " + x.name + " (" + x.code + ")" + (i == 0 ? "  最新" : ""), 15, Ui.fg(this), true));
            String date = x.added > 0 ? DateFormat.format("yyyy/MM/dd", new Date(x.added)).toString() : "";
            box.addView(Ui.text(this, "追加日 " + date + " ・ " + Ui.mib(x.size) + " ・ Android " + Ui.android(x.minSdk) + " 以上"
                    + (x.abis.isEmpty() ? "" : "\n" + x.abis) + "\nSHA-256 " + x.sha256.toLowerCase(), 12, Ui.mut(this), false));
            long iv = Ui.installed(this, app.pkg);
            if (iv < x.code) {
                Button bb = Ui.button(this, "このバージョンをインストール", i == 0);
                bb.setOnClickListener(y -> MainActivity.startInstall(this, app, x));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 36));
                lp.setMargins(0, gap, 0, 0);
                box.addView(bb, lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, gap, 0, 0);
            body.addView(box, lp);
        }
        refresh();
    }

    private void section(String s) {
        TextView t = Ui.text(this, s, 17, Ui.fg(this), true);
        t.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 4));
        body.addView(t);
        View line = new View(this);
        line.setBackgroundColor(Ui.line(this));
        body.addView(line, new LinearLayout.LayoutParams(-1, 1));
    }

    private void info(String k, String v, String link) {
        if (v == null || v.isEmpty()) return;
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, Ui.dp(this, 6), 0, 0);
        TextView kt = Ui.text(this, k, 14, Ui.mut(this), false);
        row.addView(kt, new LinearLayout.LayoutParams(Ui.dp(this, 100), -2));
        TextView vt = Ui.text(this, v, 14, link != null ? Ui.BLUE : Ui.fg(this), false);
        vt.setTextIsSelectable(link == null);
        if (link != null && link.startsWith("https://")) vt.setOnClickListener(x -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); } catch (Exception ignored) { }
        });
        row.addView(vt, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(row);
    }

    private void refresh() {
        if (app == null || action == null) return;
        MainActivity.bindAction(this, action, app);
        Integer prog;
        String e;
        synchronized (Installer.class) { prog = Installer.PROGRESS.get(pkg); e = Installer.ERROR.get(pkg); }
        bar.setVisibility(prog == null ? View.GONE : View.VISIBLE);
        if (prog != null) { bar.setIndeterminate(prog > 100); if (prog <= 100) bar.setProgress(prog); }
        err.setText(e == null ? "" : "⚠ " + e);
        err.setVisibility(e == null ? View.GONE : View.VISIBLE);
    }
}
