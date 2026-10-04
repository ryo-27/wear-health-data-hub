# Health Services とデータ形式

## 重要な仕様

最初に使っていた `MeasureClient` は、短時間のスポット測定用です。現在の Wear OS 仕様で `MeasureClient` が提供するデータは心拍数（`HEART_RATE_BPM`）だけです。歩数や距離などを同じクライアントから取得することはできないため、このアプリでは用途別に3つのAPIを併用します。

- `MeasureClient`: アプリ表示中の高頻度な心拍測定
- `PassiveMonitoringClient`: 日常データを低頻度・省電力で収集。アプリ終了後はサービスにバッチ配信
- `ExerciseClient`: 選択した運動中の高頻度データと集計値

「全データ」は固定リストを無条件に要求する意味ではありません。機種、Wear OS、Health Services、センサー、選択した運動種別、ユーザーが許可した権限によって対応範囲が変わるため、各クライアントの capabilities を実行時に確認し、実際に対応しているデータ型をすべて登録します。

## データ取得の流れ

1. 起動時に心拍、身体活動、位置情報、バックグラウンド健康データの権限を要求します。
2. 各 Health Services クライアントから端末の capabilities を取得します。
3. 端末対応データ型から、拒否された権限を必要とする型だけを除外します。
4. Measure と Passive の対応型を登録します。
5. 画面で端末対応の運動種別を選び、「運動を開始」を押すと、その種別が対応する全データ型を登録します。
6. callback または `PassiveDataService` に届いた `DataPointContainer` を共通形式へ変換します。
7. 最新値を画面へ表示し、同じ内容を `HealthData` タグの1行JSONとして Logcat に出力します。

Passiveデータの最新値は `SharedPreferences` に保存されるため、バックグラウンドで受信した後にアプリを開いても確認できます。履歴を無期限に保存する実装ではなく、取得元とデータ型ごとの最新値だけを保持します。

## 取得できるデータ

実際の一覧は端末から返される capabilities が正です。Health Services で定義されている主な候補は次のとおりです。

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

例えば、水泳のデータは水泳種別を選んだ場合だけ、ランニングダイナミクスは対応する時計でランニング種別を選んだ場合だけ返ります。センサーが存在しても、端末の Health Services がその型を公開していなければ取得できません。

## Health Servicesから届く形式

コールバックには `DataPointContainer` が届きます。中には次の4形式があります。

- `SampleDataPoint`: ある時点の値。心拍、速度、位置など
- `IntervalDataPoint`: 開始時刻から終了時刻までの増分。歩数、距離など
- `CumulativeDataPoint`: 運動開始からの累積値。合計歩数、合計距離など
- `StatisticalDataPoint`: 期間内の最小・最大・平均。心拍統計、速度統計など

Sample と Interval の時刻は端末起動からの `Duration` で送られます。アプリは `elapsedRealtime()` から起動時刻を求め、ISO-8601の実時刻に変換します。Cumulative と Statistical はAPIから渡された `Instant` をそのまま使います。

アプリ内では次の共通フィールドへ正規化します。

- `source`: `MEASURE`、`PASSIVE`、`EXERCISE`
- `dataType`: Health Servicesのデータ型名
- `pointType`: `sample`、`interval`、`cumulative`、`statistical`
- `value`: 値。位置などの複合値はHealth Servicesオブジェクトの文字列表現
- `unit`: データ型に対応する単位
- `startTime`: 区間・集計の開始時刻。Sampleでは `null`
- `endTime`: 測定時刻または終了時刻
- `receivedAt`: アプリが受信した時刻
- `accuracy`: Health Servicesが提供した場合の精度情報

Logcat出力例:

```json
{"source":"MEASURE","dataType":"HeartRateBpm","pointType":"sample","value":"72.0","unit":"bpm","startTime":null,"endTime":"2026-09-17T03:12:10.123Z","receivedAt":"2026-09-17T03:12:10.200Z","accuracy":"HeartRateAccuracy(...)"}
```

```json
{"source":"PASSIVE","dataType":"StepsDaily","pointType":"interval","value":"4832","unit":"count","startTime":"2026-09-16T15:00:00Z","endTime":"2026-09-17T03:12:10Z","receivedAt":"2026-09-17T03:15:00Z","accuracy":null}
```

```json
{"source":"EXERCISE","dataType":"DistanceTotal","pointType":"cumulative","value":"1250.4","unit":"m","startTime":"2026-09-17T03:00:00Z","endTime":"2026-09-17T03:12:10Z","receivedAt":"2026-09-17T03:12:11Z","accuracy":null}
```

統計値の `value` は `min`、`max`、`average` を含むJSON文字列です。

## 必要な権限

- `BODY_SENSORS`（API 35以下）: 身体センサー由来の健康データ
- API 36以上の詳細権限: `READ_HEART_RATE`、`READ_OXYGEN_SATURATION`、`READ_SKIN_TEMPERATURE`、`READ_RESPIRATORY_RATE`、`READ_HEART_RATE_VARIABILITY`、`READ_VO2_MAX`
- `BODY_SENSORS_BACKGROUND`（API 33〜35）または `READ_HEALTH_DATA_IN_BACKGROUND`（API 36以上）: バックグラウンドの心拍
- `ACTIVITY_RECOGNITION`: 歩数、距離、速度、カロリーなどの活動由来データ
- `ACCESS_COARSE_LOCATION` と `ACCESS_FINE_LOCATION`: 位置と絶対高度。Health ServicesのGPSデータには正確な位置情報が必要

権限を拒否してもアプリ全体は停止せず、その権限が必要なデータ型だけを登録対象から除外します。権限設定を後から変更した場合はアプリを再起動してください。

## 公式資料

- [Health Services on Wear OS](https://developer.android.com/health-and-fitness/health-services)
- [Take spot health measurements with MeasureClient](https://developer.android.com/health-and-fitness/health-services/active-data/measure-client)
- [Record an exercise with ExerciseClient](https://developer.android.com/health-and-fitness/health-services/active-data)
- [Monitor data in the background](https://developer.android.com/health-and-fitness/health-services/monitor-background)
- [Declare appropriate permissions](https://developer.android.com/health-and-fitness/health-services/permissions)
- [Health Services compatibility](https://developer.android.com/health-and-fitness/health-services/compatibility)
- [DataType API reference](https://developer.android.com/reference/androidx/health/services/client/data/DataType)
- [DataPointContainer API reference](https://developer.android.com/reference/kotlin/androidx/health/services/client/data/DataPointContainer)
