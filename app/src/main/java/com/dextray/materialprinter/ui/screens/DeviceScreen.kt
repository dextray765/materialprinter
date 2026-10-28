package com.dextray.materialprinter.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dextray.materialprinter.bluetooth.BluetoothPrinterManager
import com.dextray.materialprinter.bluetooth.ConnectionState
import com.dextray.materialprinter.protocol.DensityLevel
import com.dextray.materialprinter.protocol.InkSaving
import com.dextray.materialprinter.ui.components.BatteryGauge
import com.dextray.materialprinter.ui.components.SectionTitle
import com.dextray.materialprinter.ui.components.SegmentedChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
@Composable
fun DeviceScreen(
    printerManager: BluetoothPrinterManager,
    connectionState: ConnectionState
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pairedDevices by printerManager.pairedDevices.collectAsState()
    val discoveredDevices by printerManager.discoveredDevices.collectAsState()
    val isScanning by printerManager.isScanning.collectAsState()

    val rfcommPort by printerManager.rfcommPort.collectAsState()
    val printerWidth by printerManager.printerWidth.collectAsState()
    val densityLevel by printerManager.densityLevel.collectAsState()
    val printerClass by printerManager.printerClass.collectAsState()
    val batteryMillivolts by printerManager.batteryMillivolts.collectAsState()
    val inkSaving by printerManager.inkSaving.collectAsState()

    // Check permissions
    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    var permissionsGranted by remember { mutableStateOf(hasPermissions()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
        if (permissionsGranted) {
            printerManager.refreshPairedDevices()
        }
    }

    LaunchedEffect(Unit) {
        if (permissionsGranted) {
            printerManager.refreshPairedDevices()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Permission Warning Card
        if (!permissionsGranted) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Требуются разрешения Bluetooth",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Для подключения к принтеру без доступа в интернет необходимо предоставить разрешение на поиск устройств Bluetooth.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.BLUETOOTH_SCAN
                                    )
                                } else {
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                                permissionLauncher.launch(perms)
                            }
                        ) {
                            Text("Предоставить доступ")
                        }
                    }
                }
            }
        }

        // Active Connection Status Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionState) {
                        is ConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
                        is ConnectionState.Connecting, is ConnectionState.Printing -> MaterialTheme.colorScheme.tertiaryContainer
                        is ConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (connectionState) {
                                is ConnectionState.Connected -> Icons.Default.BluetoothConnected
                                is ConnectionState.Connecting, is ConnectionState.Printing -> Icons.Default.BluetoothSearching
                                is ConnectionState.Error -> Icons.Default.Error
                                else -> Icons.Default.Bluetooth
                            },
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when (connectionState) {
                                    is ConnectionState.Connected -> "Подключено"
                                    is ConnectionState.Connecting -> "Подключение…"
                                    is ConnectionState.Printing -> connectionState.message
                                    is ConnectionState.Error -> "Ошибка"
                                    is ConnectionState.Disconnected -> "Принтер отключён"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (connectionState is ConnectionState.Connected) {
                                Text(
                                    text = connectionState.deviceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = connectionState.deviceAddress,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                                Text(
                                    text = "канал $rfcommPort · $printerWidth точек",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else if (connectionState is ConnectionState.Connecting) {
                                Text(
                                    text = connectionState.deviceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else if (connectionState is ConnectionState.Error) {
                                Text(
                                    text = connectionState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        if (connectionState is ConnectionState.Connected) {
                            OutlinedButton(onClick = { printerManager.disconnect() }) {
                                Text("Отключить")
                            }
                        } else if (connectionState is ConnectionState.Printing) {
                            // Escape hatch: a printer that stopped reading must never lock the app in "Printing"
                            OutlinedButton(onClick = { printerManager.disconnect() }) {
                                Text("Прервать")
                            }
                        }
                    }

                    // Battery gauge
                    if (connectionState is ConnectionState.Connected) {
                        batteryMillivolts?.let { millivolts ->
                            Spacer(modifier = Modifier.height(16.dp))
                            BatteryGauge(millivolts = millivolts)
                        }
                    }

                    // Feed paper & quick actions
                    if (connectionState is ConnectionState.Connected) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val res = printerManager.feedPaper(4)
                                        if (res.isSuccess) {
                                            android.widget.Toast.makeText(context, "Лента протянута", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Ошибка: ${res.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Протяжка")
                            }

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val testText = "Material Printer 🖨️\n───────────────\n✓ Тест пройден успешно!\nШирина: $printerWidth точек\nКанал: $rfcommPort"
                                        val processed = try {
                                            withContext(Dispatchers.Default) {
                                                val rawBmp = com.dextray.materialprinter.image.TextToBitmapRenderer.renderTextToBitmap(
                                                    testText, textSizePx = 28f, isBold = true, width = printerWidth
                                                )
                                                try {
                                                    com.dextray.materialprinter.image.ImageProcessor.processBitmap(
                                                        rawBmp, targetWidth = printerWidth, rotate180 = true
                                                    )
                                                } finally {
                                                    rawBmp.recycle()
                                                }
                                            }
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Throwable) {
                                            android.widget.Toast.makeText(context, "Ошибка подготовки: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                            return@launch
                                        }
                                        val res = printerManager.printImage(processed)
                                        if (res.isSuccess) {
                                            android.widget.Toast.makeText(context, "Тестовая печать выполнена!", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Ошибка: ${res.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Тест печати")
                            }
                        }
                    }
                }
            }
        }

        // Print settings: darkness and RFCOMM channel on one calm surface
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    SectionTitle("Плотность печати")
                    val classInfo = printerClass?.let { "Тип: ${it.title}, значение ${it.densityFor(densityLevel)}" }
                        ?: "Тип определится после подключения"
                    Text(
                        text = classInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )
                    val levels = DensityLevel.values()
                    SegmentedChoice(
                        options = levels.map { it.title },
                        selectedIndex = levels.indexOf(densityLevel),
                        onSelect = { index -> printerManager.setDensityLevel(levels[index]) }
                    )

                    Box(
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    // Cap for the share of black dots per row: evens out stripes in solid black areas
                    SectionTitle("Ограничение чёрного")
                    Text(
                        text = "Если сплошной чёрный печатается серым или полосами, ограничьте долю чёрных точек в строке. Чем ниже процент, тем слабее полосы.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )
                    val inkLevels = InkSaving.values()
                    SegmentedChoice(
                        options = inkLevels.map { it.title },
                        selectedIndex = inkLevels.indexOf(inkSaving),
                        onSelect = { index -> printerManager.setInkSaving(inkLevels[index]) }
                    )

                    Box(
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    // RFCOMM channel (YHK printers use channel 2); applied on the next connection attempt
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            SectionTitle("RFCOMM-канал")
                            Text(
                                text = "Для YHK обычно 2. Применяется при следующем подключении.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { printerManager.setRfcommPort(rfcommPort - 1) }) {
                            Icon(Icons.Default.Remove, contentDescription = "Меньше")
                        }
                        Text(
                            text = "$rfcommPort",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        IconButton(onClick = { printerManager.setRfcommPort(rfcommPort + 1) }) {
                            Icon(Icons.Default.Add, contentDescription = "Больше")
                        }
                    }
                }
            }
        }

        // Section: Paired Devices
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("Сопряжённые устройства")
                IconButton(onClick = { printerManager.refreshPairedDevices() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                }
            }
        }

        if (pairedDevices.isEmpty()) {
            item {
                Text(
                    text = "Нет сопряжённых устройств. Сопрягите принтер (YHK-XXXX или WalkPrint) в настройках Bluetooth телефона.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(pairedDevices) { device ->
                DeviceItem(
                    device = device,
                    isConnected = (connectionState as? ConnectionState.Connected)?.deviceAddress == device.address,
                    onConnectClick = {
                        printerManager.connectToDevice(device, rfcommPort)
                    }
                )
            }
        }

        // Section: Device Scan
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("Поиск новых устройств")

                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    OutlinedButton(
                        onClick = {
                            if (permissionsGranted) {
                                printerManager.startDiscovery()
                            } else {
                                val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                                } else {
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                                permissionLauncher.launch(perms)
                            }
                        }
                    ) {
                        Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Сканировать")
                    }
                }
            }
        }

        if (discoveredDevices.isNotEmpty()) {
            items(discoveredDevices) { device ->
                DeviceItem(
                    device = device,
                    isConnected = (connectionState as? ConnectionState.Connected)?.deviceAddress == device.address,
                    onConnectClick = {
                        printerManager.stopDiscovery()
                        printerManager.connectToDevice(device, rfcommPort)
                    }
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceItem(
    device: BluetoothDevice,
    isConnected: Boolean,
    onConnectClick: () -> Unit
) {
    // getName() throws SecurityException when BLUETOOTH_CONNECT was revoked while the app is open
    val rawName: String? = try {
        device.name
    } catch (e: SecurityException) {
        null
    }
    val name = rawName ?: "Неизвестное устройство"
    val address = device.address

    val isPrinter = name.startsWith("YHK", ignoreCase = true) || name.contains("print", ignoreCase = true)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onConnectClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Bluetooth,
                contentDescription = null,
                tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isPrinter) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (isPrinter) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Text(
                            text = "Принтер",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))

            if (isConnected) {
                Text(
                    text = "Активен",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                FilledTonalButton(onClick = onConnectClick) {
                    Text("Подключить", maxLines = 1)
                }
            }
        }
    }
}
