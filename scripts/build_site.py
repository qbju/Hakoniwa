"""fdroid/repo/index-v2.json から日本語ストアページ（public/index.html）を生成する。"""
import html
import json
import os
import shutil
from datetime import datetime, timezone
from pathlib import Path

import common

REPO_URL = os.environ.get("REPO_URL", "https://hakoniwa.i2p.workers.dev/repo")
PUBLIC = common.ROOT / "public"
CAT_JA = {
    "Connectivity": "接続", "Development": "開発", "Games": "ゲーム", "Graphics": "グラフィック",
    "Internet": "インターネット", "Money": "お金", "Multimedia": "マルチメディア", "Navigation": "ナビ",
    "Phone & SMS": "電話・SMS", "Reading": "読書", "Science & Education": "学習", "Security": "セキュリティ",
    "Sports & Health": "健康", "System": "システム", "Theming": "テーマ", "Time": "時間", "Writing": "書く",
}


def loc(v):
    if isinstance(v, dict):
        return v.get("ja") or v.get("ja-JP") or v.get("en-US") or next(iter(v.values()), "")
    return v or ""


def icon_for(pkg, meta, versions, repo_dir: Path):
    ic = meta.get("icon")
    if ic:
        name = loc(ic).get("name") if isinstance(loc(ic), dict) else None
        if name:
            return "repo" + name
    for d in ("icons-640", "icons-480", "icons-320", "icons"):
        for vc in sorted((v["manifest"]["versionCode"] for v in versions), reverse=True):
            p = repo_dir / d / f"{pkg}.{vc}.png"
            if p.exists():
                return f"repo/{d}/{p.name}"
    return ""


def qr_svg(text):
    try:
        import qrcode
        import qrcode.image.svg
        img = qrcode.make(text, image_factory=qrcode.image.svg.SvgPathImage, box_size=8)
        return img.to_string(encoding="unicode")
    except Exception:  # noqa: BLE001
        return ""


def main():
    repo_dir = PUBLIC / "repo"
    idx = json.loads((repo_dir / "index-v2.json").read_text(encoding="utf-8"))
    fp = os.environ.get("REPO_FINGERPRINT", "")
    add_url = REPO_URL + (f"?fingerprint={fp}" if fp else "")
    cards = []
    for pkg, p in sorted(idx.get("packages", {}).items(), key=lambda kv: loc(kv[1]["metadata"].get("name")).lower()):
        meta = p["metadata"]
        versions = list(p.get("versions", {}).values())
        if not versions:
            continue
        latest = max(versions, key=lambda v: v["manifest"]["versionCode"])
        name = html.escape(loc(meta.get("name")) or pkg)
        summary = html.escape(loc(meta.get("summary")))
        icon = icon_for(pkg, meta, versions, repo_dir)
        cats = " ".join(f"<span class=tag>{html.escape(CAT_JA.get(c, c))}</span>" for c in meta.get("categories", []))
        src = html.escape(meta.get("sourceCode", ""))
        apk = "repo" + latest["file"]["name"]
        size = latest["file"].get("size", 0) / 1048576
        cards.append(f"""<article class=app id="{html.escape(pkg)}">
<img src="{icon}" alt="" loading=lazy onerror="this.style.visibility='hidden'">
<div><h2>{name}</h2><p>{summary}</p>
<p class=meta>v{html.escape(latest['manifest'].get('versionName', ''))} ・ {html.escape(meta.get('license', ''))} ・ {size:.1f} MB</p>
<p>{cats}</p>
<p class=links><a href="{apk}">APK</a> <a href="{src}" rel=noopener>ソース</a></p></div></article>""")

    updated = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M")
    page = f"""<!doctype html><html lang=ja><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>Hakoniwa — OSS Androidアプリの箱庭</title>
<meta name=description content="GitHub Releasesで配布されているOSS Androidアプリを集めたF-Droid互換ストア">
<style>
:root{{--bg:#f6f4ee;--fg:#1d2b1f;--mut:#5b6b5d;--card:#fff;--acc:#2f7d4f}}
@media(prefers-color-scheme:dark){{:root{{--bg:#111712;--fg:#e6efe7;--mut:#9fb0a2;--card:#1a221c;--acc:#6fcf97}}}}
*{{box-sizing:border-box}}body{{margin:0;font:16px/1.6 system-ui,sans-serif;background:var(--bg);color:var(--fg)}}
header,main,footer{{max-width:860px;margin:auto;padding:1rem}}h1{{margin:.2em 0;font-size:2rem}}
.lead{{color:var(--mut)}}.add{{display:flex;gap:1rem;align-items:center;flex-wrap:wrap;background:var(--card);padding:1rem;border-radius:14px}}
.add svg{{width:120px;height:120px;background:#fff;border-radius:8px}}code{{word-break:break-all;font-size:.85em}}
.btn{{display:inline-block;background:var(--acc);color:#fff;padding:.5em 1em;border-radius:999px;text-decoration:none}}
input{{width:100%;padding:.6em;border-radius:10px;border:1px solid var(--mut);background:var(--card);color:var(--fg);margin:1rem 0}}
.app{{display:flex;gap:1rem;background:var(--card);border-radius:14px;padding:1rem;margin:.8rem 0}}
.app img{{width:64px;height:64px;border-radius:14px;flex:none}}.app h2{{margin:0;font-size:1.15rem}}.app p{{margin:.2em 0}}
.meta{{color:var(--mut);font-size:.85em}}.tag{{font-size:.75em;border:1px solid var(--mut);border-radius:999px;padding:0 .6em;margin-right:.3em}}
.links a{{margin-right:1em;color:var(--acc)}}footer{{color:var(--mut);font-size:.85em}}
</style>
<header><h1>🏝️ Hakoniwa</h1>
<p class=lead>GitHub Releasesで配布されているOSSのAndroidアプリを集めた、F-Droid互換のアプリストアです。APKは開発者本人が署名したもので、Hakoniwaは署名を確認してから載せています。</p>
<div class=add>{qr_svg(add_url)}<div><p>F-Droid / Droid-ify / Neo Store にリポジトリを追加</p>
<p><a class=btn href="fdroidrepos://{html.escape(add_url.split('://', 1)[1])}">アプリで追加</a></p>
<p><code>{html.escape(add_url)}</code></p></div></div></header>
<main><input id=q type=search placeholder="アプリを検索…" aria-label=検索>
{''.join(cards) or '<p>まだアプリがありません。最初の1本を追加しませんか？</p>'}
</main>
<footer><p>{len(cards)} アプリ ・ 更新 {updated}</p>
<p>アプリの追加は <a href="https://github.com/qbju/Hakoniwa">GitHub</a> にPRで。収録はレビュー後です。掲載停止の依頼もIssueかPRでどうぞ。</p></footer>
<script>q.oninput=()=>{{const s=q.value.toLowerCase();document.querySelectorAll('.app').forEach(a=>a.hidden=s&&!a.textContent.toLowerCase().includes(s))}}</script>
</html>"""
    (PUBLIC / "index.html").write_text(page, encoding="utf-8")
    print("site ok:", len(cards), "apps")


if __name__ == "__main__":
    PUBLIC.mkdir(exist_ok=True)
    main()
