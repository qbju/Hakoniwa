"""fdroid/repo/index-v2.json から日本語ストアページ（public/index.html）を生成する。"""
import html
import json
import os
import shutil
from datetime import datetime, timedelta, timezone
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


def jdate(iso):
    return iso[:10] if iso else ""


def main():
    """静的ストアページ。並べ替え・検索・カテゴリ絞り込みはブラウザ内で完結し、サーバー処理は無い。"""
    repo_dir = PUBLIC / "repo"
    idx = json.loads((repo_dir / "index-v2.json").read_text(encoding="utf-8"))
    stats = json.loads((common.ROOT / "stats.json").read_text(encoding="utf-8")) if (common.ROOT / "stats.json").exists() else {}
    redirects = json.loads((common.ROOT / "apk-redirects.json").read_text(encoding="utf-8")) if (common.ROOT / "apk-redirects.json").exists() else {}
    fp = os.environ.get("REPO_FINGERPRINT", "")
    add_url = REPO_URL + (f"?fingerprint={fp}" if fp else "")
    apps, cards, used_cats = [], [], {}
    for pkg, p in idx.get("packages", {}).items():
        meta = p["metadata"]
        versions = list(p.get("versions", {}).values())
        if not versions:
            continue
        latest = max(versions, key=lambda v: v["manifest"]["versionCode"])
        st = stats.get(pkg, {})
        apk_name = latest["file"]["name"].lstrip("/")
        a = {
            "package": pkg,
            "name": loc(meta.get("name")) or pkg,
            "summary": loc(meta.get("summary")),
            "version": latest["manifest"].get("versionName", ""),
            "license": meta.get("license", ""),
            "categories": meta.get("categories", []),
            "source": meta.get("sourceCode", ""),
            "apk": redirects.get(apk_name) or ("repo/" + apk_name),
            "size": latest["file"].get("size", 0),
            "icon": icon_for(pkg, meta, versions, repo_dir),
            "downloads": st.get("downloads", 0),
            "updated": st.get("updated", ""),
            "added": st.get("added", "") or st.get("updated", ""),
        }
        apps.append(a)
        for c in a["categories"]:
            used_cats[c] = used_cats.get(c, 0) + 1
    apps.sort(key=lambda a: a["added"], reverse=True)  # 既定は新着順
    for a in apps:
        e = html.escape
        cats = " ".join(f"<span class=tag>{e(CAT_JA.get(c, c))}</span>" for c in a["categories"])
        cards.append(f"""<article class=app data-name="{e(a['name'].lower())}" data-added="{e(a['added'])}" data-updated="{e(a['updated'])}" data-dl="{a['downloads']}" data-cats="{e(' '.join(a['categories']))}">
<img src="{e(a['icon'])}" alt="" loading=lazy onerror="this.style.visibility='hidden'">
<div><h2>{e(a['name'])}</h2><p>{e(a['summary'])}</p>
<p class=meta>v{e(a['version'])} ・ {e(a['license'])} ・ {a['size'] / 1048576:.1f} MB ・ ⬇ {a['downloads']:,} ・ 更新 {jdate(a['updated'])}</p>
<p>{cats}</p>
<p class=links><a href="{e(a['apk'])}">APK</a> <a href="{e(a['source'])}" rel=noopener>ソース</a></p></div></article>""")
    (PUBLIC / "apps.json").write_text(json.dumps(apps, ensure_ascii=False, indent=1), encoding="utf-8")

    chips = "".join(f'<button class=chip data-cat="{html.escape(c)}">{html.escape(CAT_JA.get(c, c))} {n}</button>'
                    for c, n in sorted(used_cats.items(), key=lambda kv: -kv[1]))
    updated = datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M JST")
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
input{{width:100%;padding:.6em;border-radius:10px;border:1px solid var(--mut);background:var(--card);color:var(--fg);margin:1rem 0 .5rem}}
.bar{{display:flex;gap:.4rem;flex-wrap:wrap;margin:.4rem 0}}
.bar button{{font:inherit;font-size:.85em;border:1px solid var(--mut);background:transparent;color:var(--fg);border-radius:999px;padding:.2em .8em;cursor:pointer}}
.bar button.on{{background:var(--acc);border-color:var(--acc);color:#fff}}
.app{{display:flex;gap:1rem;background:var(--card);border-radius:14px;padding:1rem;margin:.8rem 0}}
.app img{{width:64px;height:64px;border-radius:14px;flex:none}}.app h2{{margin:0;font-size:1.15rem}}.app p{{margin:.2em 0}}
.meta{{color:var(--mut);font-size:.85em}}.tag{{font-size:.75em;border:1px solid var(--mut);border-radius:999px;padding:0 .6em;margin-right:.3em}}
.links a{{margin-right:1em;color:var(--acc)}}footer{{color:var(--mut);font-size:.85em}}#none{{color:var(--mut)}}
</style>
<header><h1>🏝️ Hakoniwa</h1>
<p class=lead>GitHub Releasesで配布されているOSSのAndroidアプリを集めた、F-Droid互換のアプリストアです。APKは開発者本人が署名したもので、Hakoniwaは署名を確認してから載せています。</p>
<div class=add>{qr_svg(add_url)}<div><p>F-Droid / Droid-ify / Neo Store にリポジトリを追加</p>
<p><a class=btn href="fdroidrepos://{html.escape(add_url.split('://', 1)[1])}">アプリで追加</a></p>
<p><code>{html.escape(add_url)}</code></p></div></div></header>
<main><input id=q type=search placeholder="アプリを検索…" aria-label=検索>
<div class=bar id=sort><button class=on data-k=added>新着</button><button data-k=updated>更新順</button><button data-k=dl>ダウンロード数</button><button data-k=name>名前順</button></div>
<div class=bar id=cats><button class=on data-cat="">すべて</button>{chips}</div>
<div id=list>{''.join(cards)}</div>
<p id=none hidden>見つかりませんでした。</p>
{'' if cards else '<p>まだアプリがありません。最初の1本を追加しませんか？</p>'}
</main>
<footer><p>{len(cards)} アプリ ・ 更新 {updated} ・ ダウンロード数はGitHub Releasesの累計</p>
<p>アプリの追加は <a href="https://github.com/qbju/Hakoniwa">GitHub</a> にPRで。収録はレビュー後です。一覧データ: <a href="apps.json">apps.json</a></p></footer>
<script>
const L=document.getElementById('list'),C=[...L.children];let k='added',cat='';
function draw(){{const s=q.value.trim().toLowerCase();
const cmp={{added:(a,b)=>b.dataset.added.localeCompare(a.dataset.added),updated:(a,b)=>b.dataset.updated.localeCompare(a.dataset.updated),
dl:(a,b)=>b.dataset.dl-a.dataset.dl,name:(a,b)=>a.dataset.name.localeCompare(b.dataset.name,'ja')}}[k];
let n=0;C.sort(cmp).forEach(a=>{{const ok=(!s||a.textContent.toLowerCase().includes(s))&&(!cat||a.dataset.cats.split(' ').includes(cat));a.hidden=!ok;n+=ok;L.appendChild(a)}});
none.hidden=n>0||!C.length}}
function tabs(id,f){{document.getElementById(id).onclick=e=>{{const b=e.target.closest('button');if(!b)return;
document.querySelectorAll('#'+id+' button').forEach(x=>x.classList.toggle('on',x===b));f(b);draw()}}}}
tabs('sort',b=>k=b.dataset.k);tabs('cats',b=>cat=b.dataset.cat);q.oninput=draw;
const h=new URLSearchParams(location.search);if(h.get('q')){{q.value=h.get('q');draw()}}
</script>
</html>"""
    (PUBLIC / "index.html").write_text(page, encoding="utf-8")
    print("site ok:", len(cards), "apps")


if __name__ == "__main__":
    PUBLIC.mkdir(exist_ok=True)
    main()
