plugins {
    alias(libs.plugins.mediasync.jvm.library)
    `java-test-fixtures`
}

dependencies {
    api(projects.transport.api)

    // Test-only: generates the self-signed identity certificates Android Keystore makes on devices.
    testFixturesImplementation(libs.bouncycastle.bcpkix)
    testImplementation(testFixtures(projects.sync.engine))
}

// Authentication and encryption code: held to the same stricter bar as the sync modules.
kover {
    reports {
        variant("ci") {
            verify {
                rule("transport:lan line coverage") {
                    minBound(85)
                }
            }
        }
    }
}

// Throughput benchmark (docs/PLAN.md §7): not part of the tests; CI runs it in its own job and keeps the trend.
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("benchmark") } }

val benchmark by tasks.registering(Test::class) {
    description = "Measures sync throughput; writes build/benchmark/results.json."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("benchmark") }
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
    systemProperty("benchmark.output", layout.buildDirectory.file("benchmark/results.json").get().asFile.path)
    testLogging.showStandardStreams = true
}

kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("benchmark")
        }
    }
}
