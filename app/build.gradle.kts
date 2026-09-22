plugins { id("com.android.application") }
android {
    namespace = "tf.dodoapps.parkbot"
    compileSdk = 35
    defaultConfig {
        applicationId = "tf.dodoapps.parkbot"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.2.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":core"))
    implementation("com.google.android.material:material:1.13.0")
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }






