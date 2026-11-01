import java.util.Properties
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

val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun signingInput(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseSigningInputs = mapOf(
    "storeFile" to signingInput("ANDROID_KEYSTORE_FILE", "storeFile"),
    "storePassword" to signingInput("ANDROID_KEYSTORE_PASSWORD", "storePassword"),
    "keyAlias" to signingInput("ANDROID_KEY_ALIAS", "keyAlias"),
    "keyPassword" to signingInput("ANDROID_KEY_PASSWORD", "keyPassword"),
)
val missingSigningInputs = releaseSigningInputs.filterValues { it == null }.keys
val releaseSigningConfigured = missingSigningInputs.isEmpty()
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").orNull == "true"

if (requireReleaseSigning && !releaseSigningConfigured) {
    throw GradleException("Release signing is required but missing: ${missingSigningInputs.sorted().joinToString()}")
}
if (releaseSigningConfigured && !rootProject.file(releaseSigningInputs.getValue("storeFile")!!).isFile) {
    throw GradleException("Release keystore not found: ${releaseSigningInputs.getValue("storeFile")}")
}
if (!releaseSigningConfigured && missingSigningInputs.size < releaseSigningInputs.size) {
    throw GradleException("Incomplete release signing configuration; missing: ${missingSigningInputs.sorted().joinToString()}")
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
    namespace = "dev.fkr.properpcloud.app"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseSigningInputs.getValue("storeFile")!!)
                storePassword = releaseSigningInputs.getValue("storePassword")
                keyAlias = releaseSigningInputs.getValue("keyAlias")
                keyPassword = releaseSigningInputs.getValue("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "dev.fkr.properpcloud.app"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = androidVersionCode(appVersion)
        versionName = appVersion
        buildConfigField("String", "PCLOUD_CLIENT_ID", buildConfigString(pCloudClientId))
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName(if (releaseSigningConfigured) "release" else "debug")
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
