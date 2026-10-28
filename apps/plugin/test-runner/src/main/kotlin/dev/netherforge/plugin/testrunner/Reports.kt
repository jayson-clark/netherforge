package dev.netherforge.plugin.testrunner

import java.io.StringWriter
import java.util.Locale
import javax.xml.stream.XMLOutputFactory

/** What a run prints for people: each test with its verdict and where it failed, then the totals. */
class TextReport(private val print: (String) -> Unit) {
    private var currentFile: String? = null

    fun event(event: TestEvent) {
        when (event) {
            is RunStarted -> {}
            is FileLoaded -> {}
            is TestResult -> result(event)
            is RunFinished -> finished(event)
            is RunFailed -> {
                print("Can't run the project's tests: ${event.message}")
                for (problem in event.problems.drop(1)) print("  $problem")
            }
        }
    }

    private fun result(result: TestResult) {
        if (result.file != currentFile) {
            currentFile = result.file
            print(result.file)
        }
        val label = when (result.status) {
            Status.PASSED -> "PASS"
            Status.FAILED -> "FAIL"
            Status.ERRORED -> "ERROR"
        }
        print("  $label  ${result.name} (${result.durationMillis} ms)")
        if (result.status == Status.PASSED) return
        val where = result.source?.let { "${it.file}${it.line?.let { line -> ":$line" }.orEmpty()}: " }.orEmpty()
        val message = result.message.orEmpty()
        // Lua's own message usually starts with the location already.
        print("        ${if (message.startsWith(where)) "" else where}$message")
        for (log in result.logs) print("        log: $log")
    }

    private fun finished(end: RunFinished) {
        val parts =
            listOf("${end.passed} passed", "${end.failed} failed") + if (end.errored > 0) listOf("${end.errored} errored") else emptyList()
        print("")
        print("${parts.joinToString(", ")} (${String.format(Locale.ROOT, "%.1f", end.durationMillis / 1000.0)} s)")
    }
}

/**
 * JUnit XML (the format CI systems read: GitHub's test reporters, Jenkins, GitLab): one `testsuite`
 * per test file, a `testcase` per test, a `failure` for [Status.FAILED] and an `error` for
 * [Status.ERRORED].
 */
object JUnitReport {
    fun write(results: List<TestResult>, durationMillis: Long): String {
        val out = StringWriter()
        val xml = XMLOutputFactory.newFactory().createXMLStreamWriter(out)
        fun seconds(millis: Long) = String.format(Locale.ROOT, "%.3f", millis / 1000.0)
        xml.writeStartDocument("UTF-8", "1.0")
        xml.writeCharacters("\n")
        xml.writeStartElement("testsuites")
        xml.writeAttribute("tests", results.size.toString())
        xml.writeAttribute("failures", results.count { it.status == Status.FAILED }.toString())
        xml.writeAttribute("errors", results.count { it.status == Status.ERRORED }.toString())
        xml.writeAttribute("time", seconds(durationMillis))
        for ((file, tests) in results.groupBy { it.file }) {
            xml.writeCharacters("\n  ")
            xml.writeStartElement("testsuite")
            xml.writeAttribute("name", file)
            xml.writeAttribute("tests", tests.size.toString())
            xml.writeAttribute("failures", tests.count { it.status == Status.FAILED }.toString())
            xml.writeAttribute("errors", tests.count { it.status == Status.ERRORED }.toString())
            xml.writeAttribute("time", seconds(tests.sumOf { it.durationMillis }))
            for (test in tests) {
                xml.writeCharacters("\n    ")
                xml.writeStartElement("testcase")
                xml.writeAttribute("classname", file)
                xml.writeAttribute("name", test.name)
                xml.writeAttribute("time", seconds(test.durationMillis))
                if (test.status != Status.PASSED) {
                    xml.writeCharacters("\n      ")
                    xml.writeStartElement(if (test.status == Status.FAILED) "failure" else "error")
                    xml.writeAttribute("message", test.message.orEmpty())
                    xml.writeCharacters(
                        listOfNotNull(
                            test.source?.let {
                                "${it.file}${it.line?.let { line -> ":$line" }.orEmpty()}"
                            },
                            test.traceback
                        ).joinToString("\n")
                    )
                    xml.writeEndElement()
                }
                if (test.logs.isNotEmpty()) {
                    xml.writeCharacters("\n      ")
                    xml.writeStartElement("system-out")
                    xml.writeCharacters(test.logs.joinToString("\n"))
                    xml.writeEndElement()
                }
                xml.writeCharacters(if (test.status != Status.PASSED || test.logs.isNotEmpty()) "\n    " else "")
                xml.writeEndElement()
            }
            xml.writeCharacters("\n  ")
            xml.writeEndElement()
        }
        xml.writeCharacters("\n")
        xml.writeEndElement()
        xml.writeEndDocument()
        xml.close()
        return out.toString() + "\n"
    }
}
