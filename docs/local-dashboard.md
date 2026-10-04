# ローカルWebダッシュボード

`web-ui`には、Wear端末からWebSocketでデータを受信し、最新値カードと時系列グラフを表示するNode.jsサーバーが含まれています。

### 1. PC側サーバーを起動

Node.js 20以上が必要です。プロジェクトルートで次を実行します。

```shell
cd web-ui
npm install
HEALTH_DASHBOARD_TOKEN="任意の十分に長い共有トークン" npm start
```

起動後、PCのブラウザで `http://localhost:8080` を開き、同じ共有トークンを入力します。ポートを変更する場合は `PORT=9000 npm start` のように指定できます。

### 2. Wearアプリの送信先を設定

プロジェクトルートの、Git管理対象外である `local.properties` に次を追加します。

実機の場合:

```properties
health.websocket.url=ws://192.168.1.100:8080/ingest
health.dashboard.token=任意の十分に長い共有トークン
```

`192.168.1.100`は開発PCのLAN IPへ置き換え、PCとWear端末を同じネットワークへ接続してください。

Android Emulatorの場合:

```properties
health.websocket.url=ws://10.0.2.2:8080/ingest
health.dashboard.token=任意の十分に長い共有トークン
```

設定後にWearアプリを再ビルド・再インストールします。値は環境変数 `HEALTH_WEBSOCKET_URL` と `HEALTH_DASHBOARD_TOKEN` でも指定でき、環境変数が `local.properties` より優先されます。

未設定時はエミュレーター向けURLと `development-token` が使われます。実機や共有LANではデフォルトトークンを使用しないでください。

### WebSocket送信形式

Wearアプリは、同じタイミングで届いたレコードを次のエンベロープにまとめて `/ingest` へ送ります。

```json
{
  "type": "health-data",
  "deviceId": "アプリが生成した匿名UUID",
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

サーバーは `deviceId + source + dataType` ごとに最新値と直近300件をメモリ上へ保持します。サーバーを再起動するとWeb側の履歴は消去されます。

MeasureとExerciseのデータはWearアプリが受信した直後にWebUIへ送られます。PassiveデータはHealth Servicesからバッチで届くため、センサー測定時刻からWeb表示まで遅れることがありますが、アプリがバッチを受信した後のWebSocket送信は即時です。

### セキュリティ上の注意

- WebSocket接続では共有トークンを必ず検証しますが、開発用の `ws://` は通信を暗号化しません。
- debugビルドだけ平文通信を許可しています。インターネットへ公開せず、信頼できるLAN内だけで使用してください。
- LAN外や本番環境で使う場合は、TLS終端を設定して `https://` / `wss://` を使用してください。
- `local.properties`へ書いたトークンをソース管理へ追加しないでください。
