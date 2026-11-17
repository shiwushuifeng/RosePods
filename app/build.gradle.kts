plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "moe.chenxy.rosepods"
    compileSdk = 36

    defaultConfig {
        applicationId = "moe.chenxy.rosepods"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = "rosepods123"
            keyAlias = "rosepods"
            keyPassword = "rosepods123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // libxposed 新 API（Vector / LSPosed 1.9+），仅编译期使用，运行时由框架提供
    compileOnly(files("libs/libxposed-api.jar"))

    testImplementation("junit:junit:4.13.2")
}