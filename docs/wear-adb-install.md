# Wear OS 実機での確認とADBインストール

## 実行と確認

1. Health Servicesを搭載したWear OS実機または対応エミュレーターで起動します。
2. 必要な権限を許可します。
3. Measure/Passiveの登録数と最新データが画面に表示されることを確認します。
4. 種別ボタンを押して端末対応の運動を選び、「運動を開始」を押します。
5. Android StudioのLogcatでタグ `HealthData` を検索します。
6. 終了時は必ず「運動を終了」を押します。

Passive配信は省電力のため即時ではなく、バックグラウンドでは複数件がまとめて届く場合があります。MeasureとExerciseはセンサーのサンプリング頻度を上げるため、必要な時間だけ使用してください。

## Wear OS実機へのADBインストール（開発・研究室内テスト）

この手順は、開発者がPCからWear OS端末へAPKを直接インストールする方法です。Google Playからの配布ではありません。Android StudioとAndroid SDK Platform-Tools（`adb`）が必要です。以下のコマンドはプロジェクトルートで実行します。

1. **送信先を設定する。** 開発用の `local.properties` に後述の `health.websocket.url` と `health.dashboard.token` を設定します。現在のAPKにはビルド時の送信先とトークンが埋め込まれるため、URLやトークンを変更したら必ず再ビルドしてください。`local.properties`はGit管理対象外です。
2. **APKを作る。** Android Studioで **Build → Build Bundle(s) / APK(s) → Build APK(s)** を選ぶか、ターミナルで `./gradlew :app:assembleDebug`（Windowsは `gradlew.bat :app:assembleDebug`）を実行します。生成物は `app/build/outputs/apk/debug/app-debug.apk` です。現在のデバッグAPKは個人PCへの接続用なので、研究室向けWeb公開が完了するまで配布しないでください。
3. **時計で開発者向けオプションを有効にする。** 時計の **設定 → システム → デバイス情報（またはバージョン）→ ビルド番号** を7回タップします。続いて **設定 → 開発者向けオプション** で **ADBデバッグ** と **ワイヤレスデバッグ** を有効にします。PCと時計は同じWi-Fiに接続します。研究室ネットワークの端末間通信が遮断されている場合は、接続可能なテスト用ネットワークを使ってください。
4. **最初の一度だけペア設定する。** 時計の **ワイヤレスデバッグ → ペア設定コードを使用してデバイスをペア設定** を開き、表示された「ペア設定用」のIPアドレス・ポートを使います。

   ```shell
   adb pair <時計のIP>:<ペア設定用ポート>
   ```

   プロンプトで時計に表示されたペア設定コードを入力します。
5. **時計へ接続する。** ペア設定画面を閉じ、時計の **ワイヤレスデバッグ** のメイン画面に表示されたIPアドレス・**接続用ポート**を使います。接続用ポートはペア設定用ポートと通常異なります。

   ```shell
   adb connect <時計のIP>:<接続用ポート>
   adb devices
   ```

6. **対象の時計へインストールする。** `adb devices` に表示されたシリアルを指定します。複数端末が接続されていても、指定した時計だけが対象になります。

   ```shell
   adb -s <adb devicesに表示された時計のシリアル> install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   `Success` が出たら時計のアプリ一覧から起動し、必要な権限を許可してください。接続できない場合は、時計とPCのWi-Fi、ワイヤレスデバッグの接続用ポート、`adb devices` の表示を確認します。時計のWi-Fi変更やワイヤレスデバッグ再起動後は `adb connect` をやり直します。

アプリIDを新しい名前に合わせて変更したため、旧版をインストール済みの時計では別アプリとして追加されます。旧版に保存された最新値や権限設定は新しいアプリへ引き継がれません。

研究室へ継続的に配布する際は、公開済みの `wss://` 送信先を使い、自分が保管する署名鍵でリリースAPKを作ります。Android Studioの **Build → Generate Signed Bundle / APK → APK** で署名します。同じアプリを更新するには同じ署名鍵を使い、`versionCode` を増やします。署名鍵とパスワードをGitへ追加しないでください。Android公式の[Wear OSワイヤレスデバッグ手順](https://developer.android.com/training/wearables/get-started/debug-wifi)と[APK署名手順](https://developer.android.com/studio/publish/app-signing)も参照してください。
