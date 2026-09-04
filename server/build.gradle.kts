import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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
    implementation(projects.metadataTags)
    implementation(libs.gson)
    implementation(libs.sqlite.jdbc)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit4)
}

application {
    mainClass.set("dev.properpcloud.server.ServerMainKt")
}
