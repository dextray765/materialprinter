package com.dextray.materialprinter

import android.app.Application
import com.dextray.materialprinter.bluetooth.BluetoothPrinterManager

/**
 * Holds the one and only [BluetoothPrinterManager], so the printer connection survives
 * Activity re-creation (rotation, dark-mode switch, font-size change, ...).
 */
class PrinterApp : Application() {
    val printerManager: BluetoothPrinterManager by lazy { BluetoothPrinterManager(this) }
}
