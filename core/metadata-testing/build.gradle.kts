plugins {
    alias(libs.plugins.mediasync.jvm.library)
}

// The golden media set (core/metadata/golden/make_golden.py writes it here) as classpath resources under /golden,
// shared with the sync engine, desktop, media-store and connector tests. No code, no dependencies. Test data: never ships in the app.
