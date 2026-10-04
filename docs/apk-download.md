# 配布用 APK のダウンロードとインストール

> **現状:** 配布用 APK は未公開である。[GitHub Releases](https://github.com/ryo-27/wear-health-data-hub/releases)にダウンロード可能な APK はない。端末ごとの紐付けはローカル環境で実装済みである。公開サーバーの用意、送信先の設定、署名と動作確認を終えてから Release の **Assets** に掲載する。

現在の `app-debug.apk` は開発 PC 向けの設定でビルドされるため、研究室メンバーへの配布には使わない。

## APK を PC へダウンロードする

この段階では時計のワイヤレスデバッグは不要である。配布開始後の手順は次のとおりである。

1. PC で [Releases ページ](https://github.com/ryo-27/wear-health-data-hub/releases)を開く。リポジトリが Private の場合は、閲覧権限のある GitHub アカウントでログインする。
2. 利用するバージョンの Release を開き、**Assets** から `.apk` ファイルをダウンロードする。**Source code (zip)** と **Source code (tar.gz)** は APK ではない。
3. Release の説明で対応 Wear OS、必要な権限、ダッシュボードの URL を確認し、ダウンロードした APK のファイル名を控える。

配布用 APK は通常の Git コミットには追加せず、Release Asset として公開する。[GitHub の Release 説明](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)を参照すること。

## PC から Pixel Watch へインストールする

ワイヤレスデバッグが必要なのは、PC から時計へのインストール・更新時である。[ADB 接続手順](wear-adb-install.md)に従って `adb pair` と `adb connect` を済ませる。

macOS / Linux での例を示す。シリアルと APK 名は、`adb devices` の結果と実際のファイル名に置き換えること。

```shell
adb devices
adb -s 192.0.2.10:12345 install -r "$HOME/Downloads/wear-health-data-hub-v1.0.apk"
```

Windows PowerShell のファイル指定には `"$env:USERPROFILE\Downloads\wear-health-data-hub-v1.0.apk"` を使う。`Success` が出たら、時計で **Wear Health Data Hub** を起動し、必要な権限を許可する。時計に表示された 8 桁のコードをダッシュボードへ入力して紐付ける。これは ADB のワイヤレスデバッグ用コードとは別である。

開発用 APK と配布用 APK の署名鍵が異なる場合、上書きインストールは失敗する。この場合は旧アプリをアンインストールしてから配布用 APK をインストールする。ただし、アンインストールすると旧アプリの保存データと権限設定は消える。

## インストール後のデータ送信

インストール後の送信に ADB 接続は不要である。公開版では、時計アプリがビルド時に指定された `wss://` の受信先へ直接送信する構成を予定している。受信にはダッシュボード画面に加え、時計からの接続を受け付けるサーバー側の処理が必要である。

現在の実装には次の制約がある。

- 送信先 URL は APK の `BuildConfig` に埋め込む。端末ごとの認証情報はインストール後に生成する。公開先 URL を確定してから配布用 APK をビルドする。
- 時計のネットワーク接続、権限の許可、Health Services からのデータ配信が送信の条件となる。
- Measure はアプリ画面の終了時に登録を解除する。Passive はバックグラウンドでも配信間隔が一定ではない。
- 通信切断中の未送信キューはプロセス内だけに保持する。プロセス終了後の再送は保証されない。
