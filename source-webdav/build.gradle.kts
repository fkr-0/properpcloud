plugins {
    id("com.android.library")
}

android {
    namespace = "dev.properpcloud.source.webdav"
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
    api(projects.coreModel)
    testImplementation(libs.junit4)
}
