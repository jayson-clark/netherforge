package dev.netherforge.plugin.api
import dev.netherforge.plugin.LuaChecks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `nf.encoding` and `nf.crypto`: what a script needs to talk to a web service
 * (a form body, Basic auth, a signed request). Each check runs in Lua and logs
 * only what it got wrong, so a failure names the expression.
 */
class EncodingTest {
    @Test
    fun `nf encoding writes and reads URLs and hex`() {
        val result = LuaChecks.run(
            """
            check("url", nf.encoding.url_encode("a b/ç~"), "a%20b%2F%C3%A7~")
            check("url back", nf.encoding.url_decode("a%20b%2f%C3%A7~"), "a b/ç~")
            check("a plus stays", nf.encoding.url_decode("a+b"), "a+b")
            check("a bad escape", nf.encoding.url_decode("%zz"), nil)
            check("a short escape", nf.encoding.url_decode("ab%4"), nil)
            check("not text", nf.encoding.url_decode("%FF"), nil)

            check("hex", nf.encoding.hex_encode("Hi"), "4869")
            check("hex back", nf.encoding.hex_decode("4869"), "Hi")
            check("either case", nf.encoding.hex_decode("C3A7"), "ç")
            check("odd", nf.encoding.hex_decode("486"), nil)
            check("not hex", nf.encoding.hex_decode("48G9"), nil)
            check("hex not text", nf.encoding.hex_decode("ff"), nil)
            """
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `nf encoding writes and reads base64 in both alphabets`() {
        val result = LuaChecks.run(
            """
            check("basic auth", nf.encoding.base64_encode("user:pass"), "dXNlcjpwYXNz")
            check("padded", nf.encoding.base64_encode("hi?>"), "aGk/Pg==")
            check("url safe", nf.encoding.base64_encode("hi?>", { url_safe = true }), "aGk_Pg==")
            check("unpadded", nf.encoding.base64_encode("hi?>", { url_safe = true, padding = false }), "aGk_Pg")
            check("back", nf.encoding.base64_decode("aGk/Pg=="), "hi?>")
            check("url safe back", nf.encoding.base64_decode("aGk_Pg"), "hi?>")
            check("not base64", nf.encoding.base64_decode("a*b"), nil)
            check("not text", nf.encoding.base64_decode("/w=="), nil)
            fails("a misspelled option", function() nf.encoding.base64_encode("x", { urlsafe = true }) end, "urlsafe")
            """
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `nf encoding writes forms as Stripe reads them`() {
        val result = LuaChecks.run(
            """
            local body = nf.encoding.form_encode({
              mode = "payment",
              line_items = { { price = "price_123", quantity = 1 } },
              metadata = { player = "Steve Jobs" },
              amount = 12.5,
              live = false,
              empty = {},
            })
            check("form", body, "amount=12.5&line_items%5B0%5D%5Bprice%5D=price_123&line_items%5B0%5D%5Bquantity%5D=1&live=false&metadata%5Bplayer%5D=Steve+Jobs&mode=payment")
            check("a list from 0", nf.encoding.form_encode({ items = { "a", "b" } }), "items%5B0%5D=a&items%5B1%5D=b")
            check("floats as decimals", nf.encoding.form_encode({ big = 1e20, small = 0.25 }), "big=100000000000000000000&small=0.25")
            check("nothing", nf.encoding.form_encode({}), "")

            local fields = nf.encoding.form_decode(body)
            check("decoded", fields["metadata[player]"], "Steve Jobs")
            check("nested name", fields["line_items[0][price]"], "price_123")
            local repeated = nf.encoding.form_decode("a=1&a=2&b&&c=")
            check("the first value", repeated.a, "1")
            check("no value", repeated.b, "")
            check("empty value", repeated.c, "")
            check("not a form", nf.encoding.form_decode("%zz=1"), nil)

            fails("a function", function() nf.encoding.form_encode({ f = function() end }) end, "fields.f: a function can't be put in a form")
            fails("a vector", function() nf.encoding.form_encode({ at = { v = vec3(1, 2, 3) } }) end, "fields.at.v: a value with a metatable")
            fails("a list at the top", function() nf.encoding.form_encode({ "a" }) end, "got a list")
            fails("a hole", function() nf.encoding.form_encode({ items = { "a", nil, "c" } }) end, "fields.items: a list with holes")
            fails("a number key", function() nf.encoding.form_encode({ t = { [5] = "x" } }) end, "fields.t: a key that isn't a string")
            fails("not a number", function() nf.encoding.form_encode({ n = 0 / 0 }) end, "fields.n:")
            local loop = {}
            loop.self = loop
            fails("itself", function() nf.encoding.form_encode({ loop = loop }) end, "a table inside itself")
            """
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `nf crypto hashes and signs as nf web verifies`() {
        val result = LuaChecks.run(
            """
            check("sha256", nf.crypto.hash("Hello, World!"), "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f")
            check("sha1", nf.crypto.hash("abc", { algorithm = "sha1" }), "a9993e364706816aba3e25717850c26c9cd0d89d")
            check("sha512", nf.crypto.hash("abc", { algorithm = "sha512" }), "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f")
            check("base64", nf.crypto.hash("abc", { algorithm = "sha1", encoding = "base64" }), "qZk+NkcGgWq6PiVxeFDCbJzQ2J0=")

            local secret = "It's a Secret to Everybody"
            check("hmac", nf.crypto.sign("Hello, World!", { secret = secret }), "757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17")
            check("hmac base64", nf.crypto.sign("Hello, World!", { secret = secret, encoding = "base64" }), "dXEH6g6yUJ/CESIczphLijdXC211hsIsRvQ3nIsEPhc=")
            local signed = nf.crypto.sign("1700000000.{}", { secret = secret, algorithm = "sha512" })
            check("verifies", nf.web.verify_signature("1700000000.{}", signed, { secret = secret, algorithm = "sha512" }), true)
            check("only its own", nf.web.verify_signature("1700000001.{}", signed, { secret = secret, algorithm = "sha512" }), false)

            fails("no secret", function() nf.crypto.sign("x", {}) end, "secret")
            fails("an unknown hash", function() nf.crypto.hash("x", { algorithm = "md5" }) end, "md5")
            """
        )
        assertEquals(LuaChecks.DONE, result)
    }
}
