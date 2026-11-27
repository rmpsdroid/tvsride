import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 moved Compose compilation into a compiler plugin; the old
    // `kotlinCompilerExtensionVersion` string is rejected outright.
    id("org.jetbrains.kotlin.plugin.compose")
}

val keyProps = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keyProps.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val ciShortSha = System.getenv("GITHUB_SHA")?.take(7)
val baseVersionName = "0.2.9"
val computedVersionCode = ciRunNumber ?: 12
val computedVersionName = when {
    ciRunNumber != null && !ciShortSha.isNullOrBlank() -> "$baseVersionName-r$ciRunNumber.$ciShortSha"
    ciRunNumber != null -> "$baseVersionName-r$ciRunNumber"
    else -> "$baseVersionName-local"
}

android {
    namespace = "dev.tvsopen"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.tvsopen"
        minSdk = 26
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = computedVersionName
        buildConfigField("String", "BUILD_VERSION_LABEL", "\"$computedVersionName\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
