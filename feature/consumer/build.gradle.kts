import java.net.URI

plugins {
    alias(libs.plugins.mediasync.android.feature)
}

// Signing in with Cloudflare (infra/rendezvous/README.md, "Sign in with Cloudflare"): MediaSync's OAuth client, from a
// Gradle property or the environment. Without both settings, internet sync is set up with a pasted token only.
fun buildSetting(name: String): String =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).getOrElse("").trim()

val cloudflareClientId = buildSetting("CLOUDFLARE_OAUTH_CLIENT_ID")
val cloudflareRedirect = buildSetting("CLOUDFLARE_OAUTH_REDIRECT_URI")
val cloudflareSignIn = cloudflareClientId.isNotEmpty() && cloudflareRedirect.isNotEmpty()
val redirect: URI? = if (cloudflareSignIn) URI(cloudflareRedirect) else null
if (redirect != null) {
    require(cloudflareClientId.all { it.isLetterOrDigit() || it in "-_.~" }) {
        "CLOUDFLARE_OAUTH_CLIENT_ID isn't an OAuth client ID: $cloudflareClientId"
    }
    // The browser comes back through an App Link: https, on the client's verified domain, without a query.
    require(redirect.scheme == "https" && !redirect.host.isNullOrEmpty() && redirect.rawQuery == null) {
        "CLOUDFLARE_OAUTH_REDIRECT_URI must be an https address without a query: $cloudflareRedirect"
    }
}

android {
    namespace = "io.github.danyk20.mediasync.feature.consumer"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField(
            "String",
            "CLOUDFLARE_OAUTH_CLIENT_ID",
            "\"${if (cloudflareSignIn) cloudflareClientId else ""}\"",
        )
        buildConfigField("String", "CLOUDFLARE_OAUTH_REDIRECT_URI", "\"${redirect ?: ""}\"")
        manifestPlaceholders["cloudflareRedirectHost"] = redirect?.host.orEmpty()
        manifestPlaceholders["cloudflareRedirectPath"] = redirect?.path.orEmpty().ifEmpty { "/" }
    }
}

// The redirect address's App Link, only in a build that can sign in.
if (cloudflareSignIn) {
    androidComponents {
        onVariants { variant ->
            variant.sources.manifests.addStaticManifestFile("src/cloudflareSignIn/AndroidManifest.xml")
        }
    }
}

dependencies {
    implementation(projects.connector.android)
    implementation(projects.core.datastore)
    implementation(projects.core.media)
    implementation(projects.sync.android)
    implementation(projects.transport.rendezvous)
    implementation(libs.androidx.browser)

    testImplementation(testFixtures(projects.connector.api))
}
