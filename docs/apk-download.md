# 共通 APK のダウンロードとインストール

> **現状:** 研究室向けの配布用 APK は未公開である。[GitHub Releases](https://github.com/ryo-27/wear-health-data-hub/releases)にダウンロード可能な APK はない。ローカル接続の実機確認と署名を終えてから掲載する。

共通 APK に特定の PC の IP アドレスは埋め込まない。各利用者は自分の PC で受信サーバーを起動し、時計の画面でその PC の IPv4 アドレスを設定する。PC ごとに APK をビルドする必要はない。

## 1. APK を PC へダウンロードする

この段階では時計のワイヤレスデバッグは不要である。配布開始後の手順は次のとおりである。

1. PC で [Releases ページ](https://github.com/ryo-27/wear-health-data-hub/releases)を開く。リポジトリが Private の場合は、閲覧権限のある GitHub アカウントでログインする。
2. 利用するバージョンの Release を開き、**Assets** から `.apk` ファイルをダウンロードする。**Source code (zip)** と **Source code (tar.gz)** は APK ではない。
3. Release の説明で対応 Wear OS、必要な権限、APK のファイル名を確認する。

配布用 APK は通常の Git コミットには追加せず、Release Asset として公開する。[GitHub の Release 説明](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)も参照すること。

## 2. PC から時計へインストールする

ワイヤレスデバッグは、PC から時計へのインストール・更新時に必要である。[ADB 接続手順](wear-adb-install.md)に従って `adb pair` と `adb connect` を行う。

macOS / Linux での例を示す。シリアルと APK 名は、`adb devices` の結果と実際のファイル名に置き換える。

```shell
adb devices
adb -s 192.0.2.10:12345 install -r "$HOME/Downloads/wear-health-data-hub-v1.0.apk"
```

Windows PowerShell のファイル指定には `"$env:USERPROFILE\Downloads\wear-health-data-hub-v1.0.apk"` を使う。`Success` が表示されたら時計でアプリを開く。

開発用 APK と配布用 APK の署名鍵が異なる場合、上書きインストールは失敗する。旧アプリをアンインストールすると保存値と権限設定は消えるため、必要なデータを先に確認する。

## 3. 各自の PC と紐付ける

1. 自分の PC で [ローカルダッシュボード](local-dashboard.md)に従って `npm ci` と `npm start` を実行する。
2. PC の IPv4 アドレスを確認し、時計の **PC の IP を設定**へ入力する。IP アドレスだけを入力し、`ws://` やポート番号は付けない。
3. 時計に表示される **Web 紐付けコード**の 8 桁を、PC の `http://localhost:8080` に入力する。このコードは ADB のワイヤレスデバッグ用コードとは別である。
4. データ表示と JSON ダウンロードを確認する。

インストール後のデータ送信に ADB 接続は不要である。ただし、PC のサーバーが起動しており、時計から PC のポート `8080` へ接続できる必要がある。PC の IP が変わった場合は時計の **PC の IP を変更**から設定し直す。APK の再インストールは不要である。

## 制限

- ローカル通信は暗号化されないため、信頼できる LAN 内でのみ使用する。
- Measure は時計アプリの画面終了時に登録を解除する。Passive はバックグラウンドでも配信間隔が一定ではない。
- 通信切断中の未送信キューは時計のプロセス内だけに保持する。プロセス終了後の再送は保証されない。
- PC のサーバーを停止すると、保持中の履歴とブラウザの紐付けは失われる。必要な履歴は JSON で保存する。
