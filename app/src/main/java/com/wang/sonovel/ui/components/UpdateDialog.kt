package com.wang.sonovel.ui.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wang.sonovel.BuildConfig
import com.wang.sonovel.core.UpdateInfo

/**
 * 新版本提示。强制更新时不可关闭，只能更新或退出应用。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
) {
    val context = LocalContext.current
    fun openDownload() {
        val ok = info.downloadUrl.startsWith("http") && runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) Toast.makeText(context, "无法打开下载地址", Toast.LENGTH_SHORT).show()
    }
    AlertDialog(
        onDismissRequest = { if (!info.forceUpdate) onLater() },
        properties = DialogProperties(dismissOnBackPress = !info.forceUpdate, dismissOnClickOutside = !info.forceUpdate),
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "当前版本 ${BuildConfig.VERSION_NAME}" + if (info.forceUpdate) "，此版本需要更新后才能继续使用" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info.forceUpdate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.updateMessage.isNotBlank()) {
                    Text(info.updateMessage, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openDownload()
                // 强制更新时保留弹窗，从浏览器返回后仍需更新
                if (!info.forceUpdate) onLater()
            }) { Text("立即更新") }
        },
        dismissButton = {
            Row {
                if (info.forceUpdate) {
                    TextButton(onClick = { (context as? Activity)?.finishAffinity() }) { Text("退出应用") }
                } else {
                    TextButton(onClick = onIgnore) { Text("忽略此版本") }
                    TextButton(onClick = onLater) { Text("稍后") }
                }
            }
        },
    )
}
