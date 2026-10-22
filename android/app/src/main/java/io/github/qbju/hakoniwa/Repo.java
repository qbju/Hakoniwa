package io.github.qbju.hakoniwa;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Hakoniwa リポジトリ（F-Droid index v2）の取得と署名検証。 */
public final class Repo {
    public static final String BASE = "https://hakoniwa.i2p.workers.dev/repo";
    /** Hakoniwa リポジトリ署名証明書の SHA-256。entry.jar がこの鍵で署名されていなければ読み込まない。 */
    public static final String FINGERPRINT = "9D17775529B637EEC917944DE8904A2599C4DE30AA61E1C77897F02DCB9F9837";

    public static final class Version {
        public String name, file, sha256, abis;
        public long code, size, added;
        public int minSdk;
    }

    public static final class App {
        public String pkg, name, summary, description, license, author, authorWeb, website, source, issues, changelog, icon, signer;
        public List<String> categories = new ArrayList<>();
        public List<String> screenshots = new ArrayList<>();
        public List<Version> versions = new ArrayList<>();
        public long added, updated;

        public Version latest() { return versions.isEmpty() ? null : versions.get(0); }
    }

    private static List<App> cache;

    public static synchronized List<App> cached() { return cache; }

    public static App find(String pkg) {
        List<App> c = cached();
        if (c != null) for (App a : c) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /** entry.jar を検証してから index-v2.json を取得・ハッシュ確認・解析する。 */
    public static synchronized List<App> load(Context ctx) throws Exception {
        byte[] jar = get(BASE + "/entry.jar", 1 << 20);
        JSONObject entry = new JSONObject(verifiedEntry(ctx, jar));
        JSONObject idx = entry.getJSONObject("index");
        byte[] body = get(BASE + idx.getString("name"), 32 << 20);
        if (!hex(MessageDigest.getInstance("SHA-256").digest(body)).equalsIgnoreCase(idx.getString("sha256")))
            throw new IOException("インデックスのハッシュが一致しません");
        List<App> apps = parse(new JSONObject(new String(body, StandardCharsets.UTF_8)));
        cache = apps;
        return apps;
    }

    /** entry.jar の JAR 署名を検証し、FINGERPRINT の鍵で署名された entry.json の中身を返す。 */
    static String verifiedEntry(Context ctx, byte[] jar) throws Exception {
        File tmp = File.createTempFile("entry", ".jar", ctx.getCacheDir());
        try {
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(jar); }
            try (JarFile jf = new JarFile(tmp, true)) {
                JarEntry e = jf.getJarEntry("entry.json");
                if (e == null) throw new IOException("entry.json がありません");
                byte[] data;
                try (InputStream in = jf.getInputStream(e)) { data = readAll(in, 1 << 20); } // 読み切ると署名が検証される
                CodeSigner[] signers = e.getCodeSigners();
                if (signers == null || signers.length == 0) throw new SecurityException("entry.jar が署名されていません");
                for (CodeSigner s : signers) {
                    Certificate c = s.getSignerCertPath().getCertificates().get(0);
                    if (hex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded())).equalsIgnoreCase(FINGERPRINT))
                        return new String(data, StandardCharsets.UTF_8);
                }
                throw new SecurityException("リポジトリの署名鍵が一致しません");
            }
        } finally {
            tmp.delete();
        }
    }

    static List<App> parse(JSONObject root) throws Exception {
        List<App> out = new ArrayList<>();
        JSONObject pkgs = root.optJSONObject("packages");
        if (pkgs == null) return out;
        for (Iterator<String> it = pkgs.keys(); it.hasNext(); ) {
            String pkg = it.next();
            JSONObject p = pkgs.getJSONObject(pkg), m = p.getJSONObject("metadata");
            App a = new App();
            a.pkg = pkg;
            a.name = loc(m.opt("name"), pkg);
            a.summary = loc(m.opt("summary"), "");
            a.description = loc(m.opt("description"), "").trim();
            a.license = m.optString("license", "");
            a.author = m.optString("authorName", "");
            a.authorWeb = m.optString("authorWebSite", "");
            a.website = m.optString("webSite", "");
            a.source = m.optString("sourceCode", "");
            a.issues = m.optString("issueTracker", "");
            a.changelog = m.optString("changelog", "");
            a.signer = m.optString("preferredSigner", "");
            a.added = m.optLong("added");
            a.updated = m.optLong("lastUpdated");
            Object ic = locObj(m.opt("icon"));
            if (ic instanceof JSONObject) a.icon = BASE + ((JSONObject) ic).optString("name");
            JSONArray cats = m.optJSONArray("categories");
            if (cats != null) for (int i = 0; i < cats.length(); i++) a.categories.add(cats.getString(i));
            JSONObject shots = m.optJSONObject("screenshots");
            if (shots != null) {
                Object ph = locObj(shots.opt("phone"));
                if (ph instanceof JSONArray) for (int i = 0; i < ((JSONArray) ph).length(); i++)
                    a.screenshots.add(BASE + ((JSONArray) ph).getJSONObject(i).optString("name"));
            }
            JSONObject vs = p.optJSONObject("versions");
            if (vs != null) for (Iterator<String> vi = vs.keys(); vi.hasNext(); ) {
                JSONObject v = vs.getJSONObject(vi.next()), f = v.getJSONObject("file"), mf = v.getJSONObject("manifest");
                Version x = new Version();
                x.name = mf.optString("versionName");
                x.code = mf.optLong("versionCode");
                x.file = f.getString("name");
                x.sha256 = f.getString("sha256");
                x.size = f.optLong("size");
                x.added = v.optLong("added");
                JSONObject sdk = mf.optJSONObject("usesSdk");
                x.minSdk = sdk == null ? 1 : sdk.optInt("minSdkVersion", 1);
                JSONArray nc = mf.optJSONArray("nativecode");
                StringBuilder sb = new StringBuilder();
                if (nc != null) for (int i = 0; i < nc.length(); i++) sb.append(i > 0 ? ", " : "").append(nc.getString(i));
                x.abis = sb.toString();
                a.versions.add(x);
            }
            Collections.sort(a.versions, (l, r) -> Long.compare(r.code, l.code));
            if (!a.versions.isEmpty()) out.add(a);
        }
        Collections.sort(out, (l, r) -> Long.compare(r.added, l.added));
        return out;
    }

    static Object locObj(Object v) {
        if (!(v instanceof JSONObject)) return v;
        JSONObject o = (JSONObject) v;
        String lang = Locale.getDefault().getLanguage();
        for (String k : new String[]{lang, "ja", "ja-JP", "en-US", "en"}) if (o.has(k)) return o.opt(k);
        Iterator<String> it = o.keys();
        return it.hasNext() ? o.opt(it.next()) : null;
    }

    static String loc(Object v, String def) {
        Object o = locObj(v);
        return o instanceof String && !((String) o).isEmpty() ? (String) o : def;
    }

    public static byte[] get(String url, int max) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) { return readAll(in, max); } finally { c.disconnect(); }
    }

    public static HttpURLConnection open(String url) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Hakoniwa-Android/" + BuildConfigLite.VERSION);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                URL next = new URL(new URL(url), loc);
                if (!"https".equals(next.getProtocol())) throw new IOException("https 以外へのリダイレクト");
                url = next.toString();
                continue;
            }
            if (code != 200) { c.disconnect(); throw new IOException("HTTP " + code); }
            return c;
        }
        throw new IOException("リダイレクトが多すぎます");
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
            o.write(b, 0, n);
            if (o.size() > max) throw new IOException("大きすぎます");
        }
        return o.toByteArray();
    }

    public static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    static final class BuildConfigLite { static final String VERSION = "0.1"; }
}
