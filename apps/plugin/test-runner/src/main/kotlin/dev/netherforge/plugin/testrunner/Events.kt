package dev.netherforge.plugin.testrunner

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a test ended. */
@Serializable
enum class Status {
    @SerialName("passed")
    PASSED,

    /** The test itself raised an error: a failed `assert`, an `error` call, a mistake calling the API. */
    @SerialName("failed")
    FAILED,

    /** The test body was fine, but something else went wrong: a script of the project errored while it ran, the project didn't start, the test file didn't load. */
    @SerialName("errored")
    ERRORED
}

/** A place in the project: `tests/greeter_test.lua`, line 12. */
@Serializable
data class Place(val file: String, val line: Int? = null)

/**
 * What a run reports, one per line as JSON (`netherforge test --json`, which the editor reads): a
 * [RunStarted], a [FileLoaded] per test file and a [TestResult] per test as it finishes, then a
 * [RunFinished]. A run that can't start at all (the project is refused) is a [RunFailed] alone.
 */
@Serializable
sealed interface TestEvent

@Serializable
@SerialName("start")
data class RunStarted(val project: String, val version: String) : TestEvent

/** A test file's own code ran: the tests it declared, or why it didn't get to declare them. */
@Serializable
@SerialName("file")
data class FileLoaded(val file: String, val tests: List<String>, val message: String? = null, val source: Place? = null) : TestEvent

@Serializable
@SerialName("result")
data class TestResult(
    val file: String,
    val name: String,
    val status: Status,
    val durationMillis: Long,
    /** What went wrong, for a test that didn't pass. */
    val message: String? = null,
    /** Where, in the project (the test's own line, or the script's that errored), when Lua could say. */
    val source: Place? = null,
    val traceback: String? = null,
    /** What scripts logged while the test ran (`log(...)`), with file and line. */
    val logs: List<String> = emptyList()
) : TestEvent

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("end")
data class RunFinished(val passed: Int, val failed: Int, val errored: Int, val durationMillis: Long) : TestEvent {
    /** Whether every test passed (a run that ran none counts). */
    val ok: Boolean get() = failed == 0 && errored == 0
}

@Serializable
@SerialName("error")
data class RunFailed(val message: String, val problems: List<String> = emptyList()) : TestEvent

/** The one JSON form of [TestEvent]s: `{"event":"result",...}`, a key left out when it is null or empty. */
val EventJson = Json {
    classDiscriminator = "event"
    explicitNulls = false
    encodeDefaults = false
}
