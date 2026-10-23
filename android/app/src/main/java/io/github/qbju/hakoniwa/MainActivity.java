package io.github.qbju.hakoniwa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** アプリ一覧（F-Droid 風）。検索・カテゴリ・並べ替え・インストール/更新ボタン。 */
public class MainActivity extends Activity implements Installer.Listener {
    private final List<Repo.App> all = new ArrayList<>(), shown = new ArrayList<>();
    private String query = "", cat = "", sort = "added";
    private boolean updatesOnly;
    private Adapter adapter;
    private LinearLayout chips;
    private TextView status;
    private ProgressBar spinner;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);

        EditText search = new EditText(this);
        search.setHint("アプリを検索");
        search.setSingleLine(true);
        search.setTextColor(Ui.fg(this));
        search.setHintTextColor(Ui.mut(this));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 4));
        g.setStroke(Ui.dp(this, 1), Ui.line(this));
        search.setBackground(g);
        search.setPadding(p, p * 2 / 3, p, p * 2 / 3);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            public void onTextChanged(CharSequence s, int a, int c, int d) { query = s.toString(); filter(); }
            public void afterTextChanged(Editable e) { }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(p, p, p, p / 2);
        root.addView(search, lp);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setPadding(p, 0, p, p / 2);
        hs.addView(chips);
        root.addView(hs);

        status = Ui.text(this, "", 14, Ui.mut(this), false);
        status.setPadding(p, p / 2, p, p / 2);
        status.setVisibility(View.GONE);
        root.addView(status);
        spinner = new ProgressBar(this);
        root.addView(spinner, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));

        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Ui.line(this)));
        list.setDividerHeight(1);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, pos, id) ->
                startActivity(new Intent(this, DetailActivity.class).putExtra("pkg", shown.get(pos).pkg)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        reload();
    }

    @Override protected void onResume() { super.onResume(); Installer.LISTENERS.add(this); buildChips(); filter(); invalidateOptionsMenu(); }
    @Override protected void onPause() { super.onPause(); Installer.LISTENERS.remove(this); }
    @Override public void changed(String pkg) { buildChips(); filter(); invalidateOptionsMenu(); }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        int up = updateCount();
        m.add(0, 4, 0, up > 0 ? "マイアプリ " + up : "マイアプリ").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        m.add(0, 1, 0, "再読み込み");
        m.add(0, 2, 0, "ウェブ版を開く");
        m.add(0, 3, 0, "アプリを追加する（GitHub）");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem i) {
        if (i.getItemId() == 4) startActivity(new Intent(this, MyAppsActivity.class));
        else if (i.getItemId() == 1) reload();
        else if (i.getItemId() == 2) open("https://hakoniwa.i2p.workers.dev/");
        else if (i.getItemId() == 3) open("https://github.com/qbju/Hakoniwa/blob/main/CONTRIBUTING.md");
        return true;
    }

    private void open(String u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception ignored) { }
    }

    private void reload() {
        spinner.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE);
        Ui.IO.execute(() -> {
            try {
                List<Repo.App> a = Repo.load(this);
                Ui.MAIN.post(() -> { all.clear(); all.addAll(a); spinner.setVisibility(View.GONE); buildChips(); filter(); invalidateOptionsMenu(); });
            } catch (Exception e) {
                Ui.MAIN.post(() -> {
                    spinner.setVisibility(View.GONE);
                    status.setText("読み込めませんでした: " + e.getMessage());
                    status.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private int updateCount() {
        int n = 0;
        for (Repo.App a : all) { long iv = Ui.installed(this, a.pkg); if (iv >= 0 && iv < a.latest().code) n++; }
        return n;
    }

    private void buildChips() {
        if (chips == null) return;
        chips.removeAllViews();
        Map<String, String> sorts = new LinkedHashMap<>();
        sorts.put("added", "新着");
        sorts.put("updated", "更新順");
        sorts.put("name", "名前順");
        for (Map.Entry<String, String> e : sorts.entrySet())
            chip(e.getValue(), sort.equals(e.getKey()) && !updatesOnly, () -> { sort = e.getKey(); updatesOnly = false; buildChips(); filter(); });
        int up = updateCount();
        if (up > 0) chip("更新あり " + up, updatesOnly, () -> { updatesOnly = !updatesOnly; buildChips(); filter(); });
        Map<String, Integer> cats = new LinkedHashMap<>();
        for (Repo.App a : all) for (String c : a.categories) cats.put(c, cats.containsKey(c) ? cats.get(c) + 1 : 1);
        chip("すべて", cat.isEmpty(), () -> { cat = ""; buildChips(); filter(); });
        for (Map.Entry<String, Integer> e : cats.entrySet())
            chip(Ui.cat(e.getKey()) + " " + e.getValue(), cat.equals(e.getKey()), () -> { cat = e.getKey(); buildChips(); filter(); });
    }

    private void chip(String label, boolean on, Runnable r) {
        Button b = Ui.button(this, label, on);
        b.setTextSize(13);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 34));
        lp.setMargins(0, 0, Ui.dp(this, 6), 0);
        chips.addView(b, lp);
    }

    private void filter() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Repo.App a : all) {
            if (!cat.isEmpty() && !a.categories.contains(cat)) continue;
            if (!q.isEmpty() && !(a.name + " " + a.summary + " " + a.pkg).toLowerCase(Locale.ROOT).contains(q)) continue;
            if (updatesOnly) { long iv = Ui.installed(this, a.pkg); if (iv < 0 || iv >= a.latest().code) continue; }
            shown.add(a);
        }
        if (sort.equals("updated")) Collections.sort(shown, (l, r) -> Long.compare(r.updated, l.updated));
        else if (sort.equals("name")) Collections.sort(shown, (l, r) -> l.name.compareToIgnoreCase(r.name));
        else Collections.sort(shown, (l, r) -> Long.compare(r.added, l.added));
        if (adapter != null) adapter.notifyDataSetChanged();
        if (!all.isEmpty() && shown.isEmpty()) { status.setText("見つかりませんでした"); status.setVisibility(View.VISIBLE); }
        else if (!all.isEmpty()) status.setVisibility(View.GONE);
    }

    /** インストール/更新/開く ボタンの状態を設定（一覧と詳細で共通）。 */
    static void bindAction(Activity act, Button btn, Repo.App a) {
        Integer prog;
        synchronized (Installer.class) { prog = Installer.PROGRESS.get(a.pkg); }
        long iv = Ui.installed(act, a.pkg);
        Repo.Version v = a.latest();
        btn.setEnabled(prog == null);
        if (prog != null) {
            btn.setText(prog > 100 ? "インストール中…" : prog + "%");
            btn.setOnClickListener(null);
        } else if (iv < 0 || iv < v.code) {
            btn.setText(iv < 0 ? "インストール" : "更新");
            btn.setOnClickListener(x -> startInstall(act, a, v));
        } else {
            btn.setText("開く");
            btn.setOnClickListener(x -> {
                Intent li = act.getPackageManager().getLaunchIntentForPackage(a.pkg);
                if (li != null) act.startActivity(li);
            });
        }
    }

    static void startInstall(Activity act, Repo.App a, Repo.Version v) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            android.widget.Toast.makeText(act, "「この提供元のアプリを許可」をオンにしてから、もう一度押してください", android.widget.Toast.LENGTH_LONG).show();
            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
            return;
        }
        if (v.minSdk > Build.VERSION.SDK_INT) {
            android.widget.Toast.makeText(act, "Android " + Ui.android(v.minSdk) + " 以上が必要です", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        Installer.install(act, a, v);
    }

    private class Adapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View cv, ViewGroup parent) {
            Context c = MainActivity.this;
            Row r;
            if (cv == null) {
                r = new Row();
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = Ui.dp(c, 12);
                row.setPadding(p, p, p, p);
                r.icon = new ImageView(c);
                row.addView(r.icon, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(p, 0, p, 0);
                r.name = Ui.text(c, "", 16, Ui.fg(c), true);
                r.summary = Ui.text(c, "", 14, Ui.fg(c), false);
                r.summary.setMaxLines(2);
                r.meta = Ui.text(c, "", 12, Ui.mut(c), false);
                col.addView(r.name);
                col.addView(r.summary);
                col.addView(r.meta);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                r.btn = Ui.button(c, "", true);
                r.btn.setFocusable(false);
                r.btn.setTextSize(13);
                row.addView(r.btn, new LinearLayout.LayoutParams(-2, Ui.dp(c, 36)));
                row.setTag(r);
                cv = row;
            } else r = (Row) cv.getTag();
            Repo.App a = shown.get(i);
            Ui.image(r.icon, a.icon, Ui.dp(c, 48));
            r.name.setText(a.name);
            r.summary.setText(a.summary);
            String err;
            synchronized (Installer.class) { err = Installer.ERROR.get(a.pkg); }
            r.meta.setText(err != null ? "⚠ " + err : (a.author.isEmpty() ? "" : a.author + " ・ ") + a.license + " ・ v" + a.latest().name);
            bindAction(MainActivity.this, r.btn, a);
            return cv;
        }
    }

    private static class Row { ImageView icon; TextView name, summary, meta; Button btn; }
}
