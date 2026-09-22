plugins {
    java
    application
}

group = "com.paddleshock"
version = "0.3.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

val jmeVersion = "3.6.1-stable"

dependencies {
    implementation("org.jmonkeyengine:jme3-core:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-desktop:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-lwjgl3:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-effects:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-plugins:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-jogg:$jmeVersion")
    implementation("com.simsilica:lemur:1.16.0")
    runtimeOnly("org.codehaus.groovy:groovy:3.0.21")
    runtimeOnly("org.codehaus.groovy:groovy-jsr223:3.0.21")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.code-disaster.steamworks4j:steamworks4j:1.10.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("com.paddleshock.Main")
}

tasks.test {
    useJUnitPlatform()
}

// Valve's redistributable steam_api64.dll lives in native/win64 (checked into version
// control). steamworks4j's own JNI bridge library is bundled inside the steamworks4j jar
// and self-extracts to a temp directory at runtime (see SteamManager), but that bridge
// still needs to dlopen/LoadLibrary steam_api64.dll, so it must be discoverable via
// java.library.path (or the process working directory) when the game runs.
tasks.named<JavaExec>("run") {
    val nativeDir = layout.projectDirectory.dir("native/win64").asFile
    systemProperty("java.library.path", nativeDir.absolutePath)
    // steam_appid.txt (dev App ID 480) must be readable relative to the working directory.
    workingDir = layout.projectDirectory.asFile
}

// ---------------------------------------------------------------------------------------------
// Release packaging: a self-contained app image (native launcher + trimmed Java runtime + jars)
// that players can run without installing Java - the layout Steam depots expect.
//
//   ./gradlew packageApp   -> build/jpackage/image/PaddleShock/   (the folder to upload to Steam)
//   ./gradlew packageZip   -> build/distributions/PaddleShock-<version>-<os>.zip
//
// jpackage can only target the OS it runs on, so the Windows build must be produced on Windows
// (locally or via .github/workflows/package.yml). steam_appid.txt is deliberately NOT shipped:
// when launched by the Steam client the App ID comes from Steam itself.
// ---------------------------------------------------------------------------------------------

enum class TargetOs(val id: String, val nativesClassifier: String, val steamNativeDir: String?) {
    WINDOWS("windows", "natives-windows", "native/win64"),
    LINUX("linux", "natives-linux", "native/linux64"),
    MACOS("macos", "natives-macos", "native/osx");

    companion object {
        fun current(): TargetOs {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("win") -> WINDOWS
                os.contains("mac") -> MACOS
                else -> LINUX
            }
        }
    }
}

val targetOs = TargetOs.current()
val jpackageDir = layout.buildDirectory.dir("jpackage")

// Produced by `jdeps --print-module-deps` over the runtime classpath, plus modules jdeps can't see
// because they're only reached via service loading: jdk.crypto.ec (ECDHE TLS for the HTTPS calls
// to the AWS backend), jdk.localedata (pt_BR formatting), jdk.charsets.
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.logging", "java.management", "java.net.http", "java.prefs",
    "java.scripting", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "jdk.localedata", "jdk.charsets",
)

val javaToolchainLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }

fun jdkTool(name: String): String {
    val home = javaToolchainLauncher.get().metadata.installationPath.asFile
    val exe = if (targetOs == TargetOs.WINDOWS) "$name.exe" else name
    return home.resolve("bin").resolve(exe).absolutePath
}

val jlinkRuntime by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a trimmed Java runtime image containing only the modules the game needs."
    val outputDir = jpackageDir.map { it.dir("runtime") }
    outputs.dir(outputDir)
    inputs.property("modules", runtimeModules)
    doFirst { delete(outputDir) }
    executable = jdkTool("jlink")
    args(
        "--add-modules", runtimeModules.joinToString(","),
        "--include-locales", "en,pt",
        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "zip-6",
        "--output", outputDir.get().asFile.absolutePath,
    )
}

val jpackageInput by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Collects the game jar, its dependencies (host-OS natives only) and Steam natives."
    into(jpackageDir.map { it.dir("input") })
    from(tasks.jar)
    // LWJGL ships one natives jar per OS/arch; only keep the 64-bit ones for the OS being packaged.
    from(configurations.runtimeClasspath) {
        exclude { f ->
            val n = f.name
            n.contains("-natives-") && !(n.endsWith("-${targetOs.nativesClassifier}.jar") ||
                (targetOs == TargetOs.MACOS && n.endsWith("-natives-macos-arm64.jar")))
        }
    }
    // Valve's steam_api redistributable; loaded via java.library.path=$APPDIR (see jpackageImage).
    targetOs.steamNativeDir?.let { dir ->
        from(layout.projectDirectory.dir(dir)) { include("*.dll", "*.so", "*.dylib") }
    }
}

val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds the self-contained app image (native launcher + bundled JRE)."
    dependsOn(jlinkRuntime, jpackageInput)
    val imageRoot = jpackageDir.map { it.dir("image") }
    inputs.dir(jpackageDir.map { it.dir("input") })
    inputs.dir(jpackageDir.map { it.dir("runtime") })
    outputs.dir(imageRoot)
    doFirst { delete(imageRoot) }
    executable = jdkTool("jpackage")
    val jarName = tasks.jar.get().archiveFileName.get()
    args(
        "--type", "app-image",
        "--name", "PaddleShock",
        "--app-version", project.version.toString(),
        "--vendor", "PaddleShock",
        "--input", jpackageDir.get().dir("input").asFile.absolutePath,
        "--main-jar", jarName,
        "--main-class", application.mainClass.get(),
        "--runtime-image", jpackageDir.get().dir("runtime").asFile.absolutePath,
        "--dest", imageRoot.get().asFile.absolutePath,
        // $APPDIR is expanded by the jpackage launcher at startup to the folder holding the jars
        // (and steam_api64.dll), so the Steam native resolves no matter what the working dir is.
        "--java-options", "-Djava.library.path=\$APPDIR",
        "--java-options", "-Dfile.encoding=UTF-8",
    )
    if (targetOs == TargetOs.MACOS) {
        // GLFW must own the main thread on macOS.
        args("--java-options", "-XstartOnFirstThread")
    }
    // Icons: jpackage wants .ico (Windows), .png (Linux) or .icns (macOS). Drop one into branding/
    // and it is picked up automatically.
    val iconExt = when (targetOs) { TargetOs.WINDOWS -> "ico"; TargetOs.LINUX -> "png"; TargetOs.MACOS -> "icns" }
    val icon = layout.projectDirectory.file("branding/paddleshock.$iconExt").asFile
    if (icon.exists()) {
        args("--icon", icon.absolutePath)
    }
}

val packageApp by tasks.registering {
    group = "distribution"
    description = "Builds the Steam-ready app image into build/jpackage/image/PaddleShock."
    dependsOn(jpackageImage)
}

val packageZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the app image for sharing/CI artifacts."
    dependsOn(jpackageImage)
    archiveFileName.set("PaddleShock-${project.version}-${targetOs.id}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(jpackageDir.map { it.dir("image") })
}
