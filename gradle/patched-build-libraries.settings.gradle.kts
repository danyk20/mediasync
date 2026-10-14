// Build tools bring in some libraries in versions with known vulnerabilities: the Android Gradle plugin and its lint
// (Bouncy Castle, jose4j, JDOM, Commons Lang, HttpClient), Kover (FreeMarker) and Licensee (Plexus Utils). None of
// them ship in the app; they only run during builds. Until the tools bring patched versions themselves, every
// configuration of every project, plugin classpaths included, raises them to at least these versions.
//
// Remove a line once the tools bring that version or a newer one. Applied by settings.gradle.kts and by
// build-logic/settings.gradle.kts.
gradle.lifecycle.beforeProject {
    val patched = mapOf(
        // All Bouncy Castle modules move together.
        "org.bouncycastle:bcprov-jdk18on" to "1.86",
        "org.bouncycastle:bcpkix-jdk18on" to "1.86",
        "org.bouncycastle:bcutil-jdk18on" to "1.86",
        "org.freemarker:freemarker" to "2.3.35",
        "org.bitbucket.b_c:jose4j" to "0.9.6",
        "org.jdom:jdom2" to "2.0.6.1",
        "org.apache.commons:commons-lang3" to "3.18.0",
        "org.codehaus.plexus:plexus-utils" to "3.6.1",
        "org.apache.httpcomponents:httpclient" to "4.5.14",
    )
    val raise = Action<DependencyResolveDetails> {
        val minimum = patched["${requested.group}:${requested.name}"] ?: return@Action
        val version = requested.version ?: return@Action
        val have = version.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val need = minimum.split('.').map { it.toInt() }
        val difference = (0 until maxOf(have.size, need.size))
            .map { have.getOrElse(it) { 0 } - need.getOrElse(it) { 0 } }
            .firstOrNull { it != 0 } ?: 0
        if (difference < 0) {
            useVersion(minimum)
            because("A patched version (gradle/patched-build-libraries.settings.gradle.kts)")
        }
    }
    configurations.configureEach { resolutionStrategy.eachDependency(raise) }
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency(raise) }
}
