plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.krstech.dockers"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.krstech.dockers"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = rootProject.file("VERSION").takeIf { it.exists() }?.readText()?.trim() ?: "1.1.0"
        vectorDrawables { useSupportLibrary = true }

        // Docker VM image (built by .github/workflows/vm-image.yml, published as release "vm-<n>").
        val vmVersion = rootProject.file("vm/VERSION").readText().trim()
        buildConfigField("int", "VM_IMAGE_VERSION", vmVersion)
        buildConfigField(
            "String", "VM_IMAGE_BASE_URL",
            "\"https://github.com/kawincodes/KRS-DOCKER-ANDROID/releases/download/vm-$vmVersion\"",
        )
    }

    // Per-ABI APKs plus a universal one. QEMU and the Docker CLI are large native executables,
    // so per-ABI downloads are much smaller.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        // QEMU, its libraries and the Docker CLI ship as lib*.so so Android extracts them to
        // nativeLibraryDir, the only place an app may exec() files from (Android 10+ W^X).
        jniLibs.useLegacyPackaging = true
        // Termux-built libraries are already stripped; don't let AGP touch the executables.
        jniLibs.keepDebugSymbols += setOf("**/libqemu.so", "**/libdocker.so")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.lucide.icons)
    implementation(libs.termux.terminal.view)
    implementation(libs.termux.terminal.emulator)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
