// Plugins go on the buildscript classpath so the Android Gradle Plugin is only fetched where the
// Android SDK exists (CI, Android Studio). Machines without it can still build and test :core.
buildscript {
    val hasAndroidSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").exists()
    repositories {
        if (hasAndroidSdk) google()
        mavenCentral()
    }
    dependencies {
        if (hasAndroidSdk) classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
    }
}
