import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}

val appVersion = providers
    .fileContents(rootProject.layout.projectDirectory.file("VERSION"))
    .asText
    .get()
    .trim()

val pCloudClientId = providers
    .gradleProperty("pcloudClientId")
    .orElse(providers.environmentVariable("PCLOUD_CLIENT_ID"))
    .getOrElse("")
    .trim()

val requireStableAndroidSigning = providers
    .environmentVariable("PROPERPCLOUD_REQUIRE_STABLE_SIGNING")
    .map { value -> value.equals("true", ignoreCase = true) || value == "1" }
    .getOrElse(false)
val stableSigningKeystorePath = providers.environmentVariable("PROPERPCLOUD_ANDROID_KEYSTORE_PATH").getOrElse("").trim()
val stableSigningStorePassword = providers.environmentVariable("PROPERPCLOUD_ANDROID_KEYSTORE_PASSWORD").getOrElse("")
val stableSigningKeyAlias = providers.environmentVariable("PROPERPCLOUD_ANDROID_KEY_ALIAS").getOrElse("").trim()
val stableSigningKeyPassword = providers.environmentVariable("PROPERPCLOUD_ANDROID_KEY_PASSWORD").getOrElse("")
val stableSigningValues = listOf(
    stableSigningKeystorePath,
    stableSigningStorePassword,
    stableSigningKeyAlias,
    stableSigningKeyPassword,
)
val anyStableSigningValue = stableSigningValues.any(String::isNotEmpty)
val stableSigningConfigured = stableSigningValues.all(String::isNotEmpty)

require(!anyStableSigningValue || stableSigningConfigured) {
    "stable Android signing requires keystore path, store password, key alias, and key password together"
}
require(!requireStableAndroidSigning || stableSigningConfigured) {
    "stable Android signing is required but release signing configuration is incomplete"
}

require(pCloudClientId.none(Char::isISOControl)) {
    "pCloud client ID must not contain control characters"
}

fun buildConfigString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(character)
        }
    }
    append('"')
}

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

    signingConfigs {
        getByName("debug") {
            if (stableSigningConfigured) {
                val configuredKeystore = rootProject.file(stableSigningKeystorePath)
                require(configuredKeystore.isFile) {
                    "configured Android signing keystore is missing"
                }
                storeFile = configuredKeystore
                storePassword = stableSigningStorePassword
                keyAlias = stableSigningKeyAlias
                keyPassword = stableSigningKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "dev.properpcloud.app"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = androidVersionCode(appVersion)
        versionName = appVersion
        buildConfigField("String", "PCLOUD_CLIENT_ID", buildConfigString(pCloudClientId))
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }

    sourceSets {
        getByName("main").assets.directories.add(rootProject.file("LICENSES").path)
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("properpcloud.projectRoot", rootProject.projectDir.absolutePath)
    systemProperty("robolectric.offline", "true")
    systemProperty(
        "robolectric.dependency.dir",
        rootProject.layout.projectDirectory.dir(".cache/robolectric").asFile.absolutePath,
    )
}

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    testImplementation(platform(libs.compose.bom))

    implementation(projects.coreModel)
    implementation(projects.metadataOnline)
    implementation(projects.metadataTags)
    implementation(projects.sourcePcloud)
    implementation(projects.sourceServer)
    implementation(projects.sourceWebdav)
    implementation(libs.pcloud.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
