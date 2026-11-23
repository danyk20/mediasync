plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

// In-memory connectors and ledger, shared with the contract-test kit and the connector modules' tests. Test code: never ships in the app.
dependencies {
    api(projects.connector.api)
}
