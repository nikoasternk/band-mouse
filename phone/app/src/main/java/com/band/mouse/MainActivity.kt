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
import androidx.compose.runtime.mutableFloatStateOf
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

class MainActivity : ComponentActivity() {

    private lateinit var mouseClient: WearableMouseClient
    private var connectedBandName by mutableStateOf<String?>(null)
    private var isCheckingConnection by mutableStateOf(true)
    private var isAccessibilityGranted by mutableStateOf(false)
    private var isWriteSettingsGranted by mutableStateOf(false)
    private var isMasterServiceEnabled by mutableStateOf(true)
    private var sensitivity by mutableFloatStateOf(3.0f)
    private var scrollSpeed by mutableFloatStateOf(8.0f)
    private var languageMode by mutableStateOf("auto")

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("bandmouse_prefs", Context.MODE_PRIVATE)
        languageMode = prefs.getString("lang_mode", "auto") ?: "auto"
        sensitivity = prefs.getFloat("sensitivity", 3.0f)
        scrollSpeed = prefs.getFloat("scroll_mult", 8.0f)
        isMasterServiceEnabled = prefs.getBoolean("master_enabled", true)

        MouseAccessibilityService.cursorSensitivity = sensitivity
        MouseAccessibilityService.scrollMultiplier = scrollSpeed
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
                            sensitivity = sensitivity,
                            scrollSpeed = scrollSpeed,
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
                            onSensitivityChange = { value ->
                                sensitivity = value
                                MouseAccessibilityService.cursorSensitivity = value
                                prefs.edit().putFloat("sensitivity", value).apply()
                            },
                            onScrollSpeedChange = { value ->
                                scrollSpeed = value
                                MouseAccessibilityService.scrollMultiplier = value
                                prefs.edit().putFloat("scroll_mult", value).apply()
                            },
                            onResetSpeeds = {
                                sensitivity = 3.0f
                                scrollSpeed = 8.0f
                                MouseAccessibilityService.cursorSensitivity = 3.0f
                                MouseAccessibilityService.scrollMultiplier = 8.0f
                                prefs.edit()
                                    .putFloat("sensitivity", 3.0f)
                                    .putFloat("scroll_mult", 8.0f)
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
fun MouseControlScreen(
    modifier: Modifier = Modifier,
    bandStatus: String,
    isAccessibilityGranted: Boolean,
    isWriteSettingsGranted: Boolean,
    isMasterEnabled: Boolean,
    sensitivity: Float,
    scrollSpeed: Float,
    onMasterToggle: (Boolean) -> Unit,
    onConnectWatch: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onScrollSpeedChange: (Float) -> Unit,
    onResetSpeeds: () -> Unit
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
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.label_sensitivity, String.format(Locale.US, "%.1f", sensitivity)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = sensitivity,
                    onValueChange = onSensitivityChange,
                    valueRange = 0.8f..6.0f
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.label_scroll_speed, String.format(Locale.US, "%.1f", scrollSpeed)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = scrollSpeed,
                    onValueChange = onScrollSpeedChange,
                    valueRange = 1.5f..14.0f
                )

                TextButton(
                    onClick = onResetSpeeds,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.btn_reset_speeds))
                }
            }
        }
    }
}
