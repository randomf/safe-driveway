package io.github.randomf.safedriveway.passage

/** A problem the main screen tells the driver about, the most urgent first. */
enum class NoticeKind {
    /** The connection was lost: the app gets no gap from the unit until it's back. */
    Lost,
    SensorFault,
    NeedsNewerApp,
    InUse,
    PhoneLimit,
    OutOfDate,
    LowBattery
}

data class Notice(val kind: NoticeKind, val unit: UnitSetup, val live: UnitLive)

/** Every unit's problems, the most urgent first, and in gate order within the same kind. */
fun notices(setup: PassageSetup, units: Map<UnitId, UnitLive>): List<Notice> =
    setup.units.sortedWith(compareBy({ it.gate }, { it.side })).flatMap { unit ->
        units[unit.id]?.let { live -> kinds(live).map { Notice(it, unit, live) } }.orEmpty()
    }.sortedBy { it.kind.ordinal }

/** A unit's problems: what its connection says, and once connected, what it reports. */
private fun kinds(live: UnitLive): List<NoticeKind> = when (live.connection) {
    Connection.Reconnecting -> listOf(NoticeKind.Lost)

    Connection.InUse -> listOf(NoticeKind.InUse)

    Connection.PhoneLimit -> listOf(NoticeKind.PhoneLimit)

    Connection.NeedsNewerApp -> listOf(NoticeKind.NeedsNewerApp)

    Connection.Connected -> listOfNotNull(
        NoticeKind.SensorFault.takeIf { live.sensorFault },
        NoticeKind.OutOfDate.takeIf { live.outOfDate },
        NoticeKind.LowBattery.takeIf { live.lowBattery }
    )

    Connection.Searching, Connection.Connecting, Connection.Disconnected -> emptyList()
}
