plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.compose)
}

// GroveMedia: music & video (the original Grove Music app), included in the Grove Book app
android {
    namespace = "com.grove.music"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation(libs.androidx.activity.compose)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
