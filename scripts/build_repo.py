"""収録アプリの最新リリースを集めて fdroid リポ（未署名ステージ）を組み立てる。

出力:
  fdroid/repo/*.apk           … fdroid update 用（公開時には削除）
  fdroid/metadata/<pkg>.yml   … apps/*.yml から生成
  apk-redirects.json          … {"<apk名>": "<元のGitHub Release URL>"}
  stats.json                  … {"<pkg>": {"downloads", "updated", "added"}}（ストアページの並べ替え用）
署名が定義と違うAPK・パッケージ名が違うAPKは取り込まない（乗っ取り対策）。
"""
import json
import sys
from pathlib import Path

import yaml

import common

OUT = common.ROOT / "fdroid"
REPO = OUT / "repo"
META = OUT / "metadata"
KEEP = 2


def metadata_for(app):
    m = {
        "Name": app["name"],
        "Summary": app["summary"],
        "Description": app.get("description", app["summary"]),
        "License": app["license"],
        "Categories": app["categories"] if isinstance(app["categories"], list) else [app["categories"]],
        "SourceCode": f"https://github.com/{app['repo']}",
        "IssueTracker": f"https://github.com/{app['repo']}/issues",
        "Changelog": f"https://github.com/{app['repo']}/releases",
    }
    if app.get("website"):
        m["WebSite"] = app["website"]
    if app.get("author"):
        m["AuthorName"] = app["author"]
    if app.get("anti_features"):
        m["AntiFeatures"] = app["anti_features"]
    return m


def main():
    REPO.mkdir(parents=True, exist_ok=True)
    META.mkdir(parents=True, exist_ok=True)
    redirects, problems, stats = {}, [], {}
    for app in common.load_apps():
        pkg = app["package"]
        (META / f"{pkg}.yml").write_text(yaml.safe_dump(metadata_for(app), allow_unicode=True, sort_keys=False), encoding="utf-8")
        try:
            rels = common.releases_with_apk(app, limit=KEEP)
        except Exception as e:  # noqa: BLE001
            problems.append(f"{pkg}: リリース取得失敗 {e}")
            continue
        try:
            dl = common.download_count(app)
        except Exception:  # noqa: BLE001
            dl = 0
        stats[pkg] = {"downloads": dl, "updated": rels[0]["published"] if rels else "",
                      "added": common.added_date(pkg)}
        want = common.normalize_signers(app)
        for rel in rels:
            tmp = REPO / f".{pkg}.download"
            try:
                common.download(rel["url"], tmp)
                got_pkg, vc, _ = common.apk_package(tmp)
                if got_pkg != pkg:
                    raise RuntimeError(f"パッケージ名が {got_pkg}")
                if not (common.apk_signers(tmp) & want):
                    raise RuntimeError("署名が定義と一致しない（取り込み拒否）")
                name = f"{pkg}_{vc}.apk"
                tmp.rename(REPO / name)
                redirects[name] = rel["url"]
            except Exception as e:  # noqa: BLE001
                problems.append(f"{pkg} {rel['tag']}: {e}")
                tmp.unlink(missing_ok=True)
    (common.ROOT / "apk-redirects.json").write_text(json.dumps(redirects, indent=1), encoding="utf-8")
    (common.ROOT / "stats.json").write_text(json.dumps(stats, indent=1), encoding="utf-8")
    print(f"APK {len(redirects)} 件を取り込み")
    for p in problems:
        print("::warning::" + p)
    return 0


if __name__ == "__main__":
    sys.exit(main())
