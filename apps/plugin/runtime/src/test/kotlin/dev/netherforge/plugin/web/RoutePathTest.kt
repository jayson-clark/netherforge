package dev.netherforge.plugin.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Routes' paths: what's one, what each matches and hands over, which wins, and how request paths and queries are read. */
class RoutePathTest {
    private fun path(text: String) = RoutePath.parse(text).getOrThrow()

    @Test
    fun `a path is literal segments, parameters and a last rest`() {
        for (good in listOf(
            "/",
            "/hooks/store",
            "/replays/:id",
            "/files/*rest",
            "/a.b~c-d_e/:x/:y"
        )) {
            assertTrue(RoutePath.parse(good).isSuccess, good)
        }
        for (bad in listOf("", "hooks", "/a//b", "/a/", "/*rest/more", "/:Bad", "/:x/:x", "/a b", "/..", "/%41")) {
            assertTrue(RoutePath.parse(bad).isFailure, bad)
        }
    }

    @Test
    fun `a match hands over its parameters, and a rest takes everything after`() {
        assertEquals(mapOf("id" to "12"), path("/replays/:id").matches(listOf("replays", "12")))
        assertNull(path("/replays/:id").matches(listOf("replays")))
        assertNull(path("/replays/:id").matches(listOf("replays", "12", "x")))
        assertEquals(mapOf("rest" to "a/b/c"), path("/files/*rest").matches(listOf("files", "a", "b", "c")))
        assertNull(path("/files/*rest").matches(listOf("files")))
        assertEquals(emptyMap(), path("/").matches(emptyList()))
    }

    @Test
    fun `the more literal route ranks first, and same-shaped routes are the same`() {
        assertTrue(path("/a/b").rank.zip(path("/a/:x").rank).any { (a, b) -> a < b })
        assertTrue(path("/a/:x").sameAs(path("/a/:y")))
        assertFalse(path("/a/:x").sameAs(path("/a/b")))
    }

    @Test
    fun `request paths and queries are decoded, and what can't be is refused`() {
        assertEquals(listOf("shop", "a b", "é"), RoutePath.segments("/shop/a%20b/%C3%A9/"))
        assertEquals(listOf("shop", "a+b"), RoutePath.segments("/shop/a+b"))
        assertNull(RoutePath.segments("/shop/%zz"))
        assertNull(RoutePath.segments("/shop/%2e%2e/x"))
        assertNull(RoutePath.segments("/shop//x"))
        assertEquals(mapOf("a" to "1", "b" to "x y", "c" to ""), RoutePath.query("a=1&b=x+y&a=2&c"))
        assertEquals("a=x%20y&b=%26", RoutePath.encodeQuery(mapOf("b" to "&", "a" to "x y")))
    }
}
