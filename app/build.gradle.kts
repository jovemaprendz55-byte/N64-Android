plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jsr.n64android"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    // The CI places the separately built, pinned GLideN64 Android plugin here.
    // It is packaged as a native library but is not linked into the JNI bridge.
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(rootProject.file("build/native-libs"))
        }
    }

    defaultConfig {
        applicationId = "com.jsr.n64android"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // O primeiro aparelho-alvo usa ARM64; evitar empacotar ABIs que não serão testadas nesta etapa.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
