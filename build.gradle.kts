plugins {
    java
    application
}

group = "com.paddleshock"
version = "0.1.0"

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

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("com.paddleshock.Main")
}

tasks.test {
    useJUnitPlatform()
}
