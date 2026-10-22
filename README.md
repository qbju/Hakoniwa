# 🏝️ Hakoniwa

GitHub Releasesで配布されているOSSのAndroidアプリを集めた、F-Droid互換のアプリストアです。

- ストア: https://hakoniwa.i2p.workers.dev/
- リポジトリURL（F-Droid / Droid-ify / Neo Store に追加）: `https://hakoniwa.i2p.workers.dev/repo`

## しくみ

1. 開発者や推薦者が `apps/<パッケージ名>.yml` を追加するPRを出す
2. CIがライセンス・APKのパッケージ名・署名証明書を自動チェックしてPRにコメント
3. **メンテナーが人間の目でレビューして承認したものだけ**マージ
4. 6時間ごとに各アプリの最新リリース（2版まで）を取得し、署名が登録済みのものと一致するAPKだけでindexを作って署名・公開
5. APK本体はHakoniwaに置かず、元のGitHub Releaseへリダイレクト（ハッシュは署名済みindexで検証されます）

## アプリを追加する

[CONTRIBUTING.md](CONTRIBUTING.md) を読んで、[テンプレート](.github/app-template.yml) をもとにPRを出してください。

## ライセンス

このリポジトリのスクリプト類は MIT。収録アプリのライセンスは各アプリに従います。
