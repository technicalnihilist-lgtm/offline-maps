plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.offlinemaps.core.data"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

/**
 * OPTIMIZATION.md J-09. Room exports `AppDatabase`'s schema into `schemas/` from *both* KSP variant
 * tasks, and `org.gradle.parallel=true` lets them run at the same time: one reads `1.json` while the
 * other rewrites it and the build dies with `IllegalStateException: Empty schema file` (2026-11-03,
 * again on 2026-11-12 on the I-05 commit - both green on a rerun, which is what a race looks like).
 *
 * The directory stays shared on purpose - the schema is one file per database version, not per
 * variant, and splitting it would leave the release variant's export somewhere nothing reads - so
 * the two tasks are ordered instead of being allowed to overlap. `mustRunAfter` is honoured even
 * under parallel execution, and it costs nothing when only one variant is being built.
 */
tasks.matching { it.name == "kspReleaseKotlin" }.configureEach {
    mustRunAfter(tasks.matching { it.name == "kspDebugKotlin" })
}
dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
