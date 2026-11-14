package io.github.randomf.safedriveway

import kotlin.test.Test
import kotlin.test.assertEquals

class BluetoothStatusTest {
    @Test
    fun checksUntilKableAnswers() {
        assertEquals(
            BluetoothStatus.Checking,
            bluetoothStatus(
                supported = null,
                permitted = true,
                permissionBlocked = false,
                on = true
            )
        )
    }

    @Test
    fun noBluetoothLowEnergyOutranksEverythingElse() {
        assertEquals(
            BluetoothStatus.NotSupported,
            bluetoothStatus(
                supported = false,
                permitted = false,
                permissionBlocked = true,
                on = false
            )
        )
    }

    @Test
    fun asksForThePermissionBeforeLookingAtTheRadio() {
        assertEquals(
            BluetoothStatus.PermissionNeeded,
            bluetoothStatus(
                supported = true,
                permitted = false,
                permissionBlocked = false,
                on = false
            )
        )
    }

    @Test
    fun sendsToTheSettingsOnceTheSystemStopsAsking() {
        assertEquals(
            BluetoothStatus.PermissionBlocked,
            bluetoothStatus(
                supported = true,
                permitted = false,
                permissionBlocked = true,
                on = false
            )
        )
    }

    @Test
    fun aPermissionAllowedInTheSettingsIsNoLongerBlocked() {
        assertEquals(
            BluetoothStatus.Ready,
            bluetoothStatus(supported = true, permitted = true, permissionBlocked = true, on = true)
        )
    }

    @Test
    fun reportsBluetoothOff() {
        assertEquals(
            BluetoothStatus.Off,
            bluetoothStatus(
                supported = true,
                permitted = true,
                permissionBlocked = false,
                on = false
            )
        )
    }

    @Test
    fun readyWhenEverythingIsInPlace() {
        assertEquals(
            BluetoothStatus.Ready,
            bluetoothStatus(
                supported = true,
                permitted = true,
                permissionBlocked = false,
                on = true
            )
        )
    }
}
