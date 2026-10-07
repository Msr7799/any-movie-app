import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The Google Services plugin is activated automatically once the real
// app/google-services.json from Firebase Console is added. Keeping it
// conditional lets the project build before credentials are provisioned.
if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use(::load)
}
// Optional URL of the deployed Next.js Movies_Player website.
// The Vercel search server is not the Next.js website; keep these separate.
val siteBaseUrl = providers.gradleProperty("MOVIES_PLAYER_SITE_URL").orElse("").get().trimEnd('/')
val apiBaseUrl = providers.gradleProperty("ANY_MOVIE_API_BASE_URL")
    .orElse("https://movies-search-server.vercel.app")
    .get()

android {
    namespace = "com.forgepulse.anymovie"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.forgepulse.anymovie"
        minSdk = 24
        targetSdk = 37
        versionCode = 24
        versionName = "2.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SITE_BASE_URL", "\"$siteBaseUrl\"")
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.trimEnd('/')}\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(keystoreProperties.getProperty("storeFile")) { "Missing storeFile" })
                storePassword = requireNotNull(keystoreProperties.getProperty("storePassword")) { "Missing storePassword" }
                keyAlias = requireNotNull(keystoreProperties.getProperty("keyAlias")) { "Missing keyAlias" }
                keyPassword = requireNotNull(keystoreProperties.getProperty("keyPassword")) { "Missing keyPassword" }
            }
        }
    }

    buildTypes {
        release {
            optimization.enable = true
            signingConfig = signingConfigs.findByName("release")
        }
        create("qa") {
            initWith(getByName("release"))
            // Keep the same applicationId as the Firebase Android app so
            // google-services.json, Anonymous Auth and Google Sign-In work in QA builds too.
            // QA still has a version-name suffix and remains debuggable.
            versionNameSuffix = "-qa"
            isDebuggable = true
            optimization.enable = true
            signingConfig = signingConfigs.findByName("release")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.webkit)
    implementation(libs.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // Firebase platform: Authentication (anonymous + Google), Realtime Database, Analytics.
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-analytics")

    // Modern Sign in with Google via Android Credential Manager.
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
