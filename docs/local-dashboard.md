# ローカル Web ダッシュボード

`web-ui` は時計から WebSocket でデータを受信し、端末ごとの最新値、グラフ、受信履歴を表示する Node.js サーバーである。各利用者が自分の PC で起動する。

## 1. PC 側サーバーを起動する

Node.js 20 以上を使用する。プロジェクトルートから実行する。初回は `npm ci` で依存関係をインストールする。

```shell
cd web-ui
npm ci
npm start
```

PC のブラウザで `http://localhost:8080` を開く。時計アプリはポート `8080` に接続するため、この構成では `PORT` を変更しない。

## 2. PC の IP アドレスを確認する

PC と時計を、端末間通信が許可された同じ Wi-Fi に接続する。PC の IPv4 アドレスを確認する。

```shell
# macOS で Wi-Fi が en0 の場合
ipconfig getifaddr en0
```

Windows では `ipconfig`、Linux では `hostname -I` などで確認できる。`192.168.1.100` のような IPv4 アドレスを控える。`localhost` や `127.0.0.1` は時計から見た時計自身を指すため、入力しない。

## 3. 時計に PC の IP を設定する

共通の APK を時計へインストールする。時計で **Wear Health Data Hub** を開き、**PC の IP を設定**を押す。PC の IPv4 アドレスだけを入力し、確定する。`ws://`、`:8080`、`/ingest` は入力しない。アプリが `ws://<入力したIP>:8080/ingest` を組み立て、時計内に保存する。PC の IP が変わったときは **PC の IP を変更**から再設定する。

Android Emulator では `10.0.2.2` を入力する。APK のインストール方法は [Wear OS 実機への ADB インストール](wear-adb-install.md)を参照する。

## 4. ブラウザと時計を紐付ける

1. 時計で **Wear Health Data Hub** を開き、必要な権限を許可する。
2. 時計に表示された 8 桁のコードを PC のダッシュボードに入力する。
3. 時計からデータが届くと、紐付けた端末の最新値とグラフが更新される。**受信データを JSON で保存**から、サーバーが保持している履歴をダウンロードできる。

コードは一度だけ使用でき、10 分ごとに更新される。ブラウザは紐付け情報を保存するため、通常の再読み込みでは再入力は不要である。サーバーを再起動すると紐付けと受信履歴が消えるため、時計を開いて新しいコードで紐付け直す。

時計はインストール後の初回起動時に端末固有の認証情報を生成・保存する。APK に全員共通のトークンや PC の IP アドレスは埋め込まない。アプリをアンインストールするとこの認証情報と PC の IP 設定が失われ、新しい端末 ID になる。

## データと保持期間

時計は認証後、受信したレコードを `/ingest` に送る。送信データの例を示す。

```json
{
  "type": "health-data",
  "deviceId": "認証情報から導出した匿名の端末ID",
  "sentAt": "2026-09-17T13:00:00.200Z",
  "records": [
    {
      "source": "MEASURE",
      "dataType": "HeartRate",
      "pointType": "sample",
      "value": "81.0",
      "unit": "bpm",
      "endTime": "2026-09-17T13:00:00.123Z",
      "receivedAt": "2026-09-17T13:00:00.190Z",
      "accuracy": "HrAccuracy(sensorStatus=ACCURACY_HIGH)"
    }
  ]
}
```

サーバーは端末と指標ごとに最新値と直近 300 件をメモリに保持する。`MAX_HISTORY_PER_METRIC` で 1〜1000 件に変更できる。JSON ダウンロードには保持中のレコードのみが含まれ、サーバー再起動後の履歴は復元されない。

ダウンロードする JSON は、受信メッセージを並べた配列ではなく、`取得元 → データ型 → 測定値` の階層に整理する。形式の例を示す。

```json
{
  "schemaVersion": 1,
  "deviceId": "端末ID",
  "exportedAt": "2026-10-05T01:00:00.000Z",
  "historyLimitPerMetric": 300,
  "metrics": {
    "MEASURE": {
      "HEART_RATE_BPM": {
        "unit": "bpm",
        "sampleCount": 1,
        "samples": [
          {
            "pointType": "sample",
            "value": 81,
            "rawValue": "81.0",
            "unit": "bpm",
            "startTime": null,
            "endTime": "2026-09-17T13:00:00.123Z",
            "receivedAt": "2026-09-17T13:00:00.190Z",
            "serverReceivedAt": "2026-09-17T13:00:00.200Z",
            "accuracy": "HrAccuracy(sensorStatus=ACCURACY_HIGH)"
          }
        ]
      }
    }
  }
}
```

`value` は通常の数値なら JSON の数値、統計値なら `min`・`max`・`average` を持つオブジェクトにする。安全に変換できない値は文字列のまま残す。`rawValue` には時計から届いた元の文字列を保持する。項目の `unit` は全サンプルで同一の場合に設定し、単位が混在する場合は `null` とする。各サンプルにも個別の単位を残す。

- Measure と Exercise は、時計アプリが受信した直後に送信する。
- Passive は Health Services からまとめて届く場合があり、測定時刻から表示まで遅れることがある。
- 時計の通信切断中はプロセス内のキューに一時保持する。アプリのプロセス終了後の再送は保証されない。

## 通信上の注意

ローカル用の `ws://` は通信を暗号化しない。debug と配布用 APK の両方で平文通信を許可するため、信頼できる LAN 内で使用する。PC のファイアウォールがポート `8080` の受信を遮断している場合は、時計から接続できるように設定する。インターネットにポート `8080` を公開しない。
