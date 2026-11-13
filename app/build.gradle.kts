plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val converterBenchmark = providers.gradleProperty("converterBenchmark").orNull
val converterOnly = providers.gradleProperty("converterOnly").map(String::toBooleanStrict).getOrElse(false)
val runtimeFixture = providers.gradleProperty("runtimeFixture").map(String::toBooleanStrict).getOrElse(false)
val runtimeRootOverride = providers.gradleProperty("sd15RuntimeRoot").orNull
val sd15RuntimeRoot = rootProject.file(runtimeRootOverride ?: "third_party/to-mnn")
val releaseKeystore = providers.environmentVariable("POCKETMUSE_RELEASE_KEYSTORE").orNull
val releasePassword = providers.environmentVariable("POCKETMUSE_RELEASE_PASSWORD").orNull
require((releaseKeystore == null) == (releasePassword == null)) {
    "Set both POCKETMUSE_RELEASE_KEYSTORE and POCKETMUSE_RELEASE_PASSWORD to sign the release"
}
require(!converterOnly || (runtimeRootOverride == null && !runtimeFixture)) {
    "converterOnly cannot be combined with sd15RuntimeRoot or runtimeFixture"
}
require(!runtimeFixture || converterBenchmark == null) {
    "runtimeFixture and converterBenchmark use separate test packages"
}
require(converterBenchmark == null || converterBenchmark in setOf("Debug", "Release", "RelWithDebInfo")) {
    "converterBenchmark must be Debug, Release or RelWithDebInfo"
}

android {
    namespace = "com.example.pocketmuseprototype"
    ndkVersion = libs.versions.ndk.get()
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.pocketmuseprototype"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                targets += "pocketmuse_converter"
                arguments += "-DPOCKETMUSE_BUILD_RUNTIME=${if (converterOnly) "OFF" else "ON"}"
                if (!converterOnly) {
                    arguments += "-DSD15_RUNTIME_ROOT=${sd15RuntimeRoot.invariantSeparatorsPath}"
                    targets += "pocketmuse_runtime"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    signingConfigs {
        if (releaseKeystore != null && releasePassword != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releasePassword
                keyAlias = "pocketmuse"
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        if (runtimeFixture) {
            debug { applicationIdSuffix = ".runtimefixture" }
        }
        if (converterBenchmark != null) {
            debug {
                applicationIdSuffix = ".converterbenchmark"
                externalNativeBuild {
                    cmake {
                        arguments += "-DCMAKE_BUILD_TYPE=$converterBenchmark"
                    }
                }
            }
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.appauth)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
