plugins {
    id("com.android.application")
}

val appVersion = providers
    .fileContents(rootProject.layout.projectDirectory.file("VERSION"))
    .asText
    .get()
    .trim()

fun androidVersionCode(version: String): Int {
    val core = version.substringBefore('-').substringBefore('+')
    val parts = core.split('.')
    require(parts.size == 3) { "VERSION must contain major.minor.patch" }
    val (major, minor, patch) = parts.map(String::toInt)
    return major * 1_000_000 + minor * 1_000 + patch
}

android {
    namespace = "dev.properpcloud.app"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()

    defaultConfig {
        applicationId = "dev.properpcloud.app"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = androidVersionCode(appVersion)
        versionName = appVersion
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(projects.coreModel)
    implementation(projects.sourcePcloud)
    implementation(projects.sourceWebdav)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
}
