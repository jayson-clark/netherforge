package dev.netherforge.plugin.http

import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The SSRF guard's classifier: every range a request must not reach, and public addresses it must. */
class AddressGuardTest {
    private fun kind(literal: String) = AddressGuard.classify(InetAddress.getByName(literal))

    private fun assertKind(expected: AddressGuard.Kind, vararg literals: String) {
        for (literal in literals) assertEquals(expected, kind(literal), literal)
    }

    @Test
    fun `IPv4 loopback private link-local and shared ranges, on and past their edges`() {
        assertKind(AddressGuard.Kind.LOOPBACK, "127.0.0.1", "127.255.255.255", "127.1", "2130706433")
        assertKind(
            AddressGuard.Kind.PRIVATE,
            "10.0.0.0",
            "10.255.255.255",
            "172.16.0.0",
            "172.31.255.255",
            "192.168.0.1",
            "192.168.255.255"
        )
        assertKind(AddressGuard.Kind.SHARED, "100.64.0.0", "100.100.100.100", "100.127.255.255")
        assertKind(AddressGuard.Kind.LINK_LOCAL, "169.254.0.1", "169.254.169.254", "169.254.255.255")
        assertKind(AddressGuard.Kind.PRIVATE, "198.18.0.1", "198.19.255.255")
        assertKind(AddressGuard.Kind.NEVER, "0.0.0.0", "224.0.0.1", "239.255.255.255", "255.255.255.255")
        assertKind(
            AddressGuard.Kind.RESERVED,
            "0.1.2.3",
            "240.0.0.1",
            "192.0.0.8",
            "192.0.2.1",
            "198.51.100.7",
            "203.0.113.9",
            "192.88.99.1"
        )
    }

    @Test
    fun `addresses just outside those ranges are public`() {
        val outside = listOf(
            "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255", "128.0.0.0", "169.253.255.255",
            "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "198.17.255.255", "198.20.0.0",
            "223.255.255.255", "8.8.8.8", "1.1.1.1", "93.184.216.34"
        )
        for (literal in outside) {
            assertNull(kind(literal), literal)
        }
    }

    @Test
    fun `IPv6 loopback unspecified unique-local link-local site-local and multicast`() {
        assertKind(AddressGuard.Kind.LOOPBACK, "::1", "0:0:0:0:0:0:0:1")
        assertKind(AddressGuard.Kind.NEVER, "::", "ff02::1", "ff00::", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
        assertKind(AddressGuard.Kind.UNIQUE_LOCAL, "fc00::1", "fd12:3456:789a::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
        assertKind(AddressGuard.Kind.LINK_LOCAL, "fe80::1", "febf::1", "fe80::dead:beef")
        assertKind(AddressGuard.Kind.PRIVATE, "fec0::1", "feff::1")
    }

    @Test
    fun `IPv6 ranges that carry or stand in for IPv4 and the documentation and unassigned space`() {
        assertKind(
            AddressGuard.Kind.RESERVED,
            "::10.0.0.1", "64:ff9b::808:808", "100::1", "2001::1", "2001:db8::1", "3fff::1", "4000::1", "8000::1"
        )
        // 6to4 carries its IPv4 address: a private one is that kind, a public one is still not a destination.
        assertKind(AddressGuard.Kind.PRIVATE, "2002:0a00:0001::1", "2002:c0a8:0101::1")
        assertKind(AddressGuard.Kind.LOOPBACK, "2002:7f00:0001::")
        assertKind(AddressGuard.Kind.RESERVED, "2002:0808:0808::1")
    }

    @Test
    fun `global unicast IPv6 is public`() {
        for (literal in listOf("2606:4700:4700::1111", "2001:4860:4860::8888", "2a00:1450:4001::1", "2001:1::1", "3ffe::1")) {
            assertNull(kind(literal), literal)
        }
    }

    @Test
    fun `an IPv4-mapped address is judged as the IPv4 address Java turns it into, and refused as mapped when it stays IPv6`() {
        // Java parses ::ffff:a.b.c.d as the Inet4Address it holds, so a mapped private address is a private one.
        assertKind(AddressGuard.Kind.LOOPBACK, "::ffff:127.0.0.1", "::ffff:7f00:1")
        assertKind(AddressGuard.Kind.PRIVATE, "::ffff:10.1.2.3", "::ffff:192.168.0.1")
        assertKind(AddressGuard.Kind.SHARED, "::ffff:100.64.0.1")
        assertKind(AddressGuard.Kind.LINK_LOCAL, "::ffff:169.254.169.254")
        assertNull(kind("::ffff:8.8.8.8"))
        // One that is still an Inet6Address (built from its parts, as a resolver could) is refused whatever it holds.
        for (embedded in listOf(byteArrayOf(10, 0, 0, 1), byteArrayOf(8, 8, 8, 8))) {
            val bytes = ByteArray(10) + byteArrayOf(-1, -1) + embedded
            assertEquals(AddressGuard.Kind.MAPPED, AddressGuard.classify(Inet6Address.getByAddress(null, bytes, 0)))
        }
    }

    @Test
    fun `private addresses are allowed by the owner's option, an address of no one host never is`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        assertNotNull(AddressGuard.refusal(loopback, allowPrivate = false))
        assertNull(AddressGuard.refusal(loopback, allowPrivate = true))
        for (literal in listOf("10.0.0.1", "100.64.0.1", "169.254.169.254", "fc00::1", "fe80::1", "::1")) {
            val address = InetAddress.getByName(literal)
            assertNotNull(AddressGuard.refusal(address, allowPrivate = false), literal)
            assertNull(AddressGuard.refusal(address, allowPrivate = true), literal)
        }
        for (literal in listOf("0.0.0.0", "224.0.0.1", "255.255.255.255", "::", "ff02::1")) {
            assertNotNull(AddressGuard.refusal(InetAddress.getByName(literal), allowPrivate = true), literal)
        }
        assertNull(AddressGuard.refusal(InetAddress.getByName("8.8.8.8"), allowPrivate = false))
        assertEquals("a loopback address", AddressGuard.refusal(loopback, allowPrivate = false))
    }
}
