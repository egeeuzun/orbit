import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.orbit.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.orbit.browser"
        minSdk = 21
        targetSdk = 36
        versionCode = 2000
        versionName = "2.0.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подпись — реальным ключом из keystore.properties (вне git),
            // а не debug. Если файла нет — собранный APK подписывается
            // вручную через apksigner (см. docs/pipeline/brief.md).
            val ks = rootProject.file("keystore.properties")
            if (ks.exists()) {
                val props = Properties().apply {
                    ks.inputStream().use { load(it) }
                }
                signingConfigs.create("release") {
                    storeFile = file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // 1 GB RAM hedefi: tek ABI'lık küçük APK'lar üretilir.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
    }
}

// ---------------------------------------------------------------------------
// Yerel motor (Brave adblock-rust) derlemesi.
//
// Üretilen .so dosyaları depoda tutulduğu için Rust kurulu olmayan bir
// makinede de derleme yapılabilir; cargo-ndk varsa kütüphane tazelenir.
// ---------------------------------------------------------------------------
val rustDir = rootProject.file("rust/adblock-jni")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val ndkAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

// Yapılandırma anında çözülür: görev içinde betik nesnesine kapanış (closure)
// tutulmaz, böylece yapılandırma önbelleği bozulmaz.
val cargoNdkAvailable = providers.exec {
    commandLine("sh", "-c", "command -v cargo-ndk >/dev/null 2>&1 && echo yes || echo no")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim() == "yes"

val canBuildNative = cargoNdkAvailable && rustDir.isDirectory

val buildNativeAdblock = tasks.register<Exec>("buildNativeAdblock") {
    group = "build"
    description = "adblock-jni kütüphanesini Android ABI'leri için derler"
    workingDir = rustDir
    commandLine(
        buildList {
            add("cargo")
            add("ndk")
            ndkAbis.forEach { add("-t"); add(it) }
            add("-P"); add("21")
            add("-o"); add(jniLibsDir.asFile.absolutePath)
            add("build"); add("--release")
        }
    )
    enabled = canBuildNative
    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    outputs.dir(jniLibsDir)
}

tasks.named("preBuild") { dependsOn(buildNativeAdblock) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.material)
}
