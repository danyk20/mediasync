plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

// Test-only: generates the self-signed identity certificates Android Keystore makes on devices.
dependencies {
    api(projects.transport.lan)
    implementation(libs.bouncycastle.bcpkix)
}
