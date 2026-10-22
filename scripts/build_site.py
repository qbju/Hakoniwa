"""fdroid/repo/index-v2.json から public/apps.json を生成する。
ストアページ本体（worker/index.html）は静的でWorkerに同梱、ブラウザで apps.json を読んで描画する。"""
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
    (PUBLIC / "apps.json").write_text(json.dumps(apps, ensure_ascii=False, indent=1), encoding="utf-8")

    print("apps.json ok:", len(apps), "apps")

if __name__ == "__main__":
    PUBLIC.mkdir(exist_ok=True)
    main()
