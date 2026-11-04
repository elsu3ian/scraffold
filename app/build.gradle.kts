import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * The permanent release key, so every release installs over the last. GitHub's builder passes it in
 * the environment; on the developer's machine it comes from a properties file kept outside this
 * repository. Without either, release builds use the debug key, so the code still builds anywhere.
 */
val releaseKey: Map<String, String>? = run {
    val env = System.getenv()
    env["SCRAFFOLD_KEYSTORE"]?.takeIf { it.isNotEmpty() }?.let { store ->
        return@run mapOf(
            "storeFile" to store,
            "storePassword" to env.getValue("SCRAFFOLD_KEYSTORE_PASSWORD"),
            "keyAlias" to env.getValue("SCRAFFOLD_KEY_ALIAS"),
            "keyPassword" to env.getValue("SCRAFFOLD_KEY_PASSWORD")
        )
    }
    val file = env["SCRAFFOLD_SIGNING_PROPERTIES"]?.let(::File)
        ?: rootProject.file("../scraffold-signing/keystore.properties")
    if (!file.exists()) return@run null
    val props = Properties().apply { file.inputStream().use { load(it) } }
    props.stringPropertyNames().associateWith { props.getProperty(it) }
}

/**
 * The version code Android compares, worked out from the version name, so a release only sets the name.
 * The app's update check works it out the same way (Updates.versionCodeOf), so the two must match.
 * x.y.z gives (x * 10000 + y * 100 + z) * 10, and a final release 9 more than its betas, so each new
 * version always installs over the one before. 1.0.3-beta is 100030, 1.1.0-beta 101000, 1.1.0 101009.
 */
fun versionCodeOf(name: String): Int {
    val core = name.substringBefore('-')
    val parts = core.split('.').map { part ->
        part.toIntOrNull() ?: error("Version $name: \"$part\" isn't a number")
    }
    require(parts.size == 3) { "Version $name needs three numbers, like 1.1.0" }
    val (major, minor, patch) = parts
    require(minor < 100 && patch < 100) { "Version $name: the second and third numbers must be below 100" }
    val final = if (name.contains('-')) 0 else 9
    return (major * 10000 + minor * 100 + patch) * 10 + final
}

android {
    namespace = "net.mangadot.scaffold"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.mangadot.scaffold"
        minSdk = 26
        targetSdk = 35
        // The one line to change for a release
        versionName = "1.1.0-beta"
        versionCode = versionCodeOf(versionName!!)
        // Scraffold 1.0.3-beta and the builds before it read a written-out number here to tell that a
        // newer version is out, and anything above their 4 does: versionCode = 5

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        releaseKey?.let { key ->
            create("release") {
                storeFile = file(key.getValue("storeFile"))
                storePassword = key.getValue("storePassword")
                keyAlias = key.getValue("keyAlias")
                keyPassword = key.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.swiperefreshlayout)

    // Room SQLite
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Lifecycle & Coroutines
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Network & JSON
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Instrumented tests
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
