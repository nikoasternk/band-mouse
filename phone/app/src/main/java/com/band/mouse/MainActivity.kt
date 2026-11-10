package com.band.mouse

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.lifecycle.lifecycleScope
import com.band.mouse.service.MouseAccessibilityService
import com.band.mouse.transport.WearableMouseClient
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var mouseClient: WearableMouseClient
    private var connectedBandName by mutableStateOf<String?>(null)
    private var isCheckingConnection by mutableStateOf(true)
    private var isAccessibilityEnabled by mutableStateOf(false)
    private var sensitivity by mutableFloatStateOf(2.8f)
    private var scrollSpeed by mutableFloatStateOf(6.0f)
    private var languageMode by mutableStateOf("auto")

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("bandmouse_prefs", Context.MODE_PRIVATE)
        languageMode = prefs.getString("lang_mode", "auto") ?: "auto"
        sensitivity = prefs.getFloat("sensitivity", 2.8f)
        scrollSpeed = prefs.getFloat("scroll_mult", 6.0f)
        MouseAccessibilityService.cursorSensitivity = sensitivity
        MouseAccessibilityService.scrollMultiplier = scrollSpeed

        mouseClient = WearableMouseClient(this, lifecycleScope)
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
                            isServiceActive = isAccessibilityEnabled,
                            sensitivity = sensitivity,
                            scrollSpeed = scrollSpeed,
                            onConnectWatch = { startWatchSession() },
                            onOpenAccessibility = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAccessibilityEnabled = MouseAccessibilityService.instance != null
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
    isServiceActive: Boolean,
    sensitivity: Float,
    scrollSpeed: Float,
    onConnectWatch: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onScrollSpeedChange: (Float) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                Text(stringResource(R.string.service_title), style = MaterialTheme.typography.labelMedium)
                Text(
                    text = if (isServiceActive) stringResource(R.string.service_on) else stringResource(R.string.service_off),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FilledTonalButton(
                    onClick = onOpenAccessibility,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.btn_open_accessibility))
                }
            }
        }

        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.device_status), style = MaterialTheme.typography.labelMedium)
                    Text(bandStatus, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            Button(
                onClick = onConnectWatch,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(stringResource(R.string.btn_launch_watch))
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    valueRange = 1.5f..12.0f
                )
            }
        }
    }
}