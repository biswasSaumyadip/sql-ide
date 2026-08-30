plugins {
    application
    alias(libs.plugins.javafx)
}

group = "com.lazaro"
version = "0.1.0"

java {
    // Compile against the Java 21 API even when the build runs on a newer JDK.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.base", "javafx.graphics", "javafx.controls", "javafx.fxml")
}

application {
    mainClass = "com.lazaro.sqlide.Main"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

dependencies {
    implementation(libs.atlantafx.base)
    implementation(libs.richtextfx)
    implementation(libs.hikaricp)
    implementation(libs.jsqlparser)

    runtimeOnly(libs.mysql.connector)
    runtimeOnly(libs.slf4j.simple)
    // Embedded engine: gives the app a zero-setup scratch database and lets the
    // headless services be integration-tested without a server.
    runtimeOnly(libs.h2)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<JavaExec>("run") {
    // JavaFX loads native libraries; JDK 24+ warns unless native access is granted.
    // The flag does not exist on older JVMs, so only add it when the build JVM supports it.
    if (JavaVersion.current() >= JavaVersion.VERSION_24) {
        jvmArgs("--enable-native-access=javafx.graphics")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = libs.versions.java.get().toInt()
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
