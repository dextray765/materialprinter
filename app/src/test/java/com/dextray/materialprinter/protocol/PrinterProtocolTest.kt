package com.dextray.materialprinter.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests (no Android classes): run with `./gradlew testDebugUnitTest`.
 * They pin down everything that was reverse-engineered from the printer, so a later edit cannot break it silently.
 */
class PrinterProtocolTest {

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    // ---------------------------------------------------------------- raster packet

    @Test
    fun rasterPacketHasCorrectHeaderAndSize() {
        val raster = ByteArray(48 * 300)
        val packet = PrinterProtocol.createRasterImagePacket(48, 300, raster)

        assertEquals(8 + raster.size, packet.size)
        // GS v 0 m xL xH yL yH ; 300 = 0x012C -> yL = 44, yH = 1
        assertArrayEquals(
            byteArrayOf(0x1D, 0x76, 0x30, 0x00, 48, 0, 44, 1),
            packet.copyOfRange(0, 8)
        )
    }

    @Test
    fun rasterPacketKeepsDataUntouched() {
        val raster = ByteArray(96) { (it * 7).toByte() }
        val packet = PrinterProtocol.createRasterImagePacket(48, 2, raster)
        assertArrayEquals(raster, packet.copyOfRange(8, packet.size))
    }

    @Test
    fun densityCommandCarriesTheValue() {
        assertArrayEquals(
            byteArrayOf(0x1D, 0x49, 0xF0.toByte(), 15),
            PrinterProtocol.createDensityCommand(15)
        )
    }

    @Test
    fun feedCommandIsLimited() {
        assertEquals(1, PrinterProtocol.createFeedCommand(0).size)
        assertEquals(20, PrinterProtocol.createFeedCommand(1000).size)
    }

    // ---------------------------------------------------------------- firmware classes

    @Test
    fun densityPresetsMatchTheOriginalApp() {
        assertEquals(25, PrinterClass.PUBLIC.densityFor(DensityLevel.MEDIUM))
        assertEquals(12, PrinterClass.PRIVATE_NEW.densityFor(DensityLevel.LIGHT))
        assertEquals(15, PrinterClass.PRIVATE_NEW.densityFor(DensityLevel.MEDIUM))
        assertEquals(18, PrinterClass.PRIVATE_NEW.densityFor(DensityLevel.DARK))
        assertEquals(10, PrinterClass.PRIVATE_OLD.densityFor(DensityLevel.MEDIUM))
        assertNotNull(PrinterClass.PRIVATE_OLD.speedCommand)
        assertNull(PrinterClass.PRIVATE_NEW.speedCommand)
    }

    @Test
    fun detectsPrivateNewFirmware() {
        val status = ascii("SV=V1.19,VOLT=7800mv,DPI=384")
        val product = ascii("id:101.TNN")
        assertEquals(PrinterClass.PRIVATE_NEW, PrinterReplies.detectClass(status, product))
    }

    @Test
    fun detectsPrivateOldFirmware() {
        val status = ascii("SV=V1.05,VOLT=7800mv,DPI=384")
        val product = ascii("id:101.TNN")
        assertEquals(PrinterClass.PRIVATE_OLD, PrinterReplies.detectClass(status, product))
    }

    @Test
    fun detectsPublicFirmwareByHighId() {
        val status = ascii("SV=V1.19,VOLT=7800mv,DPI=384")
        val product = ascii("id:350.XYZ")
        assertEquals(PrinterClass.PUBLIC, PrinterReplies.detectClass(status, product))
    }

    @Test
    fun fallsBackToTheOriginalDefaultsWhenNothingIsReported() {
        assertEquals(PrinterClass.PRIVATE_NEW, PrinterReplies.detectClass(ByteArray(0), ByteArray(0)))
        assertEquals(PrinterClass.PRIVATE_NEW, PrinterReplies.detectClass(ascii("garbage"), ascii("id:abc.")))
    }

    // ---------------------------------------------------------------- status reply

    @Test
    fun parsesBatteryVoltage() {
        assertEquals(7800, PrinterReplies.parseBatteryMillivolts(ascii("SV=V1.19,VOLT=7800mv,DPI=384")))
        assertNull(PrinterReplies.parseBatteryMillivolts(ascii("no voltage here")))
        assertNull(PrinterReplies.parseBatteryMillivolts(ByteArray(0)))
    }

    @Test
    fun batteryLabelsFollowTheOriginalThresholds() {
        assertEquals("хороший", PrinterReplies.batteryLabel(7701))
        assertEquals("средний", PrinterReplies.batteryLabel(7700))
        assertEquals("средний", PrinterReplies.batteryLabel(7001))
        assertEquals("низкий", PrinterReplies.batteryLabel(7000))
    }

    @Test
    fun parsesOnlyKnownPrintWidths() {
        assertEquals(384, PrinterReplies.parseWidth(ascii("SV=V1.19,VOLT=7800mv,DPI=384")))
        assertEquals(576, PrinterReplies.parseWidth(ascii("SV=V1.19,VOLT=7800mv,DPI=576")))
        assertNull(PrinterReplies.parseWidth(ascii("DPI=720")))
        assertNull(PrinterReplies.parseWidth(ascii("nothing")))
    }

    // ---------------------------------------------------------------- error reports

    @Test
    fun findsTheErrorCode() {
        val reply = ascii("xx err:") + byteArrayOf(0x02, 0x2E)
        assertEquals(2, PrinterReplies.findErrorCode(reply))
    }

    @Test
    fun lastErrorMarkerWins() {
        val reply = ascii("err:") + byteArrayOf(0x02, 0x2E) + ascii("err:") + byteArrayOf(0x00, 0x2E)
        assertEquals(0, PrinterReplies.findErrorCode(reply))
    }

    @Test
    fun noMarkerMeansNoCode() {
        assertNull(PrinterReplies.findErrorCode(ascii("SV=V1.19,VOLT=7800mv")))
        assertNull(PrinterReplies.findErrorCode(ascii("err:"))) // marker without the status byte
        assertNull(PrinterReplies.findErrorCode(ByteArray(0)))
    }

    @Test
    fun describesErrorBits() {
        assertNull(PrinterReplies.describeError(0))
        val noPaper = PrinterReplies.describeError(PrinterReplies.ERR_NO_PAPER)
        assertTrue(noPaper!!.contains("бумага"))
        val both = PrinterReplies.describeError(PrinterReplies.ERR_NO_PAPER or PrinterReplies.ERR_OVERHEAT)
        assertTrue(both!!.contains("бумага") && both.contains("перегрев"))
        assertTrue(PrinterReplies.describeError(0x80)!!.contains("0x80"))
    }

    @Test
    fun onlyPaperAndOverheatBlockPrinting() {
        assertTrue(PrinterReplies.ERR_NO_PAPER and PrinterReplies.BLOCKING_MASK != 0)
        assertTrue(PrinterReplies.ERR_OVERHEAT and PrinterReplies.BLOCKING_MASK != 0)
        assertFalse(PrinterReplies.ERR_LOW_BATTERY and PrinterReplies.BLOCKING_MASK != 0)
    }

    // ---------------------------------------------------------------- black limit

    private fun blackDots(raster: ByteArray, row: Int, widthBytes: Int): Int {
        var count = 0
        for (b in 0 until widthBytes) {
            count += Integer.bitCount(raster[row * widthBytes + b].toInt() and 0xFF)
        }
        return count
    }

    @Test
    fun limitCapsEveryHeavyRow() {
        val widthBytes = 48
        val height = 40
        val solid = ByteArray(widthBytes * height) { 0xFF.toByte() }

        val limited = RasterTools.limitBlackPerRow(solid, widthBytes, height, 0.75f)

        for (row in 0 until height) {
            assertEquals(288, blackDots(limited, row, widthBytes))
        }
    }

    @Test
    fun limitLeavesLightRowsAlone() {
        val widthBytes = 48
        val raster = ByteArray(widthBytes * 2)
        // row 0: 10 black dots, row 1: empty
        raster[0] = 0xFF.toByte()
        raster[1] = 0xC0.toByte()

        val limited = RasterTools.limitBlackPerRow(raster, widthBytes, 2, 0.5f)

        assertArrayEquals(raster, limited)
    }

    @Test
    fun limitNeverCreatesBlackDots() {
        val widthBytes = 48
        val height = 20
        val raster = ByteArray(widthBytes * height) { index -> if (index % 3 == 0) 0x7E.toByte() else 0xFF.toByte() }

        val limited = RasterTools.limitBlackPerRow(raster, widthBytes, height, 0.6f)

        for (i in raster.indices) {
            val added = limited[i].toInt() and raster[i].toInt().inv() and 0xFF
            assertEquals("new black dot at byte $i", 0, added)
        }
    }

    @Test
    fun limitOfOneHundredPercentChangesNothing() {
        val raster = ByteArray(96) { 0xFF.toByte() }
        assertArrayEquals(raster, RasterTools.limitBlackPerRow(raster, 48, 2, 1.0f))
    }
}
