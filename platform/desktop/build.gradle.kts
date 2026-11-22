plugins {
    alias(libs.plugins.mediasync.jvm.library)
    alias(libs.plugins.licensee)
}

// The desktop app's own platform code (docs/PLAN.md §9, M8): plain JVM, no Android and no Metro. The desktop app
// registers these classes in its own graph.
dependencies {
    api(projects.transport.lan)

    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.java.keyring)

    testImplementation(testFixtures(projects.transport.lan))
}

// Every license on this module's runtime classpath must be explicitly allowed (the app's check does not see this module).
// The desktop app (M8.3) needs the same list; keep the two in step.
licensee {
    allow("Apache-2.0") // also one of JNA's two licenses (the other is LGPL-2.1, not allowed)
    allow("MIT") // secret-service, slf4j
    allow("BSD-2-Clause") // jkeychain (macOS Keychain)
    allowUrl("https://www.bouncycastle.org/licence.html") {
        because("The Bouncy Castle Licence is the MIT license")
    }
    allowUrl("https://github.com/hypfvieh/dbus-java/blob/master/LICENSE") {
        because("dbus-java (Linux Secret Service) is MIT")
    }
    // LICENSE file is the BSD 3-Clause text (checked in github.com/javakeyring/java-keyring); its pom only names it "BSD-3".
    allowDependency("com.github.javakeyring", "java-keyring", "1.0.4") {
        because("Its pom names the license only 'BSD-3'; the project's LICENSE is BSD-3-Clause")
    }
}

// The identity decides who the phones trust: held to the stricter bar.
kover {
    reports {
        variant("ci") {
            verify {
                rule("platform:desktop line coverage") {
                    minBound(85)
                }
            }
        }
    }
}

// Lint is Android's, and this module never runs there: "TrulyRandom" is about SecureRandom on Android 4.3 and older.
lint {
    disable += "TrulyRandom"
}
