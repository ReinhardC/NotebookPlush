import java.time.Instant
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

// Local and CI releases use seconds since 2020-01-01 UTC.
// The epoch leaves room below Android's 2.1 billion limit
// until July 2086. Build machines must have synchronized clocks; no network counter is needed.
abstract class UtcBuildTime : ValueSource<Long, ValueSourceParameters.None> {
    override fun obtain(): Long = Instant.now().epochSecond
}

// A ValueSource is rechecked even when Gradle reuses its configuration cache. Plain Instant.now()
// in configuration would freeze the version until an unrelated build input changed.
val buildSecond = providers.of(UtcBuildTime::class) {}.get()
val version = buildSecond - 1_577_836_800L
require(version in 1..2_100_000_000L) { "Build clock is outside the supported 2020-2086 version range." }
require(!providers.gradleProperty("notebookplushVersionCode").isPresent) {
    "Remove notebookplushVersionCode: local and CI builds now use the shared UTC build clock."
}
extra["sharedVersionCode"] = version.toInt()

// Used by the lightweight integration test as well as by maintainers inspecting a build.
tasks.register("printBuildVersion") {
    val code = version.toInt()
    doLast { println("NOTEBOOKPLUSH_VERSION_CODE=$code") }
}
