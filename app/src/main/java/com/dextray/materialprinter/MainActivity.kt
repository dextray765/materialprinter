package com.dextray.materialprinter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dextray.materialprinter.bluetooth.BluetoothPrinterManager
import com.dextray.materialprinter.ui.screens.MainScreen
import com.dextray.materialprinter.ui.theme.MaterialPrinterTheme

class MainActivity : ComponentActivity() {

    private companion object {
        // Process-wide count of live MainActivity instances (a configuration change destroys and re-creates one)
        var openInstances = 0
    }

    private lateinit var printerManager: BluetoothPrinterManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        printerManager = (application as PrinterApp).printerManager
        printerManager.cancelPendingRelease()
        openInstances++

        // Check if app was opened via "Share" (SEND intent)
        var sharedText: String? = null
        var sharedUri: Uri? = null

        if (intent?.action == Intent.ACTION_SEND) {
            // Intent extras come from other apps: a malformed one must never crash us
            try {
                when {
                    intent.type?.startsWith("text/") == true -> {
                        // Many apps pass a CharSequence (not a String) - getStringExtra() would return null for it
                        sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    }
                    intent.type?.startsWith("image/") == true -> {
                        @Suppress("DEPRECATION")
                        sharedUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Ignoring a malformed share intent: ${e.message}")
            }
        }

        setContent {
            MaterialPrinterTheme {
                MainScreen(
                    printerManager = printerManager,
                    initialSharedText = sharedText,
                    initialSharedUri = sharedUri
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Rotation / theme change re-creates the Activity: the connection must survive that.
        // Only a real close (back button, swipe away) drops the connection.
        openInstances--
        // "Share" opens a second instance on top of the running app: drop the connection only when the last one closes
        if (isFinishing && openInstances == 0) {
            printerManager.release()
        }
    }
}
