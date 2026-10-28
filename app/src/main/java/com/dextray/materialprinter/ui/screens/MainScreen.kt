package com.dextray.materialprinter.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dextray.materialprinter.bluetooth.BluetoothPrinterManager
import com.dextray.materialprinter.bluetooth.ConnectionState
import com.dextray.materialprinter.protocol.PrinterReplies
import com.dextray.materialprinter.ui.components.StatusLed
import com.dextray.materialprinter.ui.theme.LedAmber
import com.dextray.materialprinter.ui.theme.LedGreen
import com.dextray.materialprinter.ui.theme.LedOff
import com.dextray.materialprinter.ui.theme.LedRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    printerManager: BluetoothPrinterManager,
    initialSharedText: String? = null,
    initialSharedUri: Uri? = null
) {
    val connectionState by printerManager.connectionState.collectAsState()
    val printerWarning by printerManager.printerWarning.collectAsState()
    val batteryMillivolts by printerManager.batteryMillivolts.collectAsState()

    // Message from the printer itself has priority; otherwise warn about a weak battery
    val bannerText: String? = printerWarning ?: batteryMillivolts?.let { millivolts ->
        if (millivolts <= PrinterReplies.BATTERY_LOW_MV) {
            val volts = "%.1f".format(millivolts / 1000.0)
            "Низкий заряд принтера ($volts В): печать может быть бледной, с полосами или прерываться"
        } else {
            null
        }
    }
    var selectedTab by rememberSaveable {
        mutableStateOf(if (initialSharedUri != null) 1 else 0)
    }
    val screenStateHolder = rememberSaveableStateHolder()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "Material Printer",
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        StatusPill(
                            state = connectionState,
                            millivolts = batteryMillivolts,
                            onClick = { selectedTab = 2 }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )

                // Printing progress bar
                AnimatedVisibility(visible = connectionState is ConnectionState.Printing) {
                    val progress = (connectionState as? ConnectionState.Printing)?.progress ?: 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Problem reported by the printer itself (no paper, overheated) or a weak battery
                AnimatedVisibility(visible = bannerText != null) {
                    val isError = printerWarning != null
                    Surface(
                        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = bannerText ?: "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Description, contentDescription = "Текст") },
                    label = { Text("Текст") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Image, contentDescription = "Картинка") },
                    label = { Text("Картинка") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (connectionState is ConnectionState.Connected || connectionState is ConnectionState.Printing) {
                                    Badge(containerColor = LedGreen)
                                }
                            }
                        ) {
                            Icon(Icons.Default.Bluetooth, contentDescription = "Принтер")
                        }
                    },
                    label = { Text("Принтер") }
                )
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            screenStateHolder.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    0 -> TextPrintScreen(
                        printerManager = printerManager,
                        connectionState = connectionState,
                        initialSharedText = initialSharedText
                    )
                    1 -> ImagePrintScreen(
                        printerManager = printerManager,
                        connectionState = connectionState,
                        initialSharedUri = initialSharedUri
                    )
                    2 -> DeviceScreen(
                        printerManager = printerManager,
                        connectionState = connectionState
                    )
                }
            }
        }
    }
}

/** Compact connection status for the app bar: a LED like on the real printer, plus the battery voltage. */
@Composable
private fun StatusPill(
    state: ConnectionState,
    millivolts: Int?,
    onClick: () -> Unit
) {
    val weakBattery = millivolts != null && millivolts <= PrinterReplies.BATTERY_LOW_MV
    val ledColor = when (state) {
        is ConnectionState.Connected -> if (weakBattery) LedAmber else LedGreen
        is ConnectionState.Connecting, is ConnectionState.Printing -> LedAmber
        is ConnectionState.Error -> LedRed
        else -> LedOff
    }
    val label = when (state) {
        is ConnectionState.Connected -> if (millivolts != null) "%.1f В".format(millivolts / 1000.0) else "На связи"
        is ConnectionState.Connecting -> "Подключение…"
        is ConnectionState.Printing -> "Печать…"
        is ConnectionState.Error -> "Нет связи"
        else -> "Не подключён"
    }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            StatusLed(
                color = ledColor,
                pulsing = state is ConnectionState.Connecting || state is ConnectionState.Printing
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
