package io.github.randomf.safedriveway.live

import android.bluetooth.le.ScanSettings
import com.juul.kable.Advertisement
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.toIdentifier
import io.github.randomf.safedriveway.protocol.Protocol

/** Asks for a larger MTU, so that the setup record goes in fewer packets (doc/ble-protocol.md). */
private const val MTU = 256

/** Low latency: the units are found within a second, at a cost only while the app is on screen. */
@OptIn(ObsoleteKableApi::class)
actual fun unitScanner(): Scanner<Advertisement> = Scanner {
    filters { match { services = listOf(Protocol.SERVICE) } }
    scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
}

actual fun unitPeripheral(address: String): Peripheral = Peripheral(address.toIdentifier()) {
    onServicesDiscovered { requestMtu(MTU) }
    // A failed subscription is logged; the link's next connection subscribes again.
    observationExceptionHandler { cause -> log("observation failed: $cause") }
}
