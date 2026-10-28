package dev.netherforge.format.project

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Packages' declared capabilities: what `requires` grants, what it's written as, and the sum across a tree. */
class RequirementTest {
    @Test
    fun idsReadBackAsTheRequirementTheyName() {
        for (id in listOf("moderation", "db", "web", "bots", "wasm", "network", "http", "http:discord.com", "plugin:vault")) {
            assertEquals(id, Requirement.parse(id)?.id)
        }
        assertEquals(Requirement.Plugin("vault"), Requirement.parse("plugin:vault"))
        for (id in listOf("", "plugin:", "plugin:Vault", "http:", "http:Discord.com", "networks", "plugin")) {
            assertNull(Requirement.parse(id), id)
        }
    }

    @Test
    fun aDeclarationGrantsOnlyWhatItSays() {
        val requires = ProjectRequires(moderation = true, http = listOf("discord.com", "*.example.com"), plugins = listOf("vault"))
        assertTrue(Requirement.Moderation.grantedBy(requires))
        assertFalse(Requirement.Db.grantedBy(requires))
        assertFalse(Requirement.Moderation.grantedBy(null))
        assertFalse(Requirement.Bots.grantedBy(requires))
        assertTrue(Requirement.Bots.grantedBy(ProjectRequires(bots = true)))
        assertFalse(Requirement.Network.grantedBy(requires))
        assertTrue(Requirement.Network.grantedBy(ProjectRequires(network = true)))
        assertEquals("\"requires\": { \"network\": true }", Requirement.Network.declaration)
        // The spec's kind-level check: any host at all.
        assertTrue(Requirement.Http().grantedBy(requires))
        assertFalse(Requirement.Http().grantedBy(ProjectRequires(http = emptyList())))
        assertTrue(Requirement.Http("discord.com").grantedBy(requires))
        assertTrue(Requirement.Http("api.example.com").grantedBy(requires))
        assertTrue(Requirement.Http("a.b.example.com").grantedBy(requires))
        assertFalse(Requirement.Http("example.com").grantedBy(requires), "a wildcard is only what's under it")
        assertFalse(Requirement.Http("cdn.discord.com").grantedBy(requires))
        assertFalse(Requirement.Http("discord.com.evil.net").grantedBy(requires))
        assertFalse(Requirement.Http("notexample.com").grantedBy(requires))
        assertTrue(Requirement.allows("discord.com", "Discord.COM."), "DNS names compare without case or a final dot")
        assertTrue(Requirement.Plugin("vault").grantedBy(requires))
        assertFalse(Requirement.Plugin("luckperms").grantedBy(requires))
    }

    @Test
    fun hostsAndPluginNamesHaveRules() {
        for (host in listOf("discord.com", "api.example.com", "localhost", "*.example.com", "x-1.io", "203.0.113.5")) {
            assertTrue(Names.isHttpHost(host), host)
        }
        for (host in listOf("", "*.com", "*", "Discord.com", "https://x.dev", "x.dev/path", "-x.com", "x..com", "x.com:443", "a b.com")) {
            assertFalse(Names.isHttpHost(host), host)
        }
        assertTrue(Names.isPluginName("vault"))
        assertTrue(Names.isPluginName("placeholder_api-2"))
        assertFalse(Names.isPluginName("PlaceholderAPI"))
        assertFalse(Names.isPluginName("a b"))
    }

    @Test
    fun aUrlWhereAHostBelongsSaysWhichHostToWrite() {
        val text = testManifest(""", "requires": { "http": ["wss://gateway.discord.gg/?v=10", "ftp://"] }""")
        val problems = Projects.load(MapProjectSource(mapOf("netherforge.json" to text))).problems.filter {
            it.code ==
                "project.requires-host"
        }
        assertEquals(
            listOf(
                "\"ftp://\" isn't a host (${Names.HTTP_HOST_RULE})",
                "\"wss://gateway.discord.gg/?v=10\" is a URL: list its host alone, \"gateway.discord.gg\", which covers http, https, ws and wss"
            ),
            problems.map { it.message }.sorted()
        )
    }

    @Test
    fun declarationsAreSetsInTheManifest() {
        val text = testManifest(""", "requires": { "plugins": ["vault", "essentials", "vault"], "http": ["b.com", "a.com"], "db": true }""")
        val parsed = (ManifestKind.parse(text, "netherforge.json") as CanonicalJson.Parsed.Ok).value
        val written = ManifestKind.write(parsed)
        assertTrue("\"http\": [\"a.com\", \"b.com\"]" in written, written)
        assertTrue("\"plugins\": [\"essentials\", \"vault\"]" in written, written)
        assertEquals(
            listOf("db", "http:a.com", "http:b.com", "plugin:essentials", "plugin:vault"),
            Requirement.declared(parsed.requires).map { it.id }
        )
    }

    /** The sum the editor and the server show: every package's, its own dependencies' too, each once with who declares it. */
    @Test
    fun theTreeIsSummedWithWhoDeclaresWhat() {
        val manifests = mapOf(
            "economy" to """{ "formatVersion": 1, "name": "Economy", "namespace": "economy", "version": "1.0.0", "minecraft": "26.3",
                "requires": { "db": true, "plugins": ["vault"] } }""",
            "chat" to """{ "formatVersion": 1, "name": "Chat", "namespace": "chat", "version": "1.0.0", "minecraft": "26.3",
                "requires": { "http": ["discord.com"], "db": true }, "dependencies": { "economy": { "path": "../economy" } } }"""
        )
        val sources = object : PackageSources {
            override fun open(namespace: String, request: PackageRequest) =
                manifests[namespace]?.let { OpenedPackage(MapProjectSource(mapOf("netherforge.json" to it)), null, null) }
                    ?: PackageMissing()
        }
        val app =
            testManifest(
                """, "requires": { "moderation": true, "http": ["discord.com"] }, "dependencies": { "chat": { "path": "../chat" } }"""
            )
        val snapshot = Projects.load(MapProjectSource(mapOf("netherforge.json" to app)), packages = sources)
        assertEquals(
            listOf(
                RequirementUse("moderation", listOf("test")),
                RequirementUse("db", listOf("chat", "economy")),
                RequirementUse("http:discord.com", listOf("test", "chat")),
                RequirementUse("plugin:vault", listOf("economy"))
            ),
            Requirement.combined(snapshot)
        )
    }
}
