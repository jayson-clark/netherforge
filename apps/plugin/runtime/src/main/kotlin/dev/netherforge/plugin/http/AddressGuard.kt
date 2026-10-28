package dev.netherforge.plugin.http

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Which addresses `nf.http.request` may connect to: the SSRF guard's
 * classifier. A script names a host; whoever controls that name's DNS
 * decides where it leads, so the server checks the address it is about to
 * connect to, not the name.
 *
 * Classified from the address's bytes, never its text, so every spelling of
 * one address (`127.1`, `2130706433`, `::ffff:7f00:1`, `[::1]`) is the same
 * answer. [refusal] says why an address is refused, or null when it's a
 * public unicast address. Everything that isn't public is refused unless the
 * server owner allowed private addresses (`http.allow-private-addresses`),
 * except [Kind.NEVER]: addresses that name no one host (multicast, broadcast,
 * the unspecified address `0.0.0.0` and `::` as a destination).
 *
 * Java turns an IPv4-mapped IPv6 address (`::ffff:10.0.0.1`) into the
 * `Inet4Address` it holds when it parses or resolves one, so those arrive
 * here as the v4 address and are judged as that; [Kind.MAPPED] covers the
 * `Inet6Address` that still carries one.
 */
object AddressGuard {
    /** What an address is, when it isn't public. */
    enum class Kind(val words: String) {
        /** Never a destination, whatever the owner allows. */
        NEVER("not an address of one host"),
        LOOPBACK("a loopback address"),
        PRIVATE("a private address"),
        SHARED("a shared (CGNAT) address"),
        LINK_LOCAL("a link-local address"),
        UNIQUE_LOCAL("a unique-local address"),
        MAPPED("an IPv4-mapped address"),
        RESERVED("a reserved address")
    }

    /** Why [address] may not be connected to, or null when it may. [allowPrivate] is the server owner's option. */
    fun refusal(address: InetAddress, allowPrivate: Boolean): String? {
        val kind = classify(address) ?: return null
        if (allowPrivate && kind != Kind.NEVER) return null
        return kind.words
    }

    /** What kind of non-public address [address] is, or null for a public unicast one. */
    fun classify(address: InetAddress): Kind? = when (address) {
        is Inet4Address -> v4(address.address)
        is Inet6Address -> v6(address.address)
        else -> Kind.RESERVED
    }

    private fun ByteArray.u(index: Int) = this[index].toInt() and 0xff

    private fun v4(a: ByteArray): Kind? {
        val first = a.u(0)
        val second = a.u(1)
        return when {
            // 0.0.0.0 is "this host" on most systems; the rest of 0/8 is "this network".
            a.all { it.toInt() == 0 } -> Kind.NEVER
            first == 0 -> Kind.RESERVED
            first == 10 -> Kind.PRIVATE
            first == 100 && second in 64..127 -> Kind.SHARED
            first == 127 -> Kind.LOOPBACK
            first == 169 && second == 254 -> Kind.LINK_LOCAL
            first == 172 && second in 16..31 -> Kind.PRIVATE
            first == 192 && second == 168 -> Kind.PRIVATE
            // 192.0.0.0/24 (IETF protocol assignments), 192.0.2.0/24, 192.88.99.0/24 (6to4 relay), 198.51.100.0/24, 203.0.113.0/24.
            first == 192 && second == 0 && (a.u(2) == 0 || a.u(2) == 2) -> Kind.RESERVED
            first == 192 && second == 88 && a.u(2) == 99 -> Kind.RESERVED
            first == 198 && second == 51 && a.u(2) == 100 -> Kind.RESERVED
            first == 203 && second == 0 && a.u(2) == 113 -> Kind.RESERVED
            // 198.18.0.0/15 (benchmarking).
            first == 198 && second in 18..19 -> Kind.PRIVATE
            // 224/4 multicast, 240/4 reserved (and 255.255.255.255, broadcast).
            first in 224..239 -> Kind.NEVER
            first >= 240 -> if (a.all { it.toInt() == -1 }) Kind.NEVER else Kind.RESERVED
            else -> null
        }
    }

    private fun v6(a: ByteArray): Kind? {
        fun zeros(from: Int, to: Int) = (from until to).all { a[it].toInt() == 0 }
        val first = a.u(0)
        val second = a.u(1)
        return when {
            zeros(0, 15) && a[15].toInt() == 0 -> Kind.NEVER
            zeros(0, 15) && a[15].toInt() == 1 -> Kind.LOOPBACK
            // ::ffff:0:0/96, a v4 address in v6 clothes: refused whatever it holds, so a name can't smuggle a private one through.
            zeros(0, 10) && a.u(10) == 0xff && a.u(11) == 0xff -> Kind.MAPPED
            // ::/96 (deprecated v4-compatible), 64:ff9b::/96 (NAT64) and 64:ff9b:1::/48 (local NAT64): carry a v4 address.
            zeros(0, 12) -> Kind.RESERVED
            first == 0x00 && second == 0x64 && a.u(2) == 0xff && a.u(3) == 0x9b -> Kind.RESERVED
            // 100::/64 discard-only.
            first == 0x01 && second == 0x00 && zeros(2, 8) -> Kind.RESERVED
            // 2001::/32 Teredo, 2001:db8::/32 documentation.
            first == 0x20 && second == 0x01 && a.u(2) == 0 && a.u(3) == 0 -> Kind.RESERVED
            first == 0x20 && second == 0x01 && a.u(2) == 0x0d && a.u(3) == 0xb8 -> Kind.RESERVED
            // 3fff::/20 documentation.
            first == 0x3f && second == 0xff && (a.u(2) and 0xf0) == 0 -> Kind.RESERVED
            // 2002::/16 6to4: its v4 address sits in the next four bytes, and a private one is a private destination.
            first == 0x20 && second == 0x02 -> v4(a.copyOfRange(2, 6)) ?: Kind.RESERVED
            // fc00::/7 unique-local, fe80::/10 link-local, fec0::/10 site-local (deprecated), ff00::/8 multicast.
            (first and 0xfe) == 0xfc -> Kind.UNIQUE_LOCAL
            first == 0xfe && (second and 0xc0) == 0x80 -> Kind.LINK_LOCAL
            first == 0xfe && (second and 0xc0) == 0xc0 -> Kind.PRIVATE
            first == 0xff -> Kind.NEVER
            // Global unicast is 2000::/3; anything else is unassigned.
            (first and 0xe0) != 0x20 -> Kind.RESERVED
            else -> null
        }
    }
}
