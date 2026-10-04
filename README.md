# Wear Health Data Hub

Wear OS の Health Services から健康・運動データを取得し、時計とWebダッシュボードで確認するプロジェクトです。現在はローカル環境での収集・可視化まで実装しています。

## 現在できること

- `MeasureClient` で心拍、`PassiveMonitoringClient` で日常データ、`ExerciseClient` で運動中のデータを取得します。対応するデータ型は時計のcapabilitiesと権限によって変わります。
- 時計からWebSocketでデータを送信し、PCで起動したダッシュボードに最新値とグラフを表示します。
- 現在のWebサーバーはローカル開発用です。公開ホストへの配置、利用者ごとの時計の紐付け、JSONダウンロードは未実装です。受信履歴はサーバーのメモリに保持され、再起動で消えます。

## ドキュメント

- [Health Services の取得方法、データ型、権限、JSON形式](docs/health-services.md)
- [Wear OS 実機へのADBインストールと動作確認](docs/wear-adb-install.md)
- [ローカルWebダッシュボードの起動と接続](docs/local-dashboard.md)

公開ダッシュボードと配布用APKが完成した後に、APKのダウンロード・インストール方法を追加します。現在のデバッグAPKには開発時の接続先が埋め込まれるため、研究室配布には使用しません。

## 開発者・利用条件

開発者表記: **ryo-27**。本リポジトリ独自のコード、文書、素材にはオープンソースライセンスを付与していません。詳細は[LICENSE](LICENSE)を参照してください。第三者のライブラリや素材にはそれぞれの条件が適用されます。
