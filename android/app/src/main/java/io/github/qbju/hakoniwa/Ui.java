package io.github.qbju.hakoniwa;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 画面部品・画像読み込み・インストール状態など共通処理。 */
final class Ui {
    static final int BLUE = 0xFF1976D2;
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final ExecutorService IO = Executors.newFixedThreadPool(4);
    static final Map<String, String> CAT_JA = new HashMap<>();
    static {
        String[] c = {"Connectivity", "接続", "Development", "開発", "Games", "ゲーム", "Graphics", "グラフィック",
                "Internet", "インターネット", "Money", "お金", "Multimedia", "マルチメディア", "Navigation", "ナビ",
                "Phone & SMS", "電話・SMS", "Reading", "読書", "Science & Education", "学習", "Security", "セキュリティ",
                "Sports & Health", "健康", "System", "システム", "Theming", "テーマ", "Time", "時間", "Writing", "書く"};
        for (int i = 0; i < c.length; i += 2) CAT_JA.put(c[i], c[i + 1]);
    }

    static String cat(String c) { String j = CAT_JA.get(c); return j == null ? c : j; }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static boolean dark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static int fg(Context c) { return dark(c) ? 0xFFEEEEEE : 0xFF212121; }
    static int mut(Context c) { return dark(c) ? 0xFF9E9E9E : 0xFF757575; }
    static int line(Context c) { return dark(c) ? 0xFF333333 : 0xFFE0E0E0; }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        return t;
    }

    static Button button(Context c, String s, boolean filled) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(filled ? Color.WHITE : BLUE);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 4));
        if (filled) g.setColor(BLUE); else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(c, 1), BLUE); }
        b.setBackground(g);
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        b.setMinHeight(dp(c, 36));
        b.setMinimumHeight(dp(c, 36));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    static String mib(long b) { return String.format(java.util.Locale.US, "%.1f MiB", b / 1048576.0); }

    static String android(int sdk) {
        String[] v = {"", "1.0", "1.1", "1.5", "1.6", "2.0", "2.0.1", "2.1", "2.2", "2.3", "2.3.3", "3.0", "3.1", "3.2", "4.0", "4.0.3",
                "4.1", "4.2", "4.3", "4.4", "4.4W", "5.0", "5.1", "6.0", "7.0", "7.1", "8.0", "8.1", "9", "10", "11", "12", "12L", "13", "14", "15", "16"};
        return sdk > 0 && sdk < v.length ? v[sdk] : "API " + sdk;
    }

    /** インストール済みの versionCode。未インストールなら -1。 */
    static long installed(Context c, String pkg) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    // ---- 画像（メモリ＋ディスクキャッシュ） ----
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(24 << 20) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static void image(ImageView v, String url, int maxPx) {
        v.setTag(url);
        if (url == null || url.isEmpty()) { v.setImageDrawable(null); return; }
        Bitmap hit = MEM.get(url);
        if (hit != null) { v.setImageBitmap(hit); return; }
        v.setImageDrawable(null);
        final Context ctx = v.getContext().getApplicationContext();
        IO.execute(() -> {
            try {
                File f = new File(ctx.getCacheDir(), "img-" + Repo.hex(MessageDigest.getInstance("SHA-1").digest(url.getBytes())));
                if (!f.exists()) {
                    byte[] b = Repo.get(url, 8 << 20);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
                if (bm == null) return;
                MEM.put(url, bm);
                MAIN.post(() -> { if (url.equals(v.getTag())) v.setImageBitmap(bm); });
            } catch (Exception ignored) {
            }
        });
    }
}
