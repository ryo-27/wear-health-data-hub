package com.example.wearhealthdatahub.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme

/** Wear Material 3の色・文字スタイルを画面全体へ適用するアプリテーマ。 */
@Composable
fun WearHealthDataHubTheme(
    content: @Composable () -> Unit
) {
    // 現在は標準テーマを使用。独自配色を追加する場合はここでcolorScheme等を指定する。
    MaterialTheme(
        content = content
    )
}