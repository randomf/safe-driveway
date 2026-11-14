package io.github.randomf.safedriveway.demo

import io.github.randomf.safedriveway.passage.Direction
import io.github.randomf.safedriveway.passage.End

/** What the demo panel sets: the world the prototype pretends to be in. */
data class DemoSettings(
    val bluetooth: DemoBluetooth = DemoBluetooth.Phone,
    val inRange: DemoInRange = DemoInRange.Garage,
    val problems: Set<DemoProblem> = emptySet(),
    /** Whether this phone already knows the passages' PINs. */
    val pinKnown: Boolean = false,
    val direction: Direction = Direction.Forwards,
    val entry: End = End.Usual,
    val course: DemoCourse = DemoCourse.Clear
)

enum class DemoBluetooth {
    /** The phone's real Bluetooth and permission. */
    Phone,
    Off,
    PermissionNeeded,
    NotSupported
}

enum class DemoInRange { Garage, GarageAndFrontGate, Nothing, NewUnits }

enum class DemoProblem {
    LowBattery,
    SensorFault,
    OutOfDate,

    /** Another phone holds the garage. */
    InUse,
    PhoneLimit,
    NeedsNewerApp,

    /** A unit drops out halfway through the pass. */
    LostDuringPass,

    /** One unit fails to save the setup the first time. */
    WriteFails
}

/** How the simulated car goes through. */
enum class DemoCourse {
    Clear,

    /** Drifts below the warning gap and back. */
    Tight,

    /** Drifts below the critical gap and back. */
    Critical
}

/** The PIN of every passage in the demo. */
const val DEMO_PIN = "1234"
