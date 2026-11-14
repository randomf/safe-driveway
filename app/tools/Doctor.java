// Checks that this computer is ready to build the app and run it on a phone, and says how to fix
// what isn't. It never changes anything itself.
//
// `just doctor` runs it as `java tools/Doctor.java <SDK path>`, so it needs nothing but a JDK, even
// before Gradle can build. The argument is where Android Studio installs the SDK, which the
// justfile knows. It's written for Java 11, so that it can still say when Java is too old.

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Doctor {
    static final String OK = "✓";
    static final String FAIL = "✗";
    static final String WARN = "!";

    static final String OS = System.getProperty("os.name");
    static final boolean WINDOWS = OS.startsWith("Windows");
    static final boolean LINUX = OS.startsWith("Linux");

    // Gradle 9 needs Java 17 to start. It then builds with the JDK in gradle-daemon-jvm.properties.
    static final int GRADLE_MIN_JAVA = 17;

    static final String[] USB_DEBUGGING = {
        "Turn on USB debugging. On most phones:",
        "  1. Open Settings > About phone and tap Build number 7 times.",
        "  2. Open Settings > System > Developer options and turn on USB debugging.",
        "  3. Replug the phone, unlock it and accept \"Allow USB debugging?\".",
    };

    static int failures = 0;

    static void report(String symbol, String message, String... notes) {
        if (symbol.equals(FAIL)) {
            failures++;
        }
        System.out.println(symbol + " " + message);
        for (String note : notes) {
            System.out.println("    " + note);
        }
    }

    public static void main(String[] args) {
        checkJava();
        Path sdk = checkSdk(args.length > 0 ? Path.of(args[0]) : null);
        boolean phone = false;
        if (sdk != null) {
            checkPlatform(sdk);
            Path adb = checkAdb(sdk);
            if (adb != null) {
                checkOtherAdb(adb);
                phone = checkPhone(adb);
            }
        }
        if (failures > 0) {
            System.out.println("\n" + failures + " problem(s) to fix before building the app.");
            System.exit(1);
        }
        System.out.println(phone ? "\nReady." : "\nReady to build. `just run` needs a phone.");
    }

    static void checkJava() {
        int running = Runtime.version().feature();
        String pinned = readProperties(Path.of("gradle", "gradle-daemon-jvm.properties"))
            .getProperty("toolchainVersion", "?");
        if (running < GRADLE_MIN_JAVA) {
            report(FAIL, "Java " + Runtime.version() + " is too old to start Gradle",
                "Install JDK " + GRADLE_MIN_JAVA + " or later and put it first on PATH, or set JAVA_HOME.");
        } else {
            report(OK, "Java " + Runtime.version() + " starts Gradle",
                "Gradle builds with JDK " + pinned + ", and downloads it on the first build if it's missing.");
        }
    }

    /**
     * Finds the SDK the way the Android Gradle plugin does. The justfile sets ANDROID_HOME to
     * {@code studioDefault} when neither variable is set, so that path is named as the default.
     */
    static Path checkSdk(Path studioDefault) {
        String sdkDir = readProperties(Path.of("local.properties")).getProperty("sdk.dir");
        String home = System.getenv("ANDROID_HOME");
        String root = System.getenv("ANDROID_SDK_ROOT");
        String source;
        Path sdk;
        if (sdkDir != null) {
            sdk = Path.of(sdkDir);
            source = "sdk.dir in local.properties";
        } else if (home != null || root != null) {
            sdk = Path.of(home != null ? home : root);
            if (sdk.equals(studioDefault)) {
                source = "Android Studio's default";
            } else {
                source = root != null && sdk.equals(Path.of(root)) ? "ANDROID_SDK_ROOT" : "ANDROID_HOME";
            }
        } else if (studioDefault != null) {
            sdk = studioDefault;
            source = "Android Studio's default";
        } else {
            report(FAIL, "No Android SDK is set",
                "Run `just doctor`, which knows where Android Studio installs it, or set ANDROID_HOME.");
            return null;
        }

        if (!Files.isDirectory(sdk)) {
            report(FAIL, "No Android SDK in " + sdk + " (" + source + ")",
                "Install Android Studio and finish its setup wizard, which installs the SDK.",
                "If the SDK is somewhere else, set ANDROID_HOME to it.");
            return null;
        }
        if (!Files.exists(sdk.resolve("licenses").resolve("android-sdk-license"))) {
            report(FAIL, "The Android SDK licence in " + sdk + " hasn't been accepted",
                "Finish Android Studio's setup wizard, or accept it in Settings > Languages & Frameworks > Android SDK.");
            return null;
        }
        report(OK, "Android SDK in " + sdk + " (" + source + ")");
        return sdk;
    }

    static void checkPlatform(Path sdk) {
        String compileSdk = "?";
        try {
            String catalog = Files.readString(Path.of("gradle", "libs.versions.toml"));
            Matcher m = Pattern.compile("(?m)^android-compileSdk\\s*=\\s*\"(\\d+)\"").matcher(catalog);
            if (m.find()) {
                compileSdk = m.group(1);
            }
        } catch (IOException e) {
            // Reported as an unknown version below.
        }
        String name = "android-" + compileSdk;
        if (Files.isDirectory(sdk.resolve("platforms").resolve(name))
            || Files.isDirectory(sdk.resolve("platforms").resolve(name + ".0"))) {
            report(OK, "SDK platform " + name + " is installed");
        } else {
            report(WARN, "SDK platform " + name + " isn't installed yet",
                "Gradle downloads it on the first build.");
        }
    }

    static Path checkAdb(Path sdk) {
        Path adb = sdk.resolve("platform-tools").resolve(WINDOWS ? "adb.exe" : "adb");
        if (!Files.isExecutable(adb)) {
            report(FAIL, "The SDK has no adb",
                "Install Android SDK Platform-Tools in Android Studio: Tools > SDK Manager > SDK Tools.");
            return null;
        }
        report(OK, "adb " + adbVersion(adb) + " is in the SDK");
        return adb;
    }

    /** Two adb versions restart each other's server, which drops the phone's connection. */
    static void checkOtherAdb(Path adb) {
        Path other = onPath(WINDOWS ? "adb.exe" : "adb");
        if (other == null) {
            return;
        }
        try {
            if (Files.isSameFile(other, adb)) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        String version = adbVersion(other);
        if (!version.equals(adbVersion(adb))) {
            report(WARN, "Another adb, version " + version + ", comes first on PATH: " + other,
                "Two adb versions keep restarting each other's server, and the phone disconnects.",
                "The tasks use the SDK's adb. If you run adb yourself, put " + adb.getParent()
                    + " before it on PATH, or uninstall it.");
        }
    }

    static boolean checkPhone(Path adb) {
        String output = run(adb.toString(), "devices", "-l");
        if (output == null) {
            report(WARN, "adb didn't answer", "Restart its server: `" + adb + " kill-server`, then try again.");
            return false;
        }
        boolean ready = false;
        List<String> lines = new ArrayList<>(List.of(output.split("\\R")));
        lines.removeIf(line -> line.isBlank() || line.startsWith("List of devices") || line.startsWith("*"));
        for (String line : lines) {
            String[] fields = line.trim().split("\\s+");
            String serial = fields[0];
            String state = fields.length > 1 ? fields[1] : "";
            String name = serial;
            for (String field : fields) {
                if (field.startsWith("model:")) {
                    name = field.substring("model:".length()).replace('_', ' ') + " (" + serial + ")";
                }
            }
            if (state.equals("device")) {
                report(OK, name + " is connected");
                ready = true;
            } else if (state.equals("unauthorized")) {
                report(WARN, name + " hasn't allowed USB debugging from this computer",
                    "Unlock the phone and accept \"Allow USB debugging?\". If it doesn't ask, replug it.");
            } else if (line.contains("no permissions")) {
                report(WARN, "You aren't allowed to open " + serial + " over USB",
                    "Install the Android udev rules. On Debian, Ubuntu and Pop!_OS:",
                    "`sudo apt install android-sdk-platform-tools-common`, then replug the phone.");
            } else {
                report(WARN, name + " is " + state, "Replug the phone.");
            }
        }
        if (lines.isEmpty()) {
            String plugged = LINUX ? phoneWithoutDebuggingLinux() : null;
            if (plugged != null) {
                report(WARN, plugged + " is plugged in, but USB debugging is off", USB_DEBUGGING);
            } else {
                List<String> notes = new ArrayList<>();
                notes.add("Plug in the phone with a cable that carries data, not a charge-only one.");
                notes.addAll(List.of(USB_DEBUGGING));
                report(WARN, "No phone found", notes.toArray(new String[0]));
            }
        }
        return ready;
    }

    /** A USB device with an MTP or PTP interface (file transfer) is most likely a phone. */
    static String phoneWithoutDebuggingLinux() {
        try (var devices = Files.list(Path.of("/sys/bus/usb/devices"))) {
            for (Path device : (Iterable<Path>) devices::iterator) {
                if (!Files.exists(device.resolve("idVendor"))) {
                    continue;
                }
                try (var interfaces = Files.list(device)) {
                    boolean fileTransfer = interfaces
                        .filter(i -> i.getFileName().toString().contains(":"))
                        .anyMatch(i -> {
                            String name = readLine(i.resolve("interface"));
                            return "MTP".equals(name) || "PTP".equals(name)
                                || "06".equals(readLine(i.resolve("bInterfaceClass")));
                        });
                    if (fileTransfer) {
                        String product = readLine(device.resolve("product"));
                        return product != null ? product : "A phone";
                    }
                }
            }
        } catch (IOException e) {
            // No sysfs to look at.
        }
        return null;
    }

    static String adbVersion(Path adb) {
        String output = run(adb.toString(), "version");
        if (output != null) {
            Matcher m = Pattern.compile("(?m)^Version (\\S+?)(-|$)").matcher(output);
            if (m.find()) {
                return m.group(1);
            }
        }
        return "of unknown version";
    }

    static Path onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            // Windows allows quoted entries. One that still isn't a valid path can't hold adb.
            dir = dir.strip();
            if (dir.length() > 1 && dir.startsWith("\"") && dir.endsWith("\"")) {
                dir = dir.substring(1, dir.length() - 1);
            }
            if (dir.isEmpty()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir).resolve(program);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            } catch (InvalidPathException e) {
                // Skip it.
            }
        }
        return null;
    }

    /** Runs a program and returns its output, or null if it fails or takes over 30 seconds. */
    static String run(String... command) {
        Path output = null;
        try {
            // Output goes to a file, not a pipe: a server that adb starts would keep a pipe open.
            output = Files.createTempFile("doctor", ".txt");
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroy();
                return null;
            }
            return process.exitValue() == 0 ? Files.readString(output, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (output != null) {
                output.toFile().delete();
            }
        }
    }

    static Properties readProperties(Path file) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            // A missing file reads as empty.
        }
        return properties;
    }

    static String readLine(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return null;
        }
    }
}
