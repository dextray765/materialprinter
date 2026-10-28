package com.dextray.materialprinter.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dextray.materialprinter.bluetooth.BluetoothPrinterManager
import com.dextray.materialprinter.bluetooth.ConnectionState
import com.dextray.materialprinter.image.DitherMode
import com.dextray.materialprinter.ui.components.LabeledSlider
import com.dextray.materialprinter.ui.components.SegmentedChoice
import com.dextray.materialprinter.ui.components.SwitchRow
import com.dextray.materialprinter.image.ImageLoader
import com.dextray.materialprinter.image.ImageProcessor
import com.dextray.materialprinter.image.ProcessedImageResult
import com.dextray.materialprinter.ui.components.ThermalPaperPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImagePrintScreen(
    printerManager: BluetoothPrinterManager,
    connectionState: ConnectionState,
    initialSharedUri: Uri? = null
) {
    val context = LocalContext.current
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var ditherMode by rememberSaveable { mutableStateOf(DitherMode.FLOYD_STEINBERG) }
    var threshold by rememberSaveable { mutableStateOf(128) }
    var contrast by rememberSaveable { mutableStateOf(1.0f) }
    var brightness by rememberSaveable { mutableStateOf(0f) }
    var rotate180 by rememberSaveable { mutableStateOf(true) }
    var invertColors by rememberSaveable { mutableStateOf(false) }

    var processedResult by remember { mutableStateOf<ProcessedImageResult?>(null) }

    // Print width reported by the printer (384 or 576 dots)
    val printerWidth by printerManager.printerWidth.collectAsState()

    // The picture the user picked (gallery or "Share"); decoded off the UI thread below
    var pendingUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    // Gallery Picker
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingUri = uri
        }
    }

    // Handle shared URI from intent if provided
    var sharedUriConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialSharedUri) {
        if (initialSharedUri != null && !sharedUriConsumed) {
            pendingUri = initialSharedUri
            sharedUriConsumed = true
        }
    }

    // Decode the picture on a background thread: applies EXIF rotation and avoids loading huge bitmaps
    LaunchedEffect(pendingUri) {
        val uri = pendingUri
        originalBitmap = null
        processedResult = null
        if (uri == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { ImageLoader.load(context, uri) }
        if (loaded != null) {
            originalBitmap = loaded
        } else {
            android.widget.Toast.makeText(context, "Не удалось открыть изображение", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Process bitmap when settings change (off the main thread; short delay = debounce for slider drags)
    LaunchedEffect(originalBitmap, ditherMode, threshold, contrast, brightness, rotate180, invertColors, printerWidth) {
        processedResult = null
        val src = originalBitmap ?: return@LaunchedEffect
        delay(40)
        try {
            processedResult = withContext(Dispatchers.Default) {
                ImageProcessor.processBitmap(
                    source = src,
                    targetWidth = printerWidth,
                    rotate180 = rotate180,
                    ditherMode = ditherMode,
                    threshold = threshold,
                    contrast = contrast,
                    brightness = brightness.toInt(),
                    invertColors = invertColors
                )
            }
        } catch (e: CancellationException) {
            throw e // the effect was restarted by a new setting - not an error
        } catch (e: Throwable) {
            // includes OutOfMemoryError: a huge picture must not crash the app
            processedResult = null
            android.widget.Toast.makeText(context, "Не удалось обработать изображение: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val isConnected = connectionState is ConnectionState.Connected
    val isPrinting = connectionState is ConnectionState.Printing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Image selection
        Button(
            onClick = { galleryLauncher.launch("image/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Выбрать фото")
        }

        // Adjustments Card
        if (originalBitmap != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Raster mode
                    Text("Режим растра", style = MaterialTheme.typography.bodyMedium)
                    SegmentedChoice(
                        options = listOf("Полутона", "Чёткий"),
                        selectedIndex = if (ditherMode == DitherMode.THRESHOLD) 1 else 0,
                        onSelect = { index ->
                            ditherMode = if (index == 1) DitherMode.THRESHOLD else DitherMode.FLOYD_STEINBERG
                        }
                    )

                    LabeledSlider(
                        label = "Порог",
                        valueText = "$threshold",
                        value = threshold.toFloat(),
                        onValueChange = { threshold = it.toInt() },
                        valueRange = 0f..255f
                    )

                    LabeledSlider(
                        label = "Контраст",
                        valueText = "%.1f×".format(contrast),
                        value = contrast,
                        onValueChange = { contrast = it },
                        valueRange = 0.5f..2.5f
                    )

                    SwitchRow(
                        label = "Поворот на 180°",
                        checked = rotate180,
                        onCheckedChange = { rotate180 = it }
                    )
                    SwitchRow(
                        label = "Инверсия",
                        checked = invertColors,
                        onCheckedChange = { invertColors = it },
                        hint = "Много чёрного: возможны полосы. Поможет «Ограничение чёрного» на вкладке «Принтер»."
                    )
                }
            }
        }

        // Live Thermal Receipt Preview
        ThermalPaperPreview(
            previewBitmap = processedResult?.previewBitmap,
            widthDots = printerWidth,
            isPrinting = isPrinting,
            emptyMessage = "Выберите фото из галереи для предпросмотра"
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Print Action Button
        val coroutineScope = rememberCoroutineScope()
        Button(
            onClick = {
                val toPrint = processedResult ?: return@Button
                coroutineScope.launch {
                    val result = printerManager.printImage(toPrint)
                    if (result.isSuccess) {
                        android.widget.Toast.makeText(context, "Печать завершена!", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(context, "Ошибка: ${result.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
            enabled = isConnected && !isPrinting && processedResult != null,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.Print, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (!isConnected) "Принтер не подключён" else if (isPrinting) "Идёт печать…" else "Напечатать фото",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
