plugins { id("com.android.application") }

val releaseSigningVariables = listOf(
    "PARKBOT_RELEASE_KEYSTORE_PATH",
    "PARKBOT_RELEASE_STORE_PASSWORD",
    "PARKBOT_RELEASE_KEY_ALIAS",
    "PARKBOT_RELEASE_KEY_PASSWORD"
)
val releaseSigning = releaseSigningVariables.associateWith { providers.environmentVariable(it).orNull }

android {
    namespace = "tf.dodoapps.parkbot"
    compileSdk = 35
    defaultConfig {
        applicationId = "tf.dodoapps.parkbot"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.2.3"
    }
    signingConfigs {
        create("release") {
            releaseSigning["PARKBOT_RELEASE_KEYSTORE_PATH"]?.takeIf { it.isNotBlank() }?.let {
                storeFile = file(it)
            }
            storePassword = releaseSigning["PARKBOT_RELEASE_STORE_PASSWORD"]
            keyAlias = releaseSigning["PARKBOT_RELEASE_KEY_ALIAS"]
            keyPassword = releaseSigning["PARKBOT_RELEASE_KEY_PASSWORD"]
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
}

// Debug builds need no release secrets; release builds never fall back to a debug key.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        val missing = releaseSigningVariables.filter { releaseSigning[it].isNullOrBlank() }
        check(missing.isEmpty()) { "Missing release signing variables: " + missing.joinToString() }
        check(file(releaseSigning.getValue("PARKBOT_RELEASE_KEYSTORE_PATH")!!).isFile) {
            "Release keystore file does not exist."
        }
    }
}
dependencies {
    implementation(project(":core"))
    implementation("com.google.android.material:material:1.13.0")
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

