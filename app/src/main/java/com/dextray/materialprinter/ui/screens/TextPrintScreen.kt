package com.dextray.materialprinter.ui.screens

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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.dextray.materialprinter.image.ImageProcessor
import com.dextray.materialprinter.image.ProcessedImageResult
import com.dextray.materialprinter.image.TextAlignOption
import com.dextray.materialprinter.image.TextToBitmapRenderer
import com.dextray.materialprinter.ui.components.ThermalPaperPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A longer text would be 60 000+ rows tall (the raster command cannot hold that) and would not survive saved state
private const val MAX_TEXT_LENGTH = 20000

@Composable
fun TextPrintScreen(
    printerManager: BluetoothPrinterManager,
    connectionState: ConnectionState,
    initialSharedText: String? = null
) {
    val context = LocalContext.current
    var text by rememberSaveable {
        mutableStateOf(
            (initialSharedText ?: "Material Printer 🖨️\n───────────────\nПривет! Это чек с твоего мини-принтера.\nПечатает текст, списки, заметки и картинки!").take(MAX_TEXT_LENGTH)
        )
    }

    var textSize by rememberSaveable { mutableStateOf(26f) }
    var textAlign by rememberSaveable { mutableStateOf(TextAlignOption.LEFT) }
    var isBold by rememberSaveable { mutableStateOf(false) }
    var isMonospace by rememberSaveable { mutableStateOf(false) }
    var invertColors by rememberSaveable { mutableStateOf(false) }

    var processedResult by remember { mutableStateOf<ProcessedImageResult?>(null) }

    // Print width reported by the printer (384 or 576 dots)
    val printerWidth by printerManager.printerWidth.collectAsState()

    // Re-render preview whenever parameters change (off the main thread; delay = debounce while typing)
    LaunchedEffect(text, textSize, textAlign, isBold, isMonospace, invertColors, printerWidth) {
        processedResult = null
        if (text.isBlank()) {
            return@LaunchedEffect
        }
        delay(80)
        try {
            processedResult = withContext(Dispatchers.Default) {
                val rawBitmap = TextToBitmapRenderer.renderTextToBitmap(
                    text = text,
                    textSizePx = textSize,
                    align = textAlign,
                    isBold = isBold,
                    isMonospace = isMonospace,
                    invertColors = invertColors,
                    width = printerWidth
                )

                try {
                    // Process for printer (rotate180 = true, threshold = 128)
                    ImageProcessor.processBitmap(
                        source = rawBitmap,
                        targetWidth = printerWidth,
                        rotate180 = true,
                        ditherMode = DitherMode.THRESHOLD,
                        threshold = 128
                    )
                } finally {
                    rawBitmap.recycle()
                }
            }
        } catch (e: CancellationException) {
            throw e // the effect was restarted by typing - not an error
        } catch (e: Throwable) {
            // includes OutOfMemoryError: a very long text must not crash the app
            processedResult = null
            android.widget.Toast.makeText(context, "Не удалось подготовить текст (слишком длинный?): ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val isConnected = connectionState is ConnectionState.Connected
    val isPrinting = connectionState is ConnectionState.Printing

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Text Input Field
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                if (newText.length > MAX_TEXT_LENGTH) {
                    text = newText.take(MAX_TEXT_LENGTH)
                    android.widget.Toast.makeText(context, "Текст обрезан до $MAX_TEXT_LENGTH символов", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    text = newText
                }
            },
            label = { Text("Текст для печати") },
            placeholder = { Text("Введите текст, заметку, список...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить")
                    }
                }
            }
        )

        // Formatting Controls Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LabeledSlider(
                    label = "Размер",
                    valueText = "${textSize.toInt()} px",
                    value = textSize,
                    onValueChange = { textSize = it },
                    valueRange = 16f..56f,
                    steps = 7
                )

                // Alignment
                Text("Выравнивание", style = MaterialTheme.typography.bodyMedium)
                SegmentedChoice(
                    options = listOf("Слева", "По центру", "Справа"),
                    selectedIndex = textAlign.ordinal,
                    onSelect = { index ->
                        textAlign = when (index) {
                            1 -> TextAlignOption.CENTER
                            2 -> TextAlignOption.RIGHT
                            else -> TextAlignOption.LEFT
                        }
                    }
                )

                // Style
                SwitchRow(label = "Жирный шрифт", checked = isBold, onCheckedChange = { isBold = it })
                SwitchRow(label = "Моноширинный шрифт", checked = isMonospace, onCheckedChange = { isMonospace = it })
                SwitchRow(
                    label = "Инверсия (белый текст на чёрном)",
                    checked = invertColors,
                    onCheckedChange = { invertColors = it },
                    hint = "Много чёрного: возможны полосы. Поможет «Ограничение чёрного» на вкладке «Принтер»."
                )
            }
        }

        // Live Thermal Receipt Preview
        ThermalPaperPreview(
            previewBitmap = processedResult?.previewBitmap,
            widthDots = printerWidth,
            isPrinting = isPrinting,
            emptyMessage = "Введите текст для отображения чека"
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Print Action Button
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
                text = if (!isConnected) "Принтер не подключён" else if (isPrinting) "Идёт печать…" else "Напечатать чек",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
