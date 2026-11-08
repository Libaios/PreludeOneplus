plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chaque build GitHub a un numéro qui augmente : ça permet de mettre à jour
// l'appli par-dessus l'ancienne sans la désinstaller.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "fr.preludedash"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.preludeoneplus"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // Filament embarque du code natif : on ne garde que les téléphones 64 bits
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Clé de signature fixe : toutes les versions sont signées pareil,
    // donc les mises à jour s'installent par-dessus sans conflit.
    signingConfigs {
        create("perso") {
            storeFile = file("keystore/preludedash.jks")
            storePassword = "preludedash"
            keyAlias = "preludedash"
            keyPassword = "preludedash"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("perso")
        }
        debug {
            signingConfig = signingConfigs.getByName("perso")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Le modèle 3D et l'éclairage sont lus tels quels
    androidResources { noCompress += listOf("glb", "ktx", "filamat") }

    // Code commun avec l'appli Prelude Carte
    sourceSets["main"].java.srcDir("../shared/java")

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    // Rendu 3D réaliste (Google Filament) et chargement du modèle glTF.
    // 1.75.1 : dernière version compilée pour Android 35 (les suivantes exigent le SDK 37).
    implementation("com.google.android.filament:filament-android:1.75.1")
    implementation("com.google.android.filament:gltfio-android:1.75.1")
    implementation("com.google.android.filament:filament-utils-android:1.75.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-common:2.8.7")
    testImplementation("junit:junit:4.13.2")
}
