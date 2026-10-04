# Health Services とデータ形式

## 重要な仕様

`MeasureClient` は短時間のスポット測定用である。現在の Wear OS 仕様で取得できるデータ型は心拍数（`HEART_RATE_BPM`）だけであり、歩数や距離には使えない。本アプリは用途に応じて次の 3 つの API を併用する。

- **`MeasureClient`**: アプリ表示中の高頻度な心拍測定。
- **`PassiveMonitoringClient`**: 日常データの省電力な収集。アプリ画面がなくてもサービスへバッチ配信される。
- **`ExerciseClient`**: 選択した運動中の高頻度データと集計値。

取得範囲は、機種、Wear OS、Health Services、センサー、運動種別、許可された権限によって変わる。アプリは各クライアントの capabilities を実行時に確認し、対応するデータ型を登録する。「全データ」は固定リストを無条件に要求する意味ではない。

## データ取得の流れ

1. 起動時に心拍、身体活動、位置情報、バックグラウンド健康データの権限を要求する。
2. 各 Health Services クライアントから端末の capabilities を取得する。
3. 対応データ型から、拒否された権限を必要とする型を除外する。
4. Measure と Passive の対応型を登録する。
5. 画面で運動種別を選んで開始すると、その種別が対応するデータ型を登録する。
6. コールバックまたは `PassiveDataService` に届いた `DataPointContainer` を共通形式へ変換する。
7. 最新値を画面へ表示し、`HealthData` タグの 1 行 JSON として Logcat に出力する。

Passive データの最新値は `SharedPreferences` に保存する。バックグラウンドで受信した後にアプリを開いても確認できる。端末内では履歴を無期限に保存せず、取得元とデータ型ごとの最新値だけを保持する。

## 取得できるデータ

実際に取得できる型は端末から返される capabilities に従う。Health Services が定義する主な候補は次のとおりである。

- 心拍: `HEART_RATE_BPM`、最小・最大・平均の統計
- 活動量: `STEPS`、`RUNNING_STEPS`、`WALKING_STEPS`、毎日の歩数、合計歩数
- 移動: `DISTANCE`、`SPEED`、`PACE`、平地・上り・下りの距離
- エネルギー: `CALORIES`、毎日および運動中の合計
- 高度: 絶対高度、獲得・下降標高、階数
- 位置: 緯度、経度、任意の高度・方位
- 運動固有: レップ数、水泳ラップ・ストローク、ゴルフショットなど
- ランニングダイナミクス: ケイデンス、ストライド長、接地時間、上下動、上下動比
- フィットネス推定: `VO2_MAX`
- 時間: アクティブ時間、休憩、上り・平地・下りの継続時間
- 各種集計: 運動開始からの累積値、および最小・最大・平均

水泳データは水泳種別、ランニングダイナミクスは対応する時計でランニング種別を選んだ場合に限られる。センサーが存在しても、端末の Health Services がその型を公開していなければ取得できない。

## Health Servicesから届く形式

コールバックには `DataPointContainer` が届く。格納されるデータポイントには次の 4 形式がある。

- `SampleDataPoint`: ある時点の値。心拍、速度、位置など
- `IntervalDataPoint`: 開始時刻から終了時刻までの増分。歩数、距離など
- `CumulativeDataPoint`: 運動開始からの累積値。合計歩数、合計距離など
- `StatisticalDataPoint`: 期間内の最小・最大・平均。心拍統計、速度統計など

Sample と Interval の時刻は端末起動からの `Duration` で届く。アプリは `elapsedRealtime()` を用いて ISO 8601 の実時刻に変換する。Cumulative と Statistical は API から渡された `Instant` を使う。

アプリ内では次の共通フィールドへ正規化する。

- `source`: `MEASURE`、`PASSIVE`、`EXERCISE`
- `dataType`: Health Servicesのデータ型名
- `pointType`: `sample`、`interval`、`cumulative`、`statistical`
- `value`: 値。位置などの複合値はHealth Servicesオブジェクトの文字列表現
- `unit`: データ型に対応する単位
- `startTime`: 区間・集計の開始時刻。Sampleでは `null`
- `endTime`: 測定時刻または終了時刻
- `receivedAt`: アプリが受信した時刻
- `accuracy`: Health Servicesが提供した場合の精度情報

### Logcat 出力例

Measure のサンプル値:

```json
{
  "source": "MEASURE",
  "dataType": "HeartRateBpm",
  "pointType": "sample",
  "value": "72.0",
  "unit": "bpm",
  "startTime": null,
  "endTime": "2026-09-17T03:12:10.123Z",
  "receivedAt": "2026-09-17T03:12:10.200Z",
  "accuracy": "HeartRateAccuracy(...)"
}
```

Passive の歩数:

```json
{
  "source": "PASSIVE",
  "dataType": "StepsDaily",
  "pointType": "interval",
  "value": "4832",
  "unit": "count",
  "startTime": "2026-09-16T15:00:00Z",
  "endTime": "2026-09-17T03:12:10Z",
  "receivedAt": "2026-09-17T03:15:00Z",
  "accuracy": null
}
```

Exercise の累積距離:

```json
{
  "source": "EXERCISE",
  "dataType": "DistanceTotal",
  "pointType": "cumulative",
  "value": "1250.4",
  "unit": "m",
  "startTime": "2026-09-17T03:00:00Z",
  "endTime": "2026-09-17T03:12:10Z",
  "receivedAt": "2026-09-17T03:12:11Z",
  "accuracy": null
}
```

統計値の `value` は `min`、`max`、`average` を含む JSON 文字列である。実際の Logcat では各レコードを 1 行で出力する。上の例は読みやすさのために整形したものである。

## 必要な権限

- `BODY_SENSORS`（API 35以下）: 身体センサー由来の健康データ
- API 36以上の詳細権限: `READ_HEART_RATE`、`READ_OXYGEN_SATURATION`、`READ_SKIN_TEMPERATURE`、`READ_RESPIRATORY_RATE`、`READ_HEART_RATE_VARIABILITY`、`READ_VO2_MAX`
- `BODY_SENSORS_BACKGROUND`（API 33〜35）または `READ_HEALTH_DATA_IN_BACKGROUND`（API 36以上）: バックグラウンドの心拍
- `ACTIVITY_RECOGNITION`: 歩数、距離、速度、カロリーなどの活動由来データ
- `ACCESS_COARSE_LOCATION` と `ACCESS_FINE_LOCATION`: 位置と絶対高度。Health ServicesのGPSデータには正確な位置情報が必要

権限を拒否してもアプリ全体は停止しない。該当権限を必要とするデータ型だけを登録対象から除外する。権限設定を後から変更した場合は、アプリの再起動が必要である。

## 公式資料

- [Health Services on Wear OS](https://developer.android.com/health-and-fitness/health-services)
- [Take spot health measurements with MeasureClient](https://developer.android.com/health-and-fitness/health-services/active-data/measure-client)
- [Record an exercise with ExerciseClient](https://developer.android.com/health-and-fitness/health-services/active-data)
- [Monitor data in the background](https://developer.android.com/health-and-fitness/health-services/monitor-background)
- [Declare appropriate permissions](https://developer.android.com/health-and-fitness/health-services/permissions)
- [Health Services compatibility](https://developer.android.com/health-and-fitness/health-services/compatibility)
- [DataType API reference](https://developer.android.com/reference/androidx/health/services/client/data/DataType)
- [DataPointContainer API reference](https://developer.android.com/reference/kotlin/androidx/health/services/client/data/DataPointContainer)
