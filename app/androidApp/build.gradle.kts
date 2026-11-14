// No Compose here: shared sets the screen (setAppContent), so this module stays a plain app.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.randomf.safedriveway"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.randomf.safedriveway"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity)
}

// `just run`: install the debug build, then start it on the connected phone.
tasks.register<Exec>("startDebug") {
    group = "install"
    description = "Installs the debug build and starts it on the connected device."
    dependsOn("installDebug")
    val adb = androidComponents.sdkComponents.adb
    val activity = "${android.defaultConfig.applicationId}/.MainActivity"
    doFirst { executable = adb.get().asFile.path }
    args("shell", "am", "start", "-n", activity)
}
