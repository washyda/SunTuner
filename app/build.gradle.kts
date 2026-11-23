import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.washy.sm8750.tuner"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "io.washy.sm8750.tuner"
        minSdk = 30
        targetSdk = 36
        versionCode = providers.gradleProperty("releaseVersionCode").orElse("12").get().toInt()
        versionName = providers.gradleProperty("releaseVersionName").orElse("0.4.7beta").get()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))

            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    buildFeatures {
        compose = true
    }
}

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val ndk = providers.environmentVariable("ANDROID_NDK_HOME").orElse(
    provider {
        val sdk = local.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT") ?: error("Set sdk.dir or ANDROID_HOME.")
        "$sdk/ndk/${android.ndkVersion}"
    },
)
val generatedAssets = layout.buildDirectory.dir("generated/tuner-assets")
val kernelPrebuilts = rootProject.file("kernel/prebuilt")
val backendModules = listOf(
    "sm8750/acd-observer.ko",
    "sm8750/pvc-corr-ro.ko",
    "sm8750/qcom-scmi-ro.ko",
    "sm8750/stress-qos.ko",
    "sm8850/android16-5-4k/qcom-scmi-ro.ko",
    "sm8850/android16-5-4k/stress-qos.ko",
    "sm8850/android16-6-4k/qcom-scmi-ro.ko",
    "sm8850/android16-6-4k/stress-qos.ko",
)
val generateBackend by tasks.registering(Sync::class) {
    from(kernelPrebuilts) { include(backendModules) }
    into(generatedAssets.map { it.dir("backend/modules") })
    doFirst {
        backendModules.forEach { path ->
            val module = kernelPrebuilts.resolve(path)
            check(module.isFile) { "Missing kernel module: $module" }
        }
    }
}
val generatedNativeAssets = layout.buildDirectory.dir("generated/tuner-native-assets")
fun registerNativeBinary(taskName: String, sourcePath: String, assetName: String, extraFlags: List<String> = emptyList(), extraSources: List<String> = emptyList()) =
    tasks.register<Exec>(taskName) {
        val sources = (listOf(sourcePath) + extraSources).map(::file)
        val binary = generatedNativeAssets.map { it.file("backend/$assetName") }
        inputs.files(sources)
        inputs.property("ndkPath", ndk)
        outputs.file(binary)
        doFirst {
            val toolchains = file("${ndk.get()}/toolchains/llvm/prebuilt")
            val host = toolchains.listFiles()?.singleOrNull { it.isDirectory }
                ?: error("Install NDK ${android.ndkVersion} or set ANDROID_NDK_HOME.")
            binary.get().asFile.parentFile.mkdirs()
            val clang = host.resolve("bin/clang" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
            commandLine(
                listOf(
                    clang.absolutePath, "--target=aarch64-linux-android30", "-std=c17",
                    "-O3", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "-Wl,-z,max-page-size=16384",
                ) + extraFlags + sources.map { it.absolutePath } + listOf("-o", binary.get().asFile.absolutePath),
            )
        }
    }
val buildLoadWorker = registerNativeBinary(
    "buildLoadWorker", "src/main/cpp/load.c", "load", listOf("-pthread"),
    listOf("src/main/cpp/openblas/sgemm_kernel_16x4.S"),
)
val buildCdtTool = registerNativeBinary("buildCdtTool", "src/main/cpp/cdt_tool.c", "cdt_tool")
val buildCrashLogFilter = registerNativeBinary("buildCrashLogFilter", "src/main/cpp/crash_log_filter.c", "crash_log_filter")
android.sourceSets["main"].assets.directories.addAll(listOf(generatedAssets.get().asFile.path, generatedNativeAssets.get().asFile.path))
val gameNativeLibs = layout.buildDirectory.dir("generated/game-native-libs")
val buildGameGpuClock by tasks.registering(Exec::class) {
    val source = file("src/main/cpp/game_gpu_clock.c")
    val library = gameNativeLibs.map { it.file("arm64-v8a/libgame_gpu_clock.so") }
    inputs.file(source)
    inputs.property("ndkPath", ndk)
    outputs.file(library)
    doFirst {
        val toolchain = file("${ndk.get()}/toolchains/llvm/prebuilt").listFiles()!!.single { it.isDirectory }
        val clang = toolchain.resolve("bin/clang" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        library.get().asFile.parentFile.mkdirs()
        commandLine(
            clang.absolutePath, "--target=aarch64-linux-android30", "-std=c17", "-O2",
            "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror", "-Wl,-z,max-page-size=16384",
            source.absolutePath, "-lEGL", "-lGLESv3", "-o", library.get().asFile.absolutePath,
        )
    }
}
android.sourceSets["main"].jniLibs.directories.add(gameNativeLibs.get().asFile.path)
tasks.named("preBuild") { dependsOn(generateBackend, buildLoadWorker, buildCdtTool, buildCrashLogFilter, buildGameGpuClock) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
