package com.starfall.gsadrive.ui

import com.starfall.gsadrive.tr

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.starfall.gsadrive.ui.theme.ThemeMode

@Composable
fun SettingsPage(
    padding: PaddingValues,
    mode: ThemeMode,
    superDark: Boolean,
    showHiddenSystemFiles: Boolean,
    setMode: (ThemeMode) -> Unit,
    setSuperDark: (Boolean) -> Unit,
    setShowHiddenSystemFiles: (Boolean) -> Unit,
    clearCache: () -> Unit
) {
    val context = LocalContext.current
    var showThemeDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Spacer(Modifier.height(20.dp))

        SettingsSectionTitle(tr("Thông báo"))
        SettingsRow(
            title = tr("Cài đặt thông báo"),
            onClick = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                context.startActivity(intent)
            }
        )

        SettingsSectionTitle(tr("Giao diện"))
        SettingsRow(
            title = tr("Chọn giao diện"),
            subtitle = when (mode) {
                ThemeMode.SYSTEM -> tr("Chế độ mặc định của hệ thống")
                ThemeMode.LIGHT -> tr("Sáng")
                ThemeMode.DARK -> tr("Tối")
            },
            onClick = { showThemeDialog = true }
        )
        SettingsSwitchRow(
            title = "Super Dark Mode",
            subtitle = tr("Dùng nền đen thuần khi giao diện tối đang bật"),
            checked = superDark,
            onCheckedChange = setSuperDark
        )

        SettingsSectionTitle(tr("Tệp Hệ Thống"))
        SettingsSwitchRow(
            title = tr("Hiển thị tệp ẩn"),
            subtitle = tr("Hiển thị tệp và thư mục bắt đầu bằng dấu chấm trong Tệp Hệ Thống"),
            checked = showHiddenSystemFiles,
            onCheckedChange = setShowHiddenSystemFiles
        )

        SettingsSectionTitle(tr("Bộ nhớ đệm của tài liệu"))
        SettingsRow(
            title = tr("Xóa bộ nhớ đệm"),
            subtitle = tr("Xóa danh sách tệp và dữ liệu đã lưu trong bộ nhớ đệm"),
            onClick = clearCache
        )

        Spacer(Modifier.height(40.dp))
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(tr("Chọn giao diện")) },
            text = {
                Column {
                    ThemeMode.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    setMode(option)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = mode == option, onClick = null)
                            Text(
                                when (option) {
                                    ThemeMode.SYSTEM -> tr("Mặc định hệ thống")
                                    ThemeMode.LIGHT -> tr("Sáng")
                                    ThemeMode.DARK -> tr("Tối")
                                },
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text(tr("Hủy")) }
            }
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
