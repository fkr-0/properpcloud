import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(projects.coreModel)
    implementation(projects.sourcePcloud)
    implementation(projects.sourceWebdav)
    implementation(projects.metadataOnline)
    implementation(projects.metadataTags)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.core)
    implementation(libs.gson)
    implementation(libs.sqlite.jdbc)
    implementation(libs.dbus.core)
    implementation(libs.dbus.native)
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
}

val appVersion = providers.fileContents(rootProject.layout.projectDirectory.file("VERSION")).asText.get().trim()

compose.desktop {
    application {
        mainClass = "dev.properpcloud.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            modules("java.sql", "jdk.security.auth")
            packageName = "properpcloud"
            packageVersion = appVersion
            description = "Folder-first pCloud audio player"
            vendor = "properpcloud contributors"
            linux {
                // RPM reserves '-' for the package release delimiter. Use '~' so a SemVer
                // prerelease remains ordered before the corresponding final version.
                rpmPackageVersion = appVersion.replace("-", "~")
                shortcut = true
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
            }
        }
    }
}
