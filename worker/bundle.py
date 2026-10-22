"""dist（ストアページ・署名済みindex・アイコン）を Worker スクリプトに埋め込む entry.mjs を生成する。
Workers Static Assets のアップロードはJWT認証が必要なため、静的ファイルをWorkerモジュールとして同梱して配信する。
使い方: dist のルートで python3 _worker/bundle.py → _worker/build/ に entry.mjs と wrangler.toml"""
import json, mimetypes, os, shutil
from pathlib import Path

ROOT = Path.cwd()
OUT = ROOT / "_worker" / "build"
shutil.rmtree(OUT, ignore_errors=True)
(OUT / "f").mkdir(parents=True)
TYPES = {".jar": "application/java-archive", ".json": "application/json; charset=utf-8",
         ".html": "text/html; charset=utf-8", ".png": "image/png", ".svg": "image/svg+xml",
         ".xml": "application/xml", ".txt": "text/plain; charset=utf-8", ".webp": "image/webp"}
imports, table = [], []
for i, p in enumerate(sorted(x for x in ROOT.rglob("*") if x.is_file())):
    rel = p.relative_to(ROOT).as_posix()
    if rel.startswith(("_worker/", ".git")) or rel.endswith(".apk"):
        continue
    dst = OUT / "f" / f"{i}.bin"
    shutil.copyfile(p, dst)
    imports.append(f'import f{i} from "./f/{i}.bin";')
    ct = TYPES.get(p.suffix.lower()) or mimetypes.guess_type(p.name)[0] or "application/octet-stream"
    table.append(f'  {json.dumps("/" + rel)}: [f{i}, {json.dumps(ct)}],')
redirects = json.loads((ROOT / "apk-redirects.json").read_text()) if (ROOT / "apk-redirects.json").exists() else {}
(OUT / "entry.mjs").write_text("\n".join(imports) + f"""
const FILES = {{
{chr(10).join(table)}
}};
const APK = {json.dumps(redirects)};
export default {{
  async fetch(req) {{
    if (req.method !== "GET" && req.method !== "HEAD") return new Response("method not allowed", {{ status: 405 }});
    let path = decodeURIComponent(new URL(req.url).pathname);
    if (path.startsWith("/repo/") && path.endsWith(".apk")) {{
      const t = APK[path.slice(6)];
      return t ? Response.redirect(t, 302) : new Response("not found", {{ status: 404 }});
    }}
    if (path.endsWith("/")) path += "index.html";
    const f = FILES[path] || FILES[path + "/index.html"];
    if (!f) return new Response("not found", {{ status: 404 }});
    const cache = path.endsWith(".html") || path.includes("/repo/index") || path.endsWith("entry.jar") || path.endsWith(".json") ? "public, max-age=300" : "public, max-age=86400";
    return new Response(req.method === "HEAD" ? null : f[0], {{ headers: {{ "content-type": f[1], "cache-control": cache, "access-control-allow-origin": "*" }} }});
  }},
}};
""")
(OUT / "wrangler.toml").write_text('''name = "hakoniwa"
main = "entry.mjs"
compatibility_date = "2026-10-01"
workers_dev = true
no_bundle = false

[[rules]]
type = "Data"
globs = ["**/*.bin"]
fallthrough = false
''')
print(len(table), "files embedded")
