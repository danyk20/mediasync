plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

// Test-only: generates the self-signed identity certificates Android Keystore makes on devices.
dependencies {
    api(projects.transport.lan)
    implementation(libs.bouncycastle.bcpkix)
    // The interop server (InteropServer.kt) speaks the sync protocol's Hello frame.
    implementation(projects.sync.protocol)
    // The echo peer hashes the bytes it receives (HashingSink).
    implementation(libs.okio)
}

// Test-only JVM code: the JDK's default PRNG is the one every JVM test uses, so the Android 4.3 PRNG warning does not
// apply. Lint on this JVM module reports it at the key generator, where a Kotlin @Suppress does not reach.
lint {
    disable += "TrulyRandom"
}
