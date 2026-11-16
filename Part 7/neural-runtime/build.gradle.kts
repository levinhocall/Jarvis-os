plugins { id("com.android.library") }

android {
    namespace = "com.jarvis.assistant.neural"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Java Interpreter boundary: newer optional Kotlin APIs are implementation details.
    // Never export these SDK types into the application's older Kotlin compiler classpath.
    implementation("com.google.ai.edge.litert:litert:2.3.2")
}
