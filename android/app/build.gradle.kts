plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: keystore/release.p12 is password-protected; the password comes from
// the SIGNING_PASSWORD environment variable (GitHub secret). Without it the APK is debug-signed.
val signingPassword: String = System.getenv("SIGNING_PASSWORD").orEmpty()
val buildNumber: Int = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "io.github.vapes.bpdiary"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.vapes.bpdiary"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    signingConfigs {
        create("release") {
            if (signingPassword.isNotEmpty()) {
                storeFile = rootProject.file("keystore/release.p12")
                storeType = "pkcs12"
                storePassword = signingPassword
                keyAlias = "bpdiary"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingPassword.isNotEmpty()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // The web app (index.html, i18n.js, icon) from the repository root is the UI.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("web"))
}

val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile) {
        include("index.html", "i18n.js", "icon-192.png")
    }
    into(layout.buildDirectory.dir("web"))
}
tasks.named("preBuild") { dependsOn(copyWeb) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
