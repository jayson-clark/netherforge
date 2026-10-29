package dev.netherforge.plugin.api

import dev.netherforge.plugin.binding.Base64Options
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.HashOptions
import dev.netherforge.plugin.binding.NfCryptoApi
import dev.netherforge.plugin.binding.NfEncodingApi
import dev.netherforge.plugin.binding.SigningOptions
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import party.iroiro.luajava.Lua
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `nf.encoding`. Lua strings cross as text, so everything here is over a
 * text's UTF-8 bytes, and decoding to bytes that aren't UTF-8 is nil.
 */
internal class NfEncodingImpl : NfEncodingApi {
    override fun urlEncode(caller: Caller, text: String): String = percentEncode(text, form = false)

    override fun urlDecode(caller: Caller, text: String): String? = percentDecode(text, form = false)

    override fun formEncode(caller: Caller, fields: LuaValue): String {
        val pairs = mutableListOf<Pair<String, String>>()
        fields.read { lua, index -> FormFields(lua, pairs, fields::meter).top(index) }
        return pairs.joinToString("&") { (name, value) -> percentEncode(name, form = true) + "=" + percentEncode(value, form = true) }
    }

    /** A name given more than once is its first value, as `nf.web` reads a request's form. */
    override fun formDecode(caller: Caller, text: String): Map<String, String>? {
        val fields = LinkedHashMap<String, String>()
        for (pair in text.split('&')) {
            if (pair.isEmpty()) continue
            val equals = pair.indexOf('=')
            val name = percentDecode(if (equals < 0) pair else pair.substring(0, equals), form = true) ?: return null
            val value = if (equals < 0) "" else percentDecode(pair.substring(equals + 1), form = true) ?: return null
            fields.putIfAbsent(name, value)
        }
        return fields
    }

    override fun base64Encode(caller: Caller, text: String, options: Base64Options?): String {
        val encoder = if (options?.urlSafe == true) Base64.getUrlEncoder() else Base64.getEncoder()
        val padded = if (options?.padding == false) encoder.withoutPadding() else encoder
        return padded.encodeToString(text.toByteArray(StandardCharsets.UTF_8))
    }

    /** Either alphabet, padded or not: the URL-safe one's `-_` read as `+/`, and padding put back. */
    override fun base64Decode(caller: Caller, text: String): String? {
        val standard = text.replace('-', '+').replace('_', '/')
        val padded = if (standard.length % 4 == 0) standard else standard + "=".repeat(4 - standard.length % 4)
        val bytes = try {
            Base64.getDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return utf8(bytes)
    }

    override fun hexEncode(caller: Caller, text: String): String = HexFormat.of().formatHex(text.toByteArray(StandardCharsets.UTF_8))

    override fun hexDecode(caller: Caller, text: String): String? {
        val bytes = try {
            HexFormat.of().parseHex(text)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return utf8(bytes)
    }
}

/** `nf.crypto`. */
internal class NfCryptoImpl : NfCryptoApi {
    override fun hash(caller: Caller, text: String, options: HashOptions?): String {
        val algorithm = when (options?.algorithm ?: "sha256") {
            "sha1" -> "SHA-1"
            "sha512" -> "SHA-512"
            else -> "SHA-256"
        }
        val digest = MessageDigest.getInstance(algorithm).digest(text.toByteArray(StandardCharsets.UTF_8))
        return written(digest, options?.encoding)
    }

    override fun sign(caller: Caller, text: String, options: SigningOptions): String =
        written(hmac(text, options.secret, options.algorithm), options.encoding)
}

/** [text]'s HMAC under [secret], by `"sha256"` (the default), `"sha1"` or `"sha512"`: `nf.crypto.sign`'s, and what `nf.web.verify_signature` checks. */
internal fun hmac(text: String, secret: String, algorithm: String?): ByteArray {
    val mac = when (algorithm ?: "sha256") {
        "sha1" -> "HmacSHA1"
        "sha512" -> "HmacSHA512"
        else -> "HmacSHA256"
    }
    return Mac.getInstance(mac).run {
        init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), mac))
        doFinal(text.toByteArray(StandardCharsets.UTF_8))
    }
}

private fun written(bytes: ByteArray, encoding: String?): String =
    if (encoding == "base64") Base64.getEncoder().encodeToString(bytes) else HexFormat.of().formatHex(bytes)

/** [bytes] as text, or null when they aren't UTF-8. */
private fun utf8(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

/**
 * [text]'s UTF-8 bytes with all but the unreserved characters as `%XX`: in a
 * URL (RFC 3986: letters, digits, `-._~`), or in a form (the WHATWG's:
 * letters, digits, `*-._`, and a space as `+`).
 */
private fun percentEncode(text: String, form: Boolean): String {
    val out = StringBuilder(text.length)
    for (byte in text.toByteArray(StandardCharsets.UTF_8)) {
        val c = (byte.toInt() and 0xFF).toChar()
        when {
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' -> out.append(c)
            c == '~' && !form -> out.append(c)
            c == '*' && form -> out.append(c)
            c == ' ' && form -> out.append('+')
            else -> out.append('%').append(HEX_DIGITS[c.code shr 4]).append(HEX_DIGITS[c.code and 0xF])
        }
    }
    return out.toString()
}

/** [text] with its `%XX` decoded (and in a form `+` as a space); null for a `%` without two hex digits, or bytes that aren't UTF-8. */
private fun percentDecode(text: String, form: Boolean): String? {
    if ('%' !in text && !(form && '+' in text)) return text
    val bytes = ByteArrayOutputStream(text.length)
    val utf8 = text.toByteArray(StandardCharsets.UTF_8)
    var i = 0
    while (i < utf8.size) {
        val b = utf8[i].toInt()
        when {
            b == '%'.code -> {
                if (i + 2 >= utf8.size) return null
                val high = Character.digit(utf8[i + 1].toInt(), 16)
                val low = Character.digit(utf8[i + 2].toInt(), 16)
                if (high < 0 || low < 0) return null
                bytes.write(high shl 4 or low)
                i += 3
                continue
            }
            b == '+'.code && form -> bytes.write(' '.code)
            else -> bytes.write(b)
        }
        i++
    }
    return utf8(bytes.toByteArray())
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/** How deep tables in a form may go: deeper is a table inside itself, or near enough. */
private const val MAX_FORM_DEPTH = 32

/**
 * A script's table read off the stack as a form's names and values, raw (no
 * metamethod runs, so no script code), nested tables as `name[key]` and lists
 * from 0 (`name[0]`), as Stripe and the PHP/Rails convention read them.
 * Object keys are sorted, so the same table is the same text. Each entry is
 * charged to the script ([LuaValue.meter]), as any table the API walks is.
 */
private class FormFields(private val lua: Lua, private val pairs: MutableList<Pair<String, String>>, private val meter: (Int) -> Unit) {
    fun top(index: Int) {
        val at = if (index < 0) lua.top + index + 1 else index
        if (isList(at)) throw LuaApiException("bad argument 'fields' (a form's names are strings: a table of them expected, got a list)")
        entries(at, "fields", null, 0)
    }

    private fun value(index: Int, where: String, name: String, depth: Int) {
        when (lua.type(index)) {
            Lua.LuaType.STRING -> pairs += name to lua.toString(index)!!
            Lua.LuaType.BOOLEAN -> pairs += name to lua.toBoolean(index).toString()
            Lua.LuaType.NUMBER -> pairs += name to number(index, where)
            Lua.LuaType.TABLE -> entries(if (index < 0) lua.top + index + 1 else index, where, name, depth + 1)
            else -> throw LuaApiException("$where: a ${lua.type(index).toString().lowercase()} can't be put in a form")
        }
    }

    /** A table's entries under [prefix] (none at the top): a list's by position from 0, an object's by its keys, sorted. */
    private fun entries(at: Int, where: String, prefix: String?, depth: Int) {
        if (depth > MAX_FORM_DEPTH) {
            throw LuaApiException("$where: tables nested more than $MAX_FORM_DEPTH deep (a table inside itself?) can't be put in a form")
        }
        if (lua.getMetatable(at) != 0) {
            lua.pop(1)
            throw LuaApiException("$where: a value with a metatable (a Vec3, a Location, a handle) can't be put in a form")
        }
        lua.checkStack(4)
        if (isList(at)) {
            val size = lua.rawLength(at)
            var count = 0
            lua.pushNil()
            while (lua.next(at) != 0) {
                meter(1)
                count++
                lua.pop(1)
            }
            if (count != size) throw LuaApiException("$where: a list with holes or other keys can't be put in a form")
            for (position in 1..size) {
                lua.rawGetI(at, position)
                value(lua.top, "$where[$position]", "$prefix[${position - 1}]", depth)
                lua.pop(1)
            }
            return
        }
        val keys = mutableListOf<String>()
        lua.pushNil()
        while (lua.next(at) != 0) {
            meter(1)
            lua.pop(1)
            if (lua.type(-1) != Lua.LuaType.STRING) {
                throw LuaApiException("$where: a key that isn't a string, outside a list, can't be put in a form")
            }
            keys += lua.toString(-1)!!
        }
        keys.sort()
        for (key in keys) {
            lua.push(key)
            lua.rawGet(at)
            value(lua.top, "$where.$key", if (prefix == null) key else "$prefix[$key]", depth)
            lua.pop(1)
        }
    }

    /** A list has a first item; any other table is an object (the empty table too). */
    private fun isList(at: Int): Boolean {
        lua.rawGetI(at, 1)
        val list = !lua.isNil(-1)
        lua.pop(1)
        return list
    }

    /** An integer as itself, a float as plain decimal (never `1.0E20`); NaN and infinities can't be sent. */
    private fun number(index: Int, where: String): String {
        if (lua.isInteger(index)) return lua.toInteger(index).toString()
        val number = lua.toNumber(index)
        if (!number.isFinite()) throw LuaApiException("$where: $number can't be put in a form")
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()
    }
}
