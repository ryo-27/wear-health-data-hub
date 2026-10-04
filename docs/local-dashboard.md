# ローカル Web ダッシュボード

`web-ui` には、時計から WebSocket でデータを受信し、最新値カードと時系列グラフを表示する Node.js サーバーが含まれる。このページは**ローカル開発用**の接続手順である。

## 1. PC 側サーバーを起動する

Node.js 20 以上が必要である。プロジェクトルートで次を実行する。

```shell
cd web-ui
npm install
HEALTH_DASHBOARD_TOKEN="任意の十分に長い共有トークン" npm start
```

起動後、PC のブラウザで `http://localhost:8080` を開き、同じ共有トークンを入力する。ポートを変更する場合は `PORT=9000 npm start` のように指定する。

## 2. 時計アプリの送信先を設定する

Git 管理対象外の `local.properties` に、接続先と共有トークンを追加する。

### Wear OS 実機

```properties
health.websocket.url=ws://192.168.1.100:8080/ingest
health.dashboard.token=任意の十分に長い共有トークン
```

`192.168.1.100` は例であり、開発 PC の LAN IP に置き換える。PC と時計は同じネットワークに接続する。

### Android Emulator

```properties
health.websocket.url=ws://10.0.2.2:8080/ingest
health.dashboard.token=任意の十分に長い共有トークン
```

設定後、時計アプリを再ビルドしてインストールする。環境変数 `HEALTH_WEBSOCKET_URL` と `HEALTH_DASHBOARD_TOKEN` でも指定でき、環境変数が `local.properties` より優先される。

未設定時はエミュレーター向け URL と `development-token` が使われる。実機や共有 LAN ではデフォルトトークンを使用しないこと。

## WebSocket の送信形式

時計アプリは、同じタイミングで届いたレコードを次のエンベロープにまとめて `/ingest` へ送る。

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

サーバーは `deviceId + source + dataType` ごとに最新値と直近 300 件をメモリに保持する。サーバーの再起動で履歴は消える。

- Measure と Exercise は、時計アプリが受信した直後に Web へ送る。
- Passive は Health Services からバッチで届くため、測定時刻から Web 表示まで遅れる場合がある。時計アプリが受信した後の WebSocket 送信は即時に行う。

## 通信上の注意

- WebSocket 接続では共有トークンを検証する。ただし、開発用の `ws://` は通信を暗号化しない。
- 平文通信を許可するのは debug ビルドだけである。インターネットへ公開せず、信頼できる LAN 内で使用すること。
- LAN 外で利用する場合は TLS を設定し、`https://` / `wss://` を使用すること。
- `local.properties` のトークンを Git に追加しないこと。
