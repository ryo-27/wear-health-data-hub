# Wear OS 実機への ADB インストール

開発者が PC から Wear OS の時計へ debug APK をインストールし、動作を確認する手順である。Android Studio と Android SDK Platform-Tools（`adb`）を使用する。コマンドはプロジェクトルートで実行する。

現在の debug APK は開発 PC 向けの接続先を含む。研究室への配布には使用しない。配布版の入手方法は [APK のダウンロード手順](apk-download.md)を参照すること。

## 1. 接続先を設定して APK を作る

開発用の `local.properties` に、[ローカルダッシュボードの手順](local-dashboard.md)にある `health.websocket.url` と `health.dashboard.token` を設定する。これらはビルド時に APK へ埋め込まれるため、値を変えたら再ビルドが必要である。`local.properties` は Git 管理対象外である。

Android Studio の **Build → Build Bundle(s) / APK(s) → Build APK(s)**、または次のコマンドで debug APK を作る。

```shell
./gradlew :app:assembleDebug
```

Windows では `gradlew.bat :app:assembleDebug` を実行する。生成先は `app/build/outputs/apk/debug/app-debug.apk` である。

## 2. 時計と PC を ADB で接続する

1. 時計の **設定 → システム → デバイス情報（またはバージョン）→ ビルド番号** を 7 回タップし、開発者向けオプションを有効にする。
2. **設定 → 開発者向けオプション** で **ADB デバッグ** と **ワイヤレスデバッグ** を有効にする。PC と時計を同じ Wi-Fi に接続する。研究室ネットワークで端末間通信が遮断される場合は、接続可能なテスト用ネットワークを使う。
3. 時計の **ワイヤレスデバッグ → ペア設定コードを使用してデバイスをペア設定** を開く。表示された**ペア設定用** IP アドレスとポートで `adb pair` を実行し、時計のペア設定コードを入力する。

   ```shell
   adb pair 192.0.2.10:37123
   ```

4. ペア設定画面を閉じ、ワイヤレスデバッグのメイン画面にある**接続用**ポートで接続する。通常、接続用ポートはペア設定用ポートと異なる。

   ```shell
   adb connect 192.0.2.10:43210
   adb devices
   ```

上記の IP アドレスとポートは例である。時計に表示された値へ置き換えること。ペア設定は初回だけでよいが、Wi-Fi の変更やワイヤレスデバッグの再起動後は `adb connect` をやり直す。

## 3. インストールして確認する

`adb devices` に表示された時計のシリアルを指定してインストールする。複数端末が接続されていても、指定した時計だけが対象となる。

```shell
adb -s 192.0.2.10:43210 install -r app/build/outputs/apk/debug/app-debug.apk
```

このシリアルも例である。実際の表示に置き換えること。`Success` が表示されたら、次の順に確認する。

1. 時計のアプリ一覧から **Wear Health Data Hub** を起動し、必要な権限を許可する。
2. Measure / Passive の登録数と最新値が画面に表示されることを確認する。
3. 対応する運動種別を選び、「運動を開始」を押す。Android Studio の Logcat で `HealthData` タグを確認する。
4. 終了時は「運動を終了」を押す。

Passive は省電力のため即時配信されず、バックグラウンドでは複数件がまとめて届く場合がある。Measure と Exercise は必要な時間だけ使用する。

接続できない場合は、時計と PC の Wi-Fi、時計に表示された接続用ポート、`adb devices` の結果を確認する。

## 配布版との違い

アプリ ID の変更前にインストールした旧版は、現在のアプリとは別アプリとして残る。旧版の保存値と権限設定は引き継がれない。

継続配布には公開済みの `wss://` 送信先と署名済みの release APK を用いる。同じアプリを更新するには同じ署名鍵を使い、`versionCode` を増やす。署名鍵とパスワードを Git に追加しないこと。公式の [Wear OS ワイヤレスデバッグ手順](https://developer.android.com/training/wearables/get-started/debug-wifi)と [APK 署名手順](https://developer.android.com/studio/publish/app-signing)も参照すること。
