package io.github.randomf.safedriveway.live

import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner

/** Scans for the units' service, as fast as the platform allows while the app is on screen. */
expect fun unitScanner(): Scanner<Advertisement>

/** The unit at [address], the platform's identifier from its advertisement. */
expect fun unitPeripheral(address: String): Peripheral

/** What the app prints about the units, for whoever reads the platform's log. */
internal fun log(message: String) = println("SafeDriveway: $message")
