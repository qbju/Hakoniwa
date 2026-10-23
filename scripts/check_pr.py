"""PRで追加・変更された apps/*.yml を検証し、結果をMarkdownで出力する。

pull_request_target で実行されるため、PR側のコードは一切実行しない。
PRのYAMLはAPI経由で文字列として取得し、safe_load で読むだけ。
使い方: python scripts/check_pr.py <owner/repo> <pr番号>  (GITHUB_TOKEN 必須)
"""
import base64
import sys
import tempfile
from pathlib import Path

import yaml

import common


def main():
    repo, pr = sys.argv[1], sys.argv[2]
    pr_info = common.gh_api(f"/repos/{repo}/pulls/{pr}")
    head_repo = pr_info["head"]["repo"]["full_name"]
    head_sha = pr_info["head"]["sha"]
    files = common.gh_api(f"/repos/{repo}/pulls/{pr}/files?per_page=100")

    lines, ok = ["## 🏝️ Hakoniwa 自動チェック", ""], True
    import re
    allowed = re.compile(r"^apps/([^/]+\.yml|[^/]+/screenshots/[^/]+|[^/]+/icon\.png)$")
    others = [f["filename"] for f in files if not allowed.match(f["filename"])]
    if others:
        ok = False
        lines.append("⚠️ アプリ追加PRで変更できるのは `apps/*.yml`・`apps/<パッケージ名>/screenshots/`・`apps/<パッケージ名>/icon.png` だけです: " + ", ".join(f"`{x}`" for x in others))
    targets = [f for f in files if re.match(r"^apps/[^/]+\.yml$", f["filename"]) and f["status"] != "removed"]
    if not targets and not others:
        lines.append("アプリ定義の追加・変更はありません（削除のみ）。レビューしてください。")

    pr_defs, changed_pkgs = [], set()
    for f in files:
        m = re.match(r"^apps/([^/]+)\.yml$", f["filename"])
        if m:
            changed_pkgs.add(m.group(1))
    for f in targets:
        try:
            raw = common.gh_api(f"/repos/{head_repo}/contents/{f['filename']}?ref={head_sha}")
            d = yaml.safe_load(base64.b64decode(raw["content"]).decode("utf-8"))
            if isinstance(d, dict):
                pr_defs.append(d)
        except Exception:  # noqa: BLE001
            pass

    for f in targets:
        name = f["filename"]
        lines += [f"### `{name}`", ""]
        try:
            raw = common.gh_api(f"/repos/{head_repo}/contents/{name}?ref={head_sha}")
            data = yaml.safe_load(base64.b64decode(raw["content"]).decode("utf-8"))
            data = next((d for d in pr_defs if d == data), data)
        except Exception as e:  # noqa: BLE001
            ok = False
            lines.append(f"- ❌ YAMLを読めません: {e}")
            continue
        errs = common.validate_definition(name, data)
        if errs:
            ok = False
            lines += [f"- ❌ {e}" for e in errs]
            continue
        lines.append("- ✅ 形式OK（タイトル・説明・作者・スクリーンショット記載あり）")
        base_apps = [a for a in common.load_apps() if a.get("package") not in changed_pkgs]
        bad = common.author_conflicts(data, base_apps + [d for d in pr_defs if d is not data])
        if bad:
            ok = False
            lines.append(f"- ❌ GitHubアカウント `{common.repo_owner(data)}` の他のアプリと作者名が違います（同じアカウントなら作者名をそろえてください）: "
                         + ", ".join(f"`{p}` は「{a}」" for p, a in bad))
        else:
            lines.append(f"- ✅ 作者名「{data['author']}」（{common.repo_owner(data)} の他のアプリと一致）")
        shot_errs = []
        for sp in common.screenshot_paths(data):
            try:
                c = common.gh_api(f"/repos/{head_repo}/contents/{sp}?ref={head_sha}")
                b = base64.b64decode(c["content"]) if c.get("content") else common.fetch_bytes(c["download_url"])
                e = common.check_image(b, sp)
                if e:
                    shot_errs.append(e)
            except Exception:  # noqa: BLE001
                shot_errs.append(f"`{sp}` が見つかりません")
        if shot_errs:
            ok = False
            lines += [f"- ❌ {e}" for e in shot_errs]
        else:
            lines.append(f"- ✅ スクリーンショット {len(common.screenshot_paths(data))} 枚OK")
        ip = common.icon_path(data)
        try:
            c = common.gh_api(f"/repos/{head_repo}/contents/{ip}?ref={head_sha}")
            b = base64.b64decode(c["content"]) if c.get("content") else common.fetch_bytes(c["download_url"])
            e = common.check_icon(b, ip)
            if e:
                ok = False
                lines.append(f"- ❌ {e}")
            else:
                lines.append(f"- ✅ アイコン `{ip}` OK")
        except Exception:  # noqa: BLE001
            pass  # 任意（無ければAPK内のアイコンを使う）
        try:
            lic, info = common.repo_license(data["repo"])
            if info.get("archived"):
                lines.append("- ⚠️ ソースリポがアーカイブ済みです")
            if lic and common.license_base(lic) == common.license_base(data["license"]):
                lines.append(f"- ✅ ライセンス一致 ({lic})")
            else:
                ok = False
                lines.append(f"- ❌ ライセンス不一致: リポは `{lic}`、定義は `{data['license']}`")
            rels = common.releases_with_apk(data, limit=1)
            if not rels:
                ok = False
                lines.append("- ❌ `apk` に1つだけ一致するAPKを持つ正式リリースが見つかりません")
                continue
            rel = rels[0]
            with tempfile.TemporaryDirectory() as td:
                apk = Path(td) / "app.apk"
                common.download(rel["url"], apk)
                pkg, vc, vn = common.apk_package(apk)
                if pkg == data["package"]:
                    lines.append(f"- ✅ APK `{rel['asset']}` ({rel['tag']}) パッケージ一致 {pkg} v{vn} ({vc})")
                else:
                    ok = False
                    lines.append(f"- ❌ APKのパッケージ名 `{pkg}` が定義と違います")
                got = common.apk_signers(apk)
                if got & common.normalize_signers(data):
                    lines.append("- ✅ 署名証明書が `signer` と一致")
                else:
                    ok = False
                    lines.append(f"- ❌ 署名が一致しません。APKの証明書SHA-256: `{', '.join(sorted(got))}`")
        except Exception as e:  # noqa: BLE001
            ok = False
            lines.append(f"- ❌ チェック中にエラー: {e}")
        lines.append("")

    lines += ["", "**結果: " + ("自動チェック通過。メンテナーのレビュー待ちです。" if ok else "要修正") + "**",
              "", "<sub>自動チェックは補助です。マージには人間のレビュー承認が必要です。</sub>"]
    Path("check-result.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
