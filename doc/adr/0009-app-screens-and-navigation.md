# 9. Screens, navigation and a demo backend for the app

- **Status:** Accepted
- **Date:** 2026-11-14

## Context

[app/features.md](../../app/features.md) describes what the first version of the app does. Before the BLE code is written, every screen is built as a working prototype, to settle how the app looks and flows, and to try the main screen in a phone holder at arm's length. The prototype has to run on a phone without units, and its screens have to become the real ones rather than be thrown away.

The app has about twenty screens and sheets: the start, a main screen, a unit's details, a passage's settings, the PIN, and a wizard and an editor for the setup ([app/screens.md](../../app/screens.md)). It needs a way to move between them that works on Android and iOS, with Android's back and predictive back gestures. The main screen works in portrait and landscape, and turning the phone mustn't drop the units' connections.

## Decision

- **Navigation:** [Navigation 3](https://developer.android.com/guide/navigation/navigation-3), in JetBrains' multiplatform build, `org.jetbrains.androidx.navigation3:navigation3-ui`, under the Apache-2.0 licence. The back stack is a list of the app's own `Screen` keys, which the app changes like any list; `NavDisplay` shows its top and handles back. Sheets and dialogs, such as a unit's details and the PIN, belong to the screen they're shown over rather than to the back stack.
- **Turning the phone:** the Android activity handles orientation and screen size changes itself, and the start of night or car mode (`android:configChanges` in the manifest), so Compose redraws the screen in place and the back stack, the screens' state and the connections stay. The back stack isn't saved for after the app has been closed: the app starts over by looking for the passage in range, which is what opening it does anyway.
- **Screens:** composables that take what they show and callbacks for what the driver does. The logic that turns readings into the main screen, the gaps, the steering suggestion, the car's position and the notices, is plain Kotlin in the `passage` package, tested in `commonTest`.
- **Backend:** one interface, `Backend`, stands for everything below the screens: the units over BLE, and what the phone stores. The screens use only it.
- **Demo backend:** for now, `DemoBackend` implements `Backend` with a pretend world: two passages, a few new units, and a simulated car that drives through the open passage. Its demo panel, in debug builds only, sets which passages are in range, which problems the units have and whether the phone knows the PIN, and plays a pass. Nothing is stored when the app closes. The BLE code replaces it with a real implementation of the same interface.
- **Icons:** the Material icons, `org.jetbrains.compose.material:material-icons-core`, Apache-2.0. The steering arrows and the passage are drawn by the app.
- **Previews:** `@Preview` functions in `commonMain` for the main states, rendered by Android Studio with `org.jetbrains.compose.ui:ui-tooling`.

## Alternatives considered

- **Navigation Compose**, JetBrains' multiplatform build of the older androidx navigation with a `NavController` and routes. It owns the back stack, which makes the app's own jumps, such as from the wizard's end straight to the new passage's main screen, harder to express. Google's new work goes into Navigation 3.
- **The app's own back stack and back handling, without a library.** Little to learn, but predictive back, transitions and saving each screen's state would be the app's to write and keep working on both platforms.
- **Saving the back stack for after the app is killed,** with `rememberNavBackStack` and serializable keys. It needs kotlinx.serialization for every key, and restoring a deep screen after the app was gone isn't wanted: the units have to be found and connected again anyway.
- **Recreating the activity when the phone turns,** Android's default, and keeping the state in ViewModels. More code and a lifecycle library for what Compose already handles when the activity stays.
- **ViewModels per screen.** More structure, but the screens hold little state of their own; the backend holds what lasts.
- **Clickable mockups outside the app,** as HTML or in a design tool. Quicker to change, but they can't be tried in the car, and would be redone in Compose.
- **The Material Symbols as vector resources.** Newer, but each icon has to be copied into the app by hand. The core icons cover what the app needs.

## Consequences

- The screens can be tried on any Android phone, and on the emulator, without units. The debug APK that CI builds shows the demo panel.
- The BLE work implements `Backend` and the phone's storage, and the screens don't change. Whether the demo stays in debug builds is decided then.
- Every new screen is a `Screen` key and an entry in `ui/Navigation.kt`, and its texts go into the string resources in English and Czech.
- The Material icons' last release is 1.7.3. They're plain vectors, so they keep working with later Compose releases, but there will be no new ones.
- A change of language, font size or display density while the app is open still recreates the activity, and the app starts over by looking for the passage.
