package io.github.randomf.safedriveway.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.backend.UnlockResult
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.PassageSetup
import io.github.randomf.safedriveway.passage.UnitId
import io.github.randomf.safedriveway.ui.driving.DrivingScreen
import io.github.randomf.safedriveway.ui.settings.PassageSettingsScreen
import io.github.randomf.safedriveway.ui.settings.PinResetScreen
import io.github.randomf.safedriveway.ui.settings.ReplaceUnitScreen
import io.github.randomf.safedriveway.ui.setup.SetupDraft
import io.github.randomf.safedriveway.ui.setup.SetupOverviewScreen
import io.github.randomf.safedriveway.ui.setup.SetupStepScreen
import io.github.randomf.safedriveway.ui.setup.Step
import io.github.randomf.safedriveway.ui.setup.WriteScreen
import io.github.randomf.safedriveway.ui.start.AboutScreen
import io.github.randomf.safedriveway.ui.start.PassagesScreen
import io.github.randomf.safedriveway.ui.start.ScanningScreen
import io.github.randomf.safedriveway.ui.start.StartRoute
import io.github.randomf.safedriveway.ui.start.WhichPassageScreen
import kotlinx.coroutines.launch

/** The app's screens: the keys of the back stack (app/screens.md). */
sealed interface Screen {
    data object Scanning : Screen

    data object WhichPassage : Screen

    data object Passages : Screen

    data class Driving(val passage: PassageId) : Screen

    data class Settings(val passage: PassageId) : Screen

    data object About : Screen

    data class PinReset(val passage: PassageId) : Screen

    data object SetupOverview : Screen

    data class SetupStep(val step: Step, val wizard: Boolean) : Screen

    data class Writing(val after: AfterWrite) : Screen

    data class ReplaceUnit(val passage: PassageId, val unit: UnitId) : Screen
}

/** Where the app goes once a setup has been written. */
sealed interface AfterWrite {
    data class Open(val passage: PassageId) : AfterWrite

    data object BackToPassage : AfterWrite

    data object ToPassages : AfterWrite
}

private class PinRequest(val passage: PassageId, val then: () -> Unit)

/**
 * Moves between the screens. The passage of the topmost main screen on the back stack stays
 * open, so its units stay connected while its settings and setup are on top.
 */
@Composable
fun AppNavigation(backend: Backend, version: String) {
    val backStack = remember { mutableStateListOf<Screen>(Screen.Scanning) }
    // The setup being made or changed: the one piece of it the screens share.
    var draft by remember { mutableStateOf<SetupDraft?>(null) }
    var pinRequest by remember { mutableStateOf<PinRequest?>(null) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    // The app opened with no passage in range: the first to come in range opens, until the
    // driver moves on from the passages list.
    var autoOpen by remember { mutableStateOf(false) }
    // The setups that name the units on the write screen, as they were before the write.
    var writeNames by remember { mutableStateOf<List<PassageSetup>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val open by backend.open.collectAsState()
    val remembered by backend.remembered.collectAsState()

    fun push(screen: Screen) {
        autoOpen = false
        backStack += screen
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun reset(vararg screens: Screen) {
        autoOpen = false
        backStack.clear()
        backStack.addAll(screens)
    }

    fun route(route: StartRoute) = when (route) {
        is StartRoute.Open -> reset(Screen.Passages, Screen.Driving(route.passage))

        StartRoute.Choose -> reset(Screen.Passages, Screen.WhichPassage)

        StartRoute.NoneInRange -> {
            reset(Screen.Passages)
            autoOpen = true
        }
    }

    fun requirePin(passage: PassageId, then: () -> Unit) {
        scope.launch {
            val knows = backend.remembered.value.find { it.setup.id == passage }?.knowsPin == true
            if (knows && backend.unlock(passage, null) == UnlockResult.Unlocked) {
                then()
            } else {
                pinRequest = PinRequest(passage, then)
            }
        }
    }

    fun startWrite(after: AfterWrite, write: () -> Unit) {
        writeNames =
            listOfNotNull(draft?.original, draft?.setup, open?.setup) +
            remembered.map { it.setup }
        write()
        push(Screen.Writing(after))
    }

    fun finishWrite(after: AfterWrite) {
        backend.clearWrite()
        draft = null
        when (after) {
            is AfterWrite.Open -> reset(Screen.Passages, Screen.Driving(after.passage))

            AfterWrite.BackToPassage ->
                while (backStack.size > 1 && backStack.last() !is Screen.Driving) pop()

            AfterWrite.ToPassages -> reset(Screen.Passages)
        }
    }

    /** Leaves a screen that unlocked the units without writing: a unit doesn't signal while unlocked. */
    fun leaveUnlocked() {
        backend.lock()
        pop()
    }

    fun discardSetup() {
        backend.lock()
        draft = null
        confirmingDiscard = false
        pop()
    }

    val openId = (backStack.lastOrNull { it is Screen.Driving } as Screen.Driving?)?.passage
    LaunchedEffect(openId) {
        if (openId != null) backend.openPassage(openId) else backend.closePassage()
    }

    NavDisplay(
        backStack = backStack,
        onBack = {
            when (val top = backStack.last()) {
                is Screen.Writing ->
                    if (backend.writing.value?.finished != false) finishWrite(top.after)

                is Screen.ReplaceUnit -> leaveUnlocked()

                Screen.SetupOverview -> if (draft?.changed == true) {
                    confirmingDiscard = true
                } else {
                    discardSetup()
                }

                else -> pop()
            }
        },
        entryProvider = entryProvider {
            entry<Screen.Scanning> { ScanningScreen(backend, ::route) }
            entry<Screen.WhichPassage> {
                WhichPassageScreen(backend, ::pop) { reset(Screen.Passages, Screen.Driving(it)) }
            }
            entry<Screen.Passages> {
                PassagesScreen(
                    backend,
                    autoOpen,
                    onRoute = ::route,
                    onOpen = { push(Screen.Driving(it)) },
                    onNewPassage = {
                        draft = SetupDraft.forNew()
                        push(Screen.SetupStep(Step.Name, wizard = true))
                    },
                    onAbout = { push(Screen.About) }
                )
            }
            entry<Screen.Driving> { key ->
                val passage = key.passage
                DrivingScreen(
                    backend,
                    passage,
                    onBack = ::pop,
                    onSettings = { push(Screen.Settings(passage)) },
                    onReplaceUnit = { unit ->
                        requirePin(passage) { push(Screen.ReplaceUnit(passage, unit)) }
                    },
                    onForgetUnit = { unit ->
                        requirePin(passage) {
                            val setup = backend.open.value?.setup ?: return@requirePin
                            startWrite(AfterWrite.BackToPassage) {
                                backend.write(
                                    setup.withoutUnit(unit).copy(version = setup.version + 1),
                                    pin = null,
                                    forget = listOf(unit)
                                )
                            }
                        }
                    },
                    onBringUpToDate = {
                        requirePin(passage) {
                            startWrite(AfterWrite.BackToPassage, backend::bringUpToDate)
                        }
                    }
                )
            }
            entry<Screen.Settings> { key ->
                PassageSettingsScreen(
                    backend,
                    key.passage,
                    onBack = ::pop,
                    onChangeSetup = {
                        requirePin(key.passage) {
                            val setup = backend.open.value?.setup?.takeIf { it.id == key.passage }
                                ?: return@requirePin
                            draft = SetupDraft.forEdit(setup)
                            push(Screen.SetupOverview)
                        }
                    },
                    onForgotPin = { push(Screen.PinReset(key.passage)) },
                    onRemoved = { reset(Screen.Passages) }
                )
            }
            entry<Screen.About> { AboutScreen(version, ::pop) }
            entry<Screen.PinReset> { key ->
                PinResetScreen(backend, key.passage, ::pop, onDone = ::pop)
            }
            entry<Screen.SetupOverview> {
                draft?.let { current ->
                    SetupOverviewScreen(
                        current,
                        confirmingDiscard = confirmingDiscard,
                        onConfirmingDiscard = { confirmingDiscard = it },
                        onSection = { push(Screen.SetupStep(it, wizard = false)) },
                        onSave = {
                            startWrite(AfterWrite.BackToPassage) {
                                backend.write(
                                    current.toWrite(),
                                    current.newPin,
                                    current.forgotten.toList()
                                )
                            }
                        },
                        onDiscard = ::discardSetup,
                        onDelete = {
                            startWrite(AfterWrite.ToPassages) {
                                backend.deletePassage(current.setup.id)
                            }
                        }
                    )
                }
            }
            entry<Screen.SetupStep> { key ->
                draft?.let { current ->
                    SetupStepScreen(
                        key.step,
                        current,
                        onChange = { draft = it },
                        backend,
                        key.wizard,
                        onBack = ::pop,
                        onNext = {
                            // A step may have changed the draft just before: read it afresh.
                            val latest = draft ?: return@SetupStepScreen
                            val next = latest.next(key.step)
                            when {
                                !key.wizard -> pop()

                                next != null -> push(Screen.SetupStep(next, wizard = true))

                                else -> startWrite(AfterWrite.Open(latest.setup.id)) {
                                    backend.write(
                                        latest.toWrite(),
                                        latest.newPin,
                                        forget = emptyList()
                                    )
                                }
                            }
                        }
                    )
                }
            }
            entry<Screen.Writing> { key ->
                WriteScreen(backend, writeNames) { finishWrite(key.after) }
            }
            entry<Screen.ReplaceUnit> { key ->
                ReplaceUnitScreen(backend, key.passage, key.unit, ::leaveUnlocked) { new ->
                    val setup = backend.open.value?.setup ?: return@ReplaceUnitScreen
                    startWrite(AfterWrite.BackToPassage) {
                        backend.write(
                            setup.withReplaced(key.unit, new).copy(version = setup.version + 1),
                            pin = null,
                            forget = listOf(key.unit)
                        )
                    }
                }
            }
        }
    )

    pinRequest?.let { request ->
        val name = remembered.find { it.setup.id == request.passage }?.setup?.name.orEmpty()
        PinDialog(
            backend,
            request.passage,
            name,
            onUnlocked = {
                pinRequest = null
                request.then()
            },
            onForgot = {
                pinRequest = null
                push(Screen.PinReset(request.passage))
            },
            onDismiss = { pinRequest = null }
        )
    }
}
