package com.dextray.materialprinter.protocol

import java.util.UUID

/**
 * Protocol commands and constants reverse-engineered from YHK / WalkPrint cat thermal printers.
 * Directly based on cat-printer.py.
 */
object PrinterProtocol {
    /**
     * Physical print head width in dots (pixels). Standard 48mm / 58mm roll at 203 DPI.
     */
    const val PRINTER_WIDTH = 384

    /**
     * Width in bytes (8 dots per byte) = 48 bytes per raster row.
     */
    const val BYTES_PER_LINE = PRINTER_WIDTH / 8 // 48

    /**
     * Default RFCOMM channel / port used by YHK firmware.
     */
    const val DEFAULT_RFCOMM_PORT = 2

    /**
     * Standard Bluetooth Serial Port Profile (SPP) UUID.
     */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // ESC @ - Initialize printer
    val CMD_INIT = byteArrayOf(0x1B.toByte(), 0x40.toByte())

    // Status queries
    val CMD_GET_STATUS = byteArrayOf(0x1E.toByte(), 0x47.toByte(), 0x03.toByte())
    val CMD_GET_SERIAL = byteArrayOf(0x1D.toByte(), 0x67.toByte(), 0x39.toByte())
    val CMD_GET_PRODUCT_INFO = byteArrayOf(0x1D.toByte(), 0x67.toByte(), 0x69.toByte())

    // Paper / error status query (same command the original WalkPrint sends before every print)
    val CMD_GET_PAPER = byteArrayOf(0x1D.toByte(), 0x67.toByte(), 0x53.toByte())

    /**
     * GS v 0 stores the raster height in two bytes (yL, yH), so taller images cannot be sent in one command.
     */
    const val MAX_RASTER_HEIGHT = 65535

    /**
     * The original app writes to the socket in chunks of 500 bytes.
     */
    const val WRITE_CHUNK_SIZE = 500

    // End print sequence: 4 line feeds (LF) to advance paper past tear bar
    val CMD_END_PRINT = byteArrayOf(0x0A.toByte(), 0x0A.toByte(), 0x0A.toByte(), 0x0A.toByte())

    /**
     * Density command `1D 49 F0 <value>`. The valid range depends on the firmware class,
     * see [PrinterClass]; the old fixed value 0x19 (25) is only "medium" for public firmware.
     */
    fun createDensityCommand(value: Int): ByteArray =
        byteArrayOf(0x1D.toByte(), 0x49.toByte(), 0xF0.toByte(), value.coerceIn(0, 255).toByte())

    /**
     * Generate feed lines command.
     */
    fun createFeedCommand(lines: Int = 4): ByteArray {
        val count = lines.coerceIn(1, 20)
        return ByteArray(count) { 0x0A.toByte() }
    }

    /**
     * Creates ESC/POS raster bit image command:
     * GS v 0 0 xL xH yL yH d1...dk
     *
     * @param widthBytes Number of bytes per line (usually 48 for 384 dots)
     * @param height Number of vertical dots (rows)
     * @param bitmap1Bit 1-bit packed bitmap data (where 1 = dot printed / black, 0 = white)
     */
    fun createRasterImagePacket(widthBytes: Int, height: Int, bitmap1Bit: ByteArray): ByteArray {
        val xL = (widthBytes % 256).toByte()
        val xH = (widthBytes / 256).toByte()
        val yL = (height % 256).toByte()
        val yH = (height / 256).toByte()

        val header = byteArrayOf(
            0x1D.toByte(),
            0x76.toByte(),
            0x30.toByte(),
            0x00.toByte(),
            xL,
            xH,
            yL,
            yH
        )

        val output = ByteArray(header.size + bitmap1Bit.size)
        System.arraycopy(header, 0, output, 0, header.size)
        System.arraycopy(bitmap1Bit, 0, output, header.size, bitmap1Bit.size)
        return output
    }
}

/**
 * Parsing of the printer's replies (reverse-engineered from the original WalkPrint app).
 *
 * - Errors arrive as ASCII "err:" followed by one status byte (usually followed by '.').
 *   The byte is a bit mask: 0x02 = out of paper, 0x10 = print head overheated (0x12 = both),
 *   0x04 = low battery, 0x00 = everything is fine again.
 * - The status reply to `1E 47 03` contains text such as "VOLT=...,DPI..." with the print width.
 */
object PrinterReplies {
    const val ERR_NO_PAPER = 0x02
    const val ERR_LOW_BATTERY = 0x04
    const val ERR_OVERHEAT = 0x10

    /** Errors that make printing pointless. */
    const val BLOCKING_MASK = ERR_NO_PAPER or ERR_OVERHEAT

    private val DPI_REGEX = Regex("DPI\\D{0,3}(\\d{3,4})")

    /** Returns the status byte of the LAST "err:" marker in [data], or null if there is none. */
    fun findErrorCode(data: ByteArray): Int? {
        var result: Int? = null
        var i = 0
        while (i + 4 < data.size) {
            if (data[i] == 0x65.toByte() &&      // 'e'
                data[i + 1] == 0x72.toByte() &&  // 'r'
                data[i + 2] == 0x72.toByte() &&  // 'r'
                data[i + 3] == 0x3A.toByte()     // ':'
            ) {
                result = data[i + 4].toInt() and 0xFF
                i += 5
            } else {
                i++
            }
        }
        return result
    }

    /** Human readable text for an error code, or null when the code means "no error". */
    fun describeError(code: Int): String? {
        if (code == 0) return null
        val parts = ArrayList<String>()
        if (code and ERR_NO_PAPER != 0) parts.add("бумага закончилась")
        if (code and ERR_OVERHEAT != 0) parts.add("перегрев печатающей головки")
        if (code and ERR_LOW_BATTERY != 0) parts.add("низкий заряд аккумулятора")
        val known = ERR_NO_PAPER or ERR_OVERHEAT or ERR_LOW_BATTERY
        if (code and known.inv() != 0) {
            parts.add("код ошибки 0x" + Integer.toHexString(code and known.inv()).uppercase())
        }
        return "Принтер: " + parts.joinToString(", ")
    }

    /** Battery thresholds of the original app (millivolts of the 2-cell pack). */
    const val BATTERY_FULL_MV = 7700
    const val BATTERY_LOW_MV = 7000

    private val VOLT_REGEX = Regex("VOLT=(\\d+)")
    private val VERSION_REGEX = Regex("SV=.([0-9.]+)")
    private val ID_REGEX = Regex("id:(\\d+)")

    /** Battery voltage in millivolts from the status reply ("...VOLT=7800mv,DPI=..."), or null. */
    fun parseBatteryMillivolts(status: ByteArray): Int? =
        VOLT_REGEX.find(String(status, Charsets.ISO_8859_1))?.groupValues?.get(1)?.toIntOrNull()

    fun batteryLabel(millivolts: Int): String = when {
        millivolts > BATTERY_FULL_MV -> "хороший"
        millivolts > BATTERY_LOW_MV -> "средний"
        else -> "низкий"
    }

    /**
     * Same decision as the original app (BTCmd.checkFactory / PrintTask):
     * "id:NNN" in the product info below 200 (or missing) = private firmware, otherwise public;
     * private firmware older than 1.19 (reported as "SV=..." in the status) is "old".
     * Undetected values fall back to the original's defaults (private, version 1.19).
     */
    fun detectClass(status: ByteArray, productInfo: ByteArray): PrinterClass {
        val productText = String(productInfo, Charsets.ISO_8859_1)
        val id = ID_REGEX.find(productText)?.groupValues?.get(1)?.toIntOrNull()
        val isPrivate = !productText.contains('.') || id == null || id < 200
        if (!isPrivate) return PrinterClass.PUBLIC

        val statusText = String(status, Charsets.ISO_8859_1)
        val version = VERSION_REGEX.find(statusText)?.groupValues?.get(1)
            ?.replace(".", "")?.toIntOrNull() ?: 119
        return if (version >= 119) PrinterClass.PRIVATE_NEW else PrinterClass.PRIVATE_OLD
    }

    /**
     * Extracts the print width (384 or 576 dots) from the status reply.
     * Only an explicit "DPI<number>" is trusted; otherwise null (caller keeps the default 384).
     */
    fun parseWidth(status: ByteArray): Int? {
        val text = String(status, Charsets.ISO_8859_1)
        val value = DPI_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (value == 384 || value == 576) value else null
    }
}

/** User-selectable print darkness (the original app calls them light / medium / dark). */
enum class DensityLevel(val title: String) {
    LIGHT("Светлая"),
    MEDIUM("Средняя"),
    DARK("Тёмная")
}

/**
 * Firmware classes of YHK printers and their density presets, taken from the original WalkPrint app
 * (AppConst.setPrintValue). Sending a value from the wrong class over-drives (or starves) the thermal head.
 */
enum class PrinterClass(
    val title: String,
    private val light: Int,
    private val medium: Int,
    private val dark: Int,
    /** Extra speed command the original sends first on old firmware (`1D 49 F1 1E`); null = none. */
    val speedCommand: ByteArray?
) {
    PUBLIC("public", 20, 25, 30, null),
    PRIVATE_NEW("private, прошивка 1.19+", 12, 15, 18, null),
    PRIVATE_OLD(
        "private, прошивка до 1.19", 5, 10, 15,
        byteArrayOf(0x1D.toByte(), 0x49.toByte(), 0xF1.toByte(), 0x1E.toByte())
    );

    fun densityFor(level: DensityLevel): Int = when (level) {
        DensityLevel.LIGHT -> light
        DensityLevel.MEDIUM -> medium
        DensityLevel.DARK -> dark
    }
}

/**
 * Limit for the share of black dots in one raster row.
 * A thermal head that cannot power a whole row at once prints solid black fields grey and uneven
 * (rows with a few white dots come out much darker than fully black ones). Capping the share of
 * black dots evens the rows out; 100 % = no limit.
 */
enum class InkSaving(val title: String, val maxFraction: Float) {
    OFF("Выкл", 1.0f),
    LIGHT("75%", 0.75f),
    MEDIUM("60%", 0.6f),
    STRONG("50%", 0.5f),
    MAX("40%", 0.4f)
}

object RasterTools {
    /**
     * Returns a copy of [raster] (1 bit per dot, MSB first, 1 = black) in which no row has more than
     * [maxFraction] black dots. The surplus dots are switched off evenly along the row, with a phase that
     * changes from row to row, so a solid field turns into a fine uniform texture instead of stripes.
     */
    fun limitBlackPerRow(raster: ByteArray, widthBytes: Int, height: Int, maxFraction: Float): ByteArray {
        val out = raster.copyOf()
        if (maxFraction >= 1f) return out
        val widthDots = widthBytes * 8
        val maxOn = (widthDots * maxFraction).toInt()

        for (y in 0 until height) {
            val base = y * widthBytes
            var on = 0
            for (b in 0 until widthBytes) {
                on += Integer.bitCount(out[base + b].toInt() and 0xFF)
            }
            if (on <= maxOn) continue

            val excess = on - maxOn
            val step = on.toDouble() / excess
            var nextClear = step * (((y * 5) % 8) + 0.5) / 8.0
            var seen = 0
            var cleared = 0
            for (x in 0 until widthDots) {
                val index = base + (x shr 3)
                val mask = 0x80 shr (x and 7)
                if ((out[index].toInt() and mask) != 0) {
                    seen++
                    if (cleared < excess && seen.toDouble() >= nextClear) {
                        out[index] = (out[index].toInt() and mask.inv()).toByte()
                        cleared++
                        nextClear += step
                    }
                }
            }
        }
        return out
    }
}
