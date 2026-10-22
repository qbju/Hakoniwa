"""Hakoniwa 共通処理: アプリ定義の読み込み・検証、GitHub Releases、APK検査。"""
import json
import os
import re
import subprocess
import urllib.request
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
APPS_DIR = ROOT / "apps"

# 再配布できるOSSライセンス（SPDX）。必要に応じて追加。
ALLOWED_LICENSES = {
    "Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Zlib", "Unlicense", "0BSD",
    "GPL-2.0-only", "GPL-2.0-or-later", "GPL-3.0-only", "GPL-3.0-or-later",
    "AGPL-3.0-only", "AGPL-3.0-or-later", "LGPL-2.1-only", "LGPL-2.1-or-later",
    "LGPL-3.0-only", "LGPL-3.0-or-later", "MPL-2.0", "EUPL-1.2", "CC0-1.0", "WTFPL",
}
# GitHub API の spdx_id は "GPL-3.0" のように -only/-or-later が付かないことがある
LICENSE_ALIASES = {"GPL-2.0": "GPL-2.0", "GPL-3.0": "GPL-3.0", "AGPL-3.0": "AGPL-3.0",
                   "LGPL-2.1": "LGPL-2.1", "LGPL-3.0": "LGPL-3.0"}

CATEGORIES = {
    "Connectivity", "Development", "Games", "Graphics", "Internet", "Money", "Multimedia",
    "Navigation", "Phone & SMS", "Reading", "Science & Education", "Security", "Sports & Health",
    "System", "Theming", "Time", "Writing",
}

REQUIRED = ["name", "package", "repo", "license", "apk", "signer", "summary", "categories"]
PKG_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")
REPO_RE = re.compile(r"^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")


def license_base(spdx: str) -> str:
    return re.sub(r"-(only|or-later)$", "", spdx or "")


def validate_definition(path_name: str, data) -> list:
    """アプリ定義の形式チェック。エラー文のリストを返す（空ならOK）。"""
    errs = []
    if not isinstance(data, dict):
        return ["YAMLのトップがマッピングになっていません"]
    for k in REQUIRED:
        if k not in data or data[k] in (None, "", []):
            errs.append(f"`{k}` がありません")
    if errs:
        return errs
    pkg = str(data["package"])
    if not PKG_RE.match(pkg):
        errs.append(f"`package` の形式が不正: {pkg}")
    if Path(path_name).name != f"{pkg}.yml":
        errs.append(f"ファイル名は `apps/{pkg}.yml` にしてください")
    if not REPO_RE.match(str(data["repo"])):
        errs.append("`repo` は `owner/name` 形式で書いてください")
    if data["license"] not in ALLOWED_LICENSES:
        errs.append(f"`license` {data['license']} は許可リストにありません（OSSのSPDX IDで）")
    try:
        re.compile(str(data["apk"]))
    except re.error as e:
        errs.append(f"`apk` の正規表現が不正: {e}")
    signers = data["signer"] if isinstance(data["signer"], list) else [data["signer"]]
    for s in signers:
        if not SHA_RE.match(str(s).lower().replace(":", "")):
            errs.append("`signer` は署名証明書のSHA-256（64桁の16進）にしてください")
    cats = data["categories"] if isinstance(data["categories"], list) else [data["categories"]]
    for c in cats:
        if c not in CATEGORIES:
            errs.append(f"カテゴリ `{c}` は使えません（{', '.join(sorted(CATEGORIES))}）")
    if len(str(data["summary"])) > 80:
        errs.append("`summary` は80文字以内で")
    return errs


def normalize_signers(data) -> set:
    s = data["signer"] if isinstance(data["signer"], list) else [data["signer"]]
    return {str(x).lower().replace(":", "") for x in s}


def load_apps():
    apps = []
    for p in sorted(APPS_DIR.glob("*.yml")):
        with open(p, encoding="utf-8") as f:
            apps.append(yaml.safe_load(f))
    return apps


def gh_api(path: str):
    req = urllib.request.Request("https://api.github.com" + path, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "hakoniwa-store",
        **({"Authorization": "Bearer " + os.environ["GITHUB_TOKEN"]} if os.environ.get("GITHUB_TOKEN") else {}),
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def repo_license(repo: str):
    info = gh_api(f"/repos/{repo}")
    lic = (info.get("license") or {}).get("spdx_id")
    return lic, info


def releases_with_apk(app, limit=2):
    """正式リリースのうち、apk正規表現に合う資産があるものを新しい順に limit 件。"""
    pat = re.compile(str(app["apk"]))
    out = []
    for rel in gh_api(f"/repos/{app['repo']}/releases?per_page=20"):
        if rel.get("draft") or (rel.get("prerelease") and not app.get("prerelease")):
            continue
        assets = [a for a in rel.get("assets", []) if pat.search(a["name"])]
        if len(assets) == 1:
            out.append({"tag": rel["tag_name"], "url": assets[0]["browser_download_url"],
                        "asset": assets[0]["name"], "size": assets[0]["size"]})
        if len(out) >= limit:
            break
    return out


def download(url: str, dest: Path, max_bytes=300 * 1024 * 1024):
    req = urllib.request.Request(url, headers={"User-Agent": "hakoniwa-store"})
    with urllib.request.urlopen(req, timeout=300) as r, open(dest, "wb") as f:
        n = 0
        while chunk := r.read(1 << 20):
            n += len(chunk)
            if n > max_bytes:
                raise RuntimeError("APKが大きすぎます (300MB超)")
            f.write(chunk)


def build_tool(name: str) -> str:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        bt = sorted((Path(home) / "build-tools").glob("*/" + name))
        if bt:
            return str(bt[-1])
    return name


def apk_package(apk: Path):
    out = subprocess.run([build_tool("aapt2"), "dump", "badging", str(apk)],
                         capture_output=True, text=True, check=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    if not m:
        raise RuntimeError("aapt2でパッケージ情報を読めません")
    return m.group(1), int(m.group(2)), m.group(3)


def apk_signers(apk: Path) -> set:
    out = subprocess.run([build_tool("apksigner"), "verify", "--print-certs", str(apk)],
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError("署名検証に失敗: " + (out.stderr or out.stdout).strip()[:300])
    return set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", out.stdout))
