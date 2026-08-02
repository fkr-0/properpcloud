plugins {
    id("com.android.library")
}

android {
    namespace = "dev.properpcloud.metadata.tags"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(projects.coreModel)
    implementation(libs.jaudiotagger)
    testImplementation(libs.junit4)
}
