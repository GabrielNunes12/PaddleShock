plugins {
    java
    application
}

group = "com.paddleshock"
version = "0.2.0"

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
