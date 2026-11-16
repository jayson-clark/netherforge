//! Which addresses `nf.http` may connect to: the SSRF guard's classifier (Kotlin's `AddressGuard`).
//!
//! A script names a host; whoever controls that name's DNS decides where it leads, so the server judges the address
//! it's about to connect to, never the name. Judged from the address's bytes, never its text, so every spelling of
//! one address is the same answer. Everything that isn't public is refused unless the server owner allowed private
//! addresses, except [`Kind::Never`]: addresses that name no one host.
//!
//! An IPv4-mapped IPv6 address (`::ffff:10.0.0.1`) is judged as the v4 address it holds: Java hands one over as
//! that `Inet4Address`, so Kotlin's guard did the same, where Rust's `IpAddr` keeps it v6.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// What an address is, when it isn't public.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    /// Never a destination, whatever the owner allows.
    Never,
    Loopback,
    Private,
    Shared,
    LinkLocal,
    UniqueLocal,
    Mapped,
    Reserved,
}

impl Kind {
    pub fn words(self) -> &'static str {
        match self {
            Kind::Never => "not an address of one host",
            Kind::Loopback => "a loopback address",
            Kind::Private => "a private address",
            Kind::Shared => "a shared (CGNAT) address",
            Kind::LinkLocal => "a link-local address",
            Kind::UniqueLocal => "a unique-local address",
            Kind::Mapped => "an IPv4-mapped address",
            Kind::Reserved => "a reserved address",
        }
    }
}

/// Why `address` may not be connected to, or none when it may. `allow_private` is the server owner's option.
pub fn refusal(address: IpAddr, allow_private: bool) -> Option<&'static str> {
    let kind = classify(address)?;
    if allow_private && kind != Kind::Never {
        return None;
    }
    Some(kind.words())
}

/// What kind of non-public address `address` is, or none for a public unicast one.
pub fn classify(address: IpAddr) -> Option<Kind> {
    match address {
        IpAddr::V4(v4) => v4_kind(v4.octets()),
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            // As Java reads it: the v4 address it carries.
            Some(v4) => v4_kind(v4.octets()),
            None => v6_kind(v6.octets()),
        },
    }
}

fn v4_kind(a: [u8; 4]) -> Option<Kind> {
    let (first, second, third) = (a[0], a[1], a[2]);
    Some(match () {
        // 0.0.0.0 is "this host" on most systems; the rest of 0/8 is "this network".
        _ if a == [0; 4] => Kind::Never,
        _ if first == 0 => Kind::Reserved,
        _ if first == 10 => Kind::Private,
        _ if first == 100 && (64..=127).contains(&second) => Kind::Shared,
        _ if first == 127 => Kind::Loopback,
        _ if first == 169 && second == 254 => Kind::LinkLocal,
        _ if first == 172 && (16..=31).contains(&second) => Kind::Private,
        _ if first == 192 && second == 168 => Kind::Private,
        // 192.0.0.0/24 (IETF protocol assignments), 192.0.2.0/24, 192.88.99.0/24 (6to4 relay), 198.51.100.0/24,
        // 203.0.113.0/24.
        _ if first == 192 && second == 0 && (third == 0 || third == 2) => Kind::Reserved,
        _ if first == 192 && second == 88 && third == 99 => Kind::Reserved,
        _ if first == 198 && second == 51 && third == 100 => Kind::Reserved,
        _ if first == 203 && second == 0 && third == 113 => Kind::Reserved,
        // 198.18.0.0/15 (benchmarking).
        _ if first == 198 && (18..=19).contains(&second) => Kind::Private,
        // 224/4 multicast, 240/4 reserved (and 255.255.255.255, broadcast).
        _ if (224..=239).contains(&first) => Kind::Never,
        _ if first >= 240 => {
            if a == [255; 4] {
                Kind::Never
            } else {
                Kind::Reserved
            }
        }
        _ => return None,
    })
}

fn v6_kind(a: [u8; 16]) -> Option<Kind> {
    let zeros = |from: usize, to: usize| a[from..to].iter().all(|&b| b == 0);
    let (first, second) = (a[0], a[1]);
    Some(match () {
        _ if zeros(0, 15) && a[15] == 0 => Kind::Never,
        _ if zeros(0, 15) && a[15] == 1 => Kind::Loopback,
        // ::ffff:0:0/96 is judged by `classify` as the v4 address it holds; this is only for a caller of `v6_kind`.
        _ if zeros(0, 10) && a[10] == 0xff && a[11] == 0xff => Kind::Mapped,
        // ::/96 (deprecated v4-compatible), 64:ff9b::/96 (NAT64) and 64:ff9b:1::/48 (local NAT64): carry a v4
        // address.
        _ if zeros(0, 12) => Kind::Reserved,
        _ if first == 0x00 && second == 0x64 && a[2] == 0xff && a[3] == 0x9b => Kind::Reserved,
        // 100::/64 discard-only.
        _ if first == 0x01 && second == 0x00 && zeros(2, 8) => Kind::Reserved,
        // 2001::/32 Teredo, 2001:db8::/32 documentation.
        _ if first == 0x20 && second == 0x01 && a[2] == 0 && a[3] == 0 => Kind::Reserved,
        _ if first == 0x20 && second == 0x01 && a[2] == 0x0d && a[3] == 0xb8 => Kind::Reserved,
        // 3fff::/20 documentation.
        _ if first == 0x3f && second == 0xff && (a[2] & 0xf0) == 0 => Kind::Reserved,
        // 2002::/16 6to4: its v4 address sits in the next four bytes, and a private one is a private destination.
        _ if first == 0x20 && second == 0x02 => {
            return Some(v4_kind([a[2], a[3], a[4], a[5]]).unwrap_or(Kind::Reserved));
        }
        // fc00::/7 unique-local, fe80::/10 link-local, fec0::/10 site-local (deprecated), ff00::/8 multicast.
        _ if (first & 0xfe) == 0xfc => Kind::UniqueLocal,
        _ if first == 0xfe && (second & 0xc0) == 0x80 => Kind::LinkLocal,
        _ if first == 0xfe && (second & 0xc0) == 0xc0 => Kind::Private,
        _ if first == 0xff => Kind::Never,
        // Global unicast is 2000::/3; anything else is unassigned.
        _ if (first & 0xe0) != 0x20 => Kind::Reserved,
        _ => return None,
    })
}

/// Whether `host` (a URL's, brackets gone) is what a client would take for an IP address and never look up: a colon
/// in it, or only digits and dots (OkHttp's own test, kept so the same spellings are judged the same way).
pub fn looks_literal(host: &str) -> bool {
    let hex_colon = host.contains(':')
        && host
            .chars()
            .all(|c| c.is_ascii_hexdigit() || c == ':' || c == '.');
    let dotted = !host.is_empty() && host.chars().all(|c| c.is_ascii_digit() || c == '.');
    hex_colon || dotted
}

/// `host` as an address, read only as written plainly: IPv6, or four decimal numbers with no leading zeros. Spellings
/// like `2130706433`, `127.1` or `0177.0.0.1` are none, never guessed at (a system's resolver reads some of them as
/// addresses and asks DNS for others).
pub fn literal(host: &str) -> Option<IpAddr> {
    let host = host.trim_start_matches('[').trim_end_matches(']');
    if host.contains(':') {
        return host.parse::<Ipv6Addr>().ok().map(IpAddr::V6);
    }
    let parts: Vec<&str> = host.split('.').collect();
    if parts.len() != 4 {
        return None;
    }
    let mut bytes = [0u8; 4];
    for (i, part) in parts.iter().enumerate() {
        if part.is_empty()
            || part.len() > 3
            || (part.len() > 1 && part.starts_with('0'))
            || !part.bytes().all(|b| b.is_ascii_digit())
        {
            return None;
        }
        bytes[i] = part.parse::<u16>().ok().filter(|&n| n <= 255)? as u8;
    }
    Some(IpAddr::V4(Ipv4Addr::from(bytes)))
}

/// This machine's own addresses (every network interface's), read at most once a minute: a public address the server
/// itself has would otherwise reach whatever listens on it (RCON, the pack server, the network's hub).
pub fn own_addresses() -> Vec<IpAddr> {
    use std::sync::Mutex;
    use std::time::{Duration, Instant};
    static CACHED: Mutex<Option<(Instant, Vec<IpAddr>)>> = Mutex::new(None);
    let mut cached = CACHED.lock().unwrap_or_else(|e| e.into_inner());
    if let Some((at, addresses)) = &*cached
        && at.elapsed() < Duration::from_secs(60)
    {
        return addresses.clone();
    }
    let addresses: Vec<IpAddr> = if_addrs::get_if_addrs()
        .map(|all| all.into_iter().map(|i| i.ip()).collect())
        .unwrap_or_default();
    *cached = Some((Instant::now(), addresses.clone()));
    addresses
}
