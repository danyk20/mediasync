import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    alias(libs.plugins.mediasync.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.transport.api)
            api(libs.okio)
        }
        iosMain.dependencies {
            // The identity log (which store was chosen, corruption) goes to the diagnostic log; the provider's lock.
            implementation(projects.core.diagnostics)
            implementation(projects.core.util)
        }
        commonTest.dependencies {
            implementation(projects.sync.protocol)
        }
        iosTest.dependencies {
            // The closing test closes a connection from another thread while a read is blocked.
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

mediasync { iosTargets() }

// The Network.framework receive shim (src/nativeInterop/cinterop/mediasync_nw.h): a C wrapper that keeps the content
// context Network.framework passes on cancel away from Kotlin/Native, which aborts on it.
kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.named("main") {
            cinterops.create("mediasyncNw") {
                includeDirs(project.file("src/nativeInterop/cinterop"))
            }
        }
    }
}

// The JVM InteropServer (:transport:lan-testing) that the iOS interop test talks to. Its runtime classpath is resolved
// from this configuration, so the server runs from the same build as the tests.
val interopServer: Configuration = configurations.create("interopServer") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, Usage.JAVA_RUNTIME))
}
dependencies {
    add(interopServer.name, projects.transport.lanTesting)
}

// Gradle starts the server right before the simulator tests and stops it after them, however they end. The simulator
// reads the directory from SIMCTL_CHILD_INTEROP_DIR (it reaches the test as INTEROP_DIR); the port and pin are in
// <dir>/server.properties. Each test run starts a fresh server, so the tests never see a stale one.
//
// Three layers keep the server from leaking: the build service kills the process when the build ends (however it
// ends), the stop task kills it after the test, and the server exits on its own after 30 minutes.
val interopDir = layout.buildDirectory.dir("interop")
val interopServerService = gradle.sharedServices.registerIfAbsent("interopServer", InteropServerService::class) {}
val startInteropServer = tasks.register<StartInteropServer>("startInteropServer") {
    group = "verification"
    description = "Starts the JVM InteropServer that the iOS interop test talks to."
    serverClasspath.from(interopServer)
    outputDir.set(interopDir)
    server.set(interopServerService)
    usesService(interopServerService)
    outputs.upToDateWhen { false }
    // The server starts after the test is compiled and linked, so a compile failure never starts one.
    mustRunAfter(
        tasks.withType<KotlinNativeCompile>().matching { it.name.contains("Test") },
        tasks.withType<KotlinNativeLink>().matching { it.name.contains("Test") },
    )
}
val stopInteropServer = tasks.register<StopInteropServer>("stopInteropServer") {
    group = "verification"
    description = "Stops the InteropServer that startInteropServer started."
    outputDir.set(interopDir)
    outputs.upToDateWhen { false }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    dependsOn(startInteropServer)
    finalizedBy(stopInteropServer)
    usesService(interopServerService)
    // Always run: a cached "passed" would hide that the interop test did not talk to a server this time.
    outputs.upToDateWhen { false }
    environment("SIMCTL_CHILD_INTEROP_DIR", interopDir.get().asFile.absolutePath)
}

/**
 * The live process with [pid], but only when its command line names the InteropServer. A pid that the operating
 * system has reused for another program is not returned, so it is never destroyed.
 */
object InteropServerProcesses {
    fun of(pid: Long): ProcessHandle? = ProcessHandle.of(pid).orElse(null)?.takeIf { handle ->
        handle.info().commandLine().orElse("").contains("InteropServer")
    }
}

/**
 * Owns the InteropServer process that [StartInteropServer] starts. Gradle closes a build service when the build ends,
 * however it ends, and close() kills the process: a failed compile or a failed test leaves no server behind.
 */
abstract class InteropServerService :
    BuildService<BuildServiceParameters.None>,
    AutoCloseable {
    private var process: Process? = null

    @Synchronized
    fun adopt(started: Process) {
        process = started
    }

    @Synchronized
    override fun close() {
        process?.takeIf { it.isAlive }?.destroyForcibly()
        process = null
    }
}

/** Starts the InteropServer in its own JVM and waits (at most 30 s) until it has written its port and pin. */
abstract class StartInteropServer : DefaultTask() {
    @get:Classpath
    abstract val serverClasspath: ConfigurableFileCollection

    @get:Internal
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val server: Property<InteropServerService>

    @TaskAction
    fun start() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        // A server left by an earlier run is killed, but only if its pid still names a live InteropServer.
        val stalePid = dir.resolve(PID_FILE)
        if (stalePid.exists()) {
            stalePid.readText().trim().toLongOrNull()?.let { InteropServerProcesses.of(it) }?.destroyForcibly()
        }
        for (stale in listOf(PROPERTIES_FILE, PID_FILE, RESULT_FILE)) dir.resolve(stale).delete()
        val log = dir.resolve(LOG_FILE)
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(java, "-cp", serverClasspath.asPath, MAIN_CLASS, dir.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        server.get().adopt(process)
        dir.resolve(PID_FILE).writeText(process.pid().toString())
        val properties = dir.resolve(PROPERTIES_FILE)
        val deadline = System.nanoTime() + READY_TIMEOUT_NANOS
        while (!properties.exists()) {
            if (!process.isAlive) {
                throw GradleException("The InteropServer exited with ${process.exitValue()}, see $log")
            }
            if (System.nanoTime() > deadline) {
                process.destroyForcibly()
                throw GradleException("The InteropServer was not ready after 30 s, see $log")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val MAIN_CLASS = "io.github.danyk20.mediasync.transport.lan.InteropServerKt"
        const val PROPERTIES_FILE = "server.properties"
        const val PID_FILE = "server.pid"
        const val LOG_FILE = "server.log"
        const val RESULT_FILE = "interop-result.txt"
        const val READY_TIMEOUT_NANOS = 30_000_000_000L
        const val POLL_MILLIS = 100L
    }
}

/** Stops the server that [StartInteropServer] recorded. Does nothing when no server is running. */
abstract class StopInteropServer : DefaultTask() {
    @get:Internal
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stop() {
        val pidFile = outputDir.get().asFile.resolve("server.pid")
        if (!pidFile.exists()) return
        pidFile.readText().trim().toLongOrNull()?.let { InteropServerProcesses.of(it) }?.destroyForcibly()
        pidFile.delete()
    }
}
