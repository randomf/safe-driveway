package io.github.randomf.safedriveway

/** What the screen says about Bluetooth. */
enum class BluetoothStatus {
    /** Kable hasn't answered yet whether the phone supports Bluetooth Low Energy. */
    Checking,
    NotSupported,
    PermissionNeeded,

    /** The system no longer asks for the permission, so the driver has to allow it in settings. */
    PermissionBlocked,
    Off,
    Ready
}

/**
 * Combines what Kable and the platform report, the most basic problem first. [supported] is null
 * until Kable answers. The platform reports whether Bluetooth is [on] only with the permission.
 */
fun bluetoothStatus(
    supported: Boolean?,
    permitted: Boolean,
    permissionBlocked: Boolean,
    on: Boolean
): BluetoothStatus = when {
    supported == null -> BluetoothStatus.Checking
    !supported -> BluetoothStatus.NotSupported
    !permitted && permissionBlocked -> BluetoothStatus.PermissionBlocked
    !permitted -> BluetoothStatus.PermissionNeeded
    !on -> BluetoothStatus.Off
    else -> BluetoothStatus.Ready
}
