import org.gradle.internal.os.OperatingSystem

plugins {
    application
    alias(libs.plugins.javafx)
    alias(libs.plugins.beryx.runtime)
}

group = "com.lazaro"
version = "1.0.0"

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
    applicationName = "SqlIDE"
    mainClass = "com.lazaro.sqlide.Main"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

dependencies {
    implementation(libs.atlantafx.base)
    implementation(libs.richtextfx)
    implementation(libs.hikaricp)
    implementation(libs.jsqlparser)
    implementation(libs.jackson.databind)
    implementation(libs.sql.formatter)
    implementation(libs.jedis)

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

/*
 * Native packaging (non-modular classpath app):
 *   org.beryx.runtime runs jlink (custom JRE) then jpackage (--main-jar / --main-class).
 * There is no module-info.java; JavaFX stays on the classpath via javafxplugin.
 *
 *   ./gradlew jpackage          # Linux/macOS → build/jpackage/
 *   .\gradlew.bat jpackage      # Windows
 *
 * Intermediate: ./gradlew runtime   (custom runtime image under build/image)
 *               ./gradlew suggestModules
 */
runtime {
    options.set(
        listOf(
            "--strip-debug",
            "--compress",
            "zip-6",
            "--no-header-files",
            "--no-man-pages",
        ),
    )

    // JDK modules only — do not list javafx.* here (they ship as JARs on the classpath).
    modules.set(
        listOf(
            "java.desktop",
            "java.logging",
            "java.management",
            "java.naming",
            "java.net.http",
            "java.prefs",
            "java.security.jgss",
            "java.sql",
            "java.sql.rowset",
            "java.xml",
            "jdk.charsets",
            "jdk.crypto.ec",
            "jdk.unsupported",
        ),
    )

    launcher {
        noConsole = true
        jvmArgs = buildList {
            add("-Dfile.encoding=UTF-8")
            if (JavaVersion.current() >= JavaVersion.VERSION_24) {
                add("--enable-native-access=javafx.graphics")
            }
        }
    }

    jpackage {
        imageName = "SqlIDE"
        installerName = "SqlIDE"
        appVersion = project.version.toString()
        mainClass = "com.lazaro.sqlide.Main"
        skipInstaller = false

        val os = OperatingSystem.current()
        installerType = when {
            os.isWindows -> "msi"
            os.isLinux -> "deb"
            os.isMacOsX -> "dmg"
            else -> null
        }

        val iconExt = when {
            os.isWindows -> "ico"
            os.isMacOsX -> "icns"
            else -> "png"
        }
        val iconFile = layout.projectDirectory.file("src/main/resources/assets/sqlide.$iconExt").asFile
        imageOptions = buildList {
            if (iconFile.isFile) {
                addAll(listOf("--icon", iconFile.absolutePath))
            }
        }

        installerOptions = buildList {
            addAll(
                listOf(
                    "--vendor", "Lazaro",
                    "--description", "A minimalist, high-density SQL IDE.",
                ),
            )
            when {
                os.isWindows -> addAll(
                    listOf(
                        "--win-per-user-install",
                        "--win-dir-chooser",
                        "--win-menu",
                        "--win-shortcut",
                    ),
                )
                os.isLinux -> addAll(
                    listOf(
                        "--linux-package-name", "sqlide",
                        "--linux-shortcut",
                        "--linux-menu-group", "Development",
                    ),
                )
                os.isMacOsX -> addAll(
                    listOf(
                        "--mac-package-name", "SqlIDE",
                    ),
                )
            }
        }
    }
}
