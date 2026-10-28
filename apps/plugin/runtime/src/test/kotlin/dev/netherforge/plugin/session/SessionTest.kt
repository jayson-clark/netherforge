package dev.netherforge.plugin.session
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.lua.StaleLuaRef
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The project session and its services: a subsystem is one [RuntimeService]
 * registered in one place, [ProjectSession]'s list, and nothing else in the
 * runtime constructs one, holds one or calls its hooks.
 */
class SessionTest {
    /** The runtime's main sources, by path from the package folder. */
    private val sources: Map<String, String> by lazy {
        val root = TestServer.repo().resolve("apps/plugin/runtime/src/main/kotlin/dev/netherforge/plugin")
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
                .associate { root.relativize(it).joinToString("/") to it.readText() }
        }
    }

    /** Every class in the runtime's sources whose header says it's a [RuntimeService], with its package. */
    private val declared: Map<String, String> by lazy {
        val header = Regex(
            // The header runs to the first line that ends in the body's brace (a default like `= {}` doesn't).
            """^(?:internal |private )?(?:open |abstract )?class (\w+)\b([\s\S]*?)\{\s*$""",
            RegexOption.MULTILINE
        )
        sources.values.flatMap { text ->
            val pkg = text.lineSequence().first { it.startsWith("package ") }.removePrefix("package ")
            header.findAll(text).filter { "RuntimeService" in it.groupValues[2] }.map { it.groupValues[1] to pkg }
        }.toMap()
    }

    @Test
    fun `every service the runtime declares is registered in the session, once`() {
        TestServer(emptyMap()).use { server ->
            val registered = server.runtime.session.services().map { it::class.simpleName!! }
            assertEquals(registered.size, registered.toSet().size, "a service registered twice: $registered")
            assertEquals(declared.keys, registered.toSet())
        }
    }

    @Test
    fun `the session holds no service it didn't register, and the runtime none at all`() {
        TestServer(emptyMap()).use { server ->
            val session = server.runtime.session
            val registered = session.services()
            for (field in ProjectSession::class.java.declaredFields) {
                if (!RuntimeService::class.java.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                val value = field.get(session)
                assertTrue(registered.any { it === value }, "ProjectSession.${field.name} isn't in its list of services")
            }
            for (field in NetherForgeRuntime::class.java.declaredFields) {
                assertTrue(
                    !RuntimeService::class.java.isAssignableFrom(field.type),
                    "NetherForgeRuntime.${field.name} is a service: it belongs to the session"
                )
            }
        }
    }

    @Test
    fun `no service is constructed outside the session's list`() {
        for ((name, pkg) in declared) {
            val made = Regex("""(?<![\w.])$name\(""")
            // A file that imports another class of that name (format's `BlockData`) means that one.
            val other = Regex("""^import (?!${Regex.escape(pkg)}\.)[\w.]+\.$name$""", RegexOption.MULTILINE)
            for ((path, text) in sources) {
                if (path == "session/ProjectSession.kt" || other.containsMatchIn(text)) continue
                val lines = text.lines().filter { made.containsMatchIn(it) && !it.trimStart().startsWith("*") && "class $name" !in it }
                assertEquals(emptyList(), lines, "$path makes a $name; services are made and registered in ProjectSession")
            }
        }
    }

    @Test
    fun `a service's hooks are called only by the session`() {
        val hook = Regex(
            """\.(define|scopeReleased|liveness|followed)\(|\b(services|service)\.(start|stop|tick|playerJoined|playerQuit|entityGone|chunkUnloading|worldLoaded|worldSaving|problems|costs|reload)\("""
        )
        for ((path, text) in sources) {
            if (path == "session/ProjectSession.kt") continue
            val calls = text.lines().filter { hook.containsMatchIn(it) && !it.trimStart().startsWith("*") }
            assertEquals(emptyList(), calls, "$path calls a service's hook; only ProjectSession dispatches them")
        }
    }

    @Test
    fun `a Lua ref from an earlier session's state is refused, not used`() {
        TestServer(emptyMap()).use { server ->
            val old = server.runtime.session.scripts.host!!
            val kept = old.keepData(null)
            assertEquals(old.generation, kept.generation)
            server.runtime.reloadAll()
            val now = server.runtime.session.scripts.host!!
            assertNotEquals(old.generation, now.generation)
            // Its slot number names something else (or nothing) in the new state: using it would corrupt the heap.
            assertFailsWith<StaleLuaRef> { now.unref(kept) }
            assertFailsWith<StaleLuaRef> { now.encodeData(kept, "data") }
        }
    }

    private fun Path.joinToString(separator: String) = (0 until nameCount).joinToString(separator) { getName(it).toString() }
}
