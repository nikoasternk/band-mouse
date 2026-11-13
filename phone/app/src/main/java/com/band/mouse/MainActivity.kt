package com.band.mouse

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.band.mouse.service.MouseAccessibilityService
import com.band.mouse.transport.WearableMouseClient
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var mouseClient: WearableMouseClient
    private var connectedBandName by mutableStateOf<String?>(null)
    private var isCheckingConnection by mutableStateOf(true)
    private var isAccessibilityGranted by mutableStateOf(false)
    private var isWriteSettingsGranted by mutableStateOf(false)
    private var isMasterServiceEnabled by mutableStateOf(true)
    private var cursorDpi by mutableIntStateOf(MouseEngine.DEFAULT_DPI)
    private var scrollDpi by mutableIntStateOf(MouseEngine.DEFAULT_DPI)
    private var languageMode by mutableStateOf("auto")

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("bandmouse_prefs", Context.MODE_PRIVATE)
        languageMode = prefs.getString("lang_mode", "auto") ?: "auto"
        cursorDpi = prefs.getInt("cursor_dpi", MouseEngine.DEFAULT_DPI).coerceIn(200, 3200)
        scrollDpi = prefs.getInt("scroll_dpi", MouseEngine.DEFAULT_DPI).coerceIn(200, 3200)
        isMasterServiceEnabled = prefs.getBoolean("master_enabled", true)

        MouseAccessibilityService.cursorDpi = cursorDpi
        MouseAccessibilityService.scrollDpi = scrollDpi
        MouseAccessibilityService.isServiceMasterEnabled = isMasterServiceEnabled

        mouseClient = WearableMouseClient.getInstance(this)
        startWatchSession()

        setContent {
            val localizedContext = remember(languageMode) { createLocalizedContext(this, languageMode) }
            CompositionLocalProvider(LocalContext provides localizedContext) {
                BandMouseTheme {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                                actions = {
                                    TextButton(onClick = {
                                        languageMode = when (languageMode) {
                                            "auto" -> "en"
                                            "en" -> "ru"
                                            else -> "auto"
                                        }
                                        prefs.edit().putString("lang_mode", languageMode).apply()
                                    }) {
                                        Text(languageMode.uppercase(Locale.ROOT), fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        val bandStatusText = when {
                            isCheckingConnection -> stringResource(R.string.status_checking)
                            connectedBandName != null -> stringResource(R.string.status_connected, connectedBandName!!)
                            else -> stringResource(R.string.status_disconnected)
                        }

                        MouseControlScreen(
                            modifier = Modifier.padding(innerPadding),
                            bandStatus = bandStatusText,
                            isAccessibilityGranted = isAccessibilityGranted,
                            isWriteSettingsGranted = isWriteSettingsGranted,
                            isMasterEnabled = isMasterServiceEnabled,
                            cursorDpi = cursorDpi,
                            scrollDpi = scrollDpi,
                            onMasterToggle = { enabled ->
                                isMasterServiceEnabled = enabled
                                prefs.edit().putBoolean("master_enabled", enabled).apply()
                                MouseAccessibilityService.instance?.setMasterEnabled(enabled)
                                MouseAccessibilityService.isServiceMasterEnabled = enabled
                            },
                            onConnectWatch = { startWatchSession() },
                            onOpenAccessibility = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onOpenWriteSettings = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    Uri.parse("package:$packageName")
                                )
                                startActivity(intent)
                            },
                            onCursorDpiChange = { value ->
                                val clamped = value.coerceIn(200, 3200)
                                cursorDpi = clamped
                                MouseAccessibilityService.cursorDpi = clamped
                                prefs.edit().putInt("cursor_dpi", clamped).apply()
                            },
                            onScrollDpiChange = { value ->
                                val clamped = value.coerceIn(200, 3200)
                                scrollDpi = clamped
                                MouseAccessibilityService.scrollDpi = clamped
                                prefs.edit().putInt("scroll_dpi", clamped).apply()
                            },
                            onResetDpi = {
                                cursorDpi = MouseEngine.DEFAULT_DPI
                                scrollDpi = MouseEngine.DEFAULT_DPI
                                MouseAccessibilityService.cursorDpi = MouseEngine.DEFAULT_DPI
                                MouseAccessibilityService.scrollDpi = MouseEngine.DEFAULT_DPI
                                prefs.edit()
                                    .putInt("cursor_dpi", MouseEngine.DEFAULT_DPI)
                                    .putInt("scroll_dpi", MouseEngine.DEFAULT_DPI)
                                    .apply()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAccessibilityGranted = MouseAccessibilityService.instance != null
        isWriteSettingsGranted = Settings.System.canWrite(this)
        mouseClient.ensureListening()
    }

    private fun startWatchSession() {
        isCheckingConnection = true
        mouseClient.startMouseSession(
            onConnected = { name ->
                connectedBandName = name
                isCheckingConnection = false
                Toast.makeText(this, getString(R.string.toast_ready), Toast.LENGTH_SHORT).show()
            },
            onError = {
                connectedBandName = null
                isCheckingConnection = false
            }
        )
    }

    private fun createLocalizedContext(base: Context, mode: String): Context {
        val targetLocale = when (mode) {
            "en" -> Locale.ENGLISH
            "ru" -> Locale("ru")
            else -> {
                val sysLang = Locale.getDefault().language.lowercase(Locale.ROOT)
                if (sysLang.startsWith("ru")) Locale("ru") else Locale.ENGLISH
            }
        }
        val config = Configuration(base.resources.configuration)
        config.setLocale(targetLocale)
        return base.createConfigurationContext(config)
    }
}

@Composable
fun BandMouseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun DpiControlBlock(
    label: String,
    dpi: Int,
    onDpiChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onDpiChange((dpi - 100).coerceIn(200, 3200)) }) {
                Text("-100", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { onDpiChange((dpi + 100).coerceIn(200, 3200)) }) {
                Text("+100", fontWeight = FontWeight.Bold)
            }
        }
    }
    Slider(
        value = dpi.toFloat(),
        onValueChange = { raw ->
            val snapped = ((raw / 100f).roundToInt() * 100).coerceIn(200, 3200)
            onDpiChange(snapped)
        },
        valueRange = 200f..3200f,
        steps = 29
    )
}

@Composable
fun MouseControlScreen(
    modifier: Modifier = Modifier,
    bandStatus: String,
    isAccessibilityGranted: Boolean,
    isWriteSettingsGranted: Boolean,
    isMasterEnabled: Boolean,
    cursorDpi: Int,
    scrollDpi: Int,
    onMasterToggle: (Boolean) -> Unit,
    onConnectWatch: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onCursorDpiChange: (Int) -> Unit,
    onScrollDpiChange: (Int) -> Unit,
    onResetDpi: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.master_switch_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.master_switch_sub), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = isMasterEnabled,
                        onCheckedChange = onMasterToggle
                    )
                }

                Text(
                    text = if (isAccessibilityGranted) stringResource(R.string.service_on) else stringResource(R.string.service_off),
                    style = MaterialTheme.typography.labelLarge
                )

                FilledTonalButton(
                    onClick = onOpenAccessibility,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_open_accessibility))
                }

                Text(
                    text = if (isWriteSettingsGranted) stringResource(R.string.brightness_perm_on) else stringResource(R.string.brightness_perm_off),
                    style = MaterialTheme.typography.labelLarge
                )

                if (!isWriteSettingsGranted) {
                    FilledTonalButton(
                        onClick = onOpenWriteSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.btn_open_write_settings))
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.device_status), style = MaterialTheme.typography.labelMedium)
                Text(bandStatus, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = onConnectWatch,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(stringResource(R.string.btn_launch_watch))
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DpiControlBlock(
                    label = stringResource(R.string.label_cursor_dpi, cursorDpi),
                    dpi = cursorDpi,
                    onDpiChange = onCursorDpiChange
                )

                Spacer(modifier = Modifier.height(6.dp))

                DpiControlBlock(
                    label = stringResource(R.string.label_scroll_dpi, scrollDpi),
                    dpi = scrollDpi,
                    onDpiChange = onScrollDpiChange
                )

                TextButton(
                    onClick = onResetDpi,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.btn_reset_dpi))
                }
            }
        }
    }
}
