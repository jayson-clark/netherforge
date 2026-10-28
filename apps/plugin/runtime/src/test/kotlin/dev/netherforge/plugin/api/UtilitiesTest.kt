package dev.netherforge.plugin.api
import dev.netherforge.plugin.LuaChecks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `nf.time`, `nf.random` (and its `Random` generators) and `nf.math`: the
 * utilities the sandbox took away with `os`. Each check runs in Lua and logs
 * only what it got wrong, so a failure names the expression.
 */
class UtilitiesTest {
    @Test
    fun `nf time formats and parses clock times`() {
        val result = LuaChecks.run(
            """
            local utc = { zone = "UTC" }
            check("epoch", nf.time.format(0, "yyyy-MM-dd HH:mm:ss", utc), "1970-01-01 00:00:00")
            local moment = 1791050400000 -- 2026-10-03 18:00 UTC
            check("names", nf.time.format(moment, "EEEE, d MMMM yyyy 'at' h a", utc), "Saturday, 3 October 2026 at 6 PM")
            check("a zone", nf.time.format(moment, "HH:mm XXX", { zone = "Europe/London" }), "19:00 +01:00")
            check("an offset", nf.time.format(moment, "HH:mm", { zone = "-05:00" }), "13:00")

            check("parse", nf.time.parse("2026-10-03 18:00", "yyyy-MM-dd HH:mm", utc), moment)
            check("parse in a zone", nf.time.parse("2026-10-03 19:00", "yyyy-MM-dd HH:mm", { zone = "Europe/London" }), moment)
            check("an offset in the text wins", nf.time.parse("2026-10-03 20:00 +02:00", "yyyy-MM-dd HH:mm XXX", utc), moment)
            check("a date alone is midnight", nf.time.parse("2026-10-03", "yyyy-MM-dd", utc), moment - 18 * 3600000)
            check("an hour alone", nf.time.parse("2026-10-03 6 PM", "yyyy-MM-dd h a", utc), moment)
            check("round trip", nf.time.parse(nf.time.format(moment, "dd MMM yyyy HH:mm", utc), "dd MMM yyyy HH:mm", utc), moment)
            check("not matching", nf.time.parse("tomorrow", "yyyy-MM-dd", utc), nil)
            check("no such day", nf.time.parse("2026-02-30", "yyyy-MM-dd", utc), nil)
            check("no such hour", nf.time.parse("2026-10-03 25:00", "yyyy-MM-dd HH:mm", utc), nil)

            fails("a bad pattern letter", function() nf.time.format(0, "yyyy-bb") end, "bad time pattern")
            fails("no date to parse", function() nf.time.parse("18:00", "HH:mm") end, "needs a year, month and day")
            fails("an unknown zone", function() nf.time.format(0, "HH", { zone = "Mars/Olympus" }) end, "no time zone \"Mars/Olympus\"")
            fails("a misspelled option", function() nf.time.format(0, "HH", { zones = "UTC" }) end, "zones")
            check("the server's zone by default", type(nf.time.format(0, "HH:mm")), "string")
            """
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `nf time writes and reads durations`() {
        val result = LuaChecks.run(
            """
            check("hms", nf.time.duration(3725000), "1h 2m 5s")
            check("days", nf.time.duration((3 * 24 + 4) * 3600000), "3d 4h")
            check("seconds", nf.time.duration(45999), "45s")
            check("under a second", nf.time.duration(999), "0s")
            check("zero", nf.time.duration(0), "0s")
            check("negative", nf.time.duration(-90000), "-1m 30s")
            check("smallest", nf.time.duration(math.mininteger):sub(1, 1), "-")

            check("compact", nf.time.parse_duration("1h30m"), 5400000)
            check("spaced", nf.time.parse_duration(" 1h 30m "), 5400000)
            check("any order", nf.time.parse_duration("30m1h"), 5400000)
            check("days", nf.time.parse_duration("2d"), 2 * 86400000)
            check("a fraction", nf.time.parse_duration("1.5h"), 5400000)
            check("milliseconds", nf.time.parse_duration("1s 250ms"), 1250)
            check("round trip", nf.time.parse_duration(nf.time.duration(93784000)), 93784000)
            check("empty", nf.time.parse_duration(""), nil)
            check("no unit", nf.time.parse_duration("90"), nil)
            check("an unknown unit", nf.time.parse_duration("3w"), nil)
            check("a unit twice", nf.time.parse_duration("1m 2m"), nil)
            check("negative", nf.time.parse_duration("-5s"), nil)
            check("words", nf.time.parse_duration("five minutes"), nil)
            check("too long", nf.time.parse_duration("99999999999999999d"), nil)
            """
        )
        assertEquals(LuaChecks.DONE, result)
    }

    /** xoshiro256** seeded by splitmix64, from the reference C, to hold the prelude's 64-bit Lua arithmetic to. */
    private fun reference(seed: Long, count: Int): List<Long> {
        var x = seed
        val s = LongArray(4) {
            x += -0x61c8864680b583ebL
            var z = x
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z xor (z ushr 31)
        }
        return List(count) {
            val result = java.lang.Long.rotateLeft(s[1] * 5, 7) * 9
            val t = s[1] shl 17
            s[2] = s[2] xor s[0]
            s[3] = s[3] xor s[1]
            s[1] = s[1] xor s[2]
            s[0] = s[0] xor s[3]
            s[2] = s[2] xor t
            s[3] = java.lang.Long.rotateLeft(s[3], 45)
            result
        }
    }

    @Test
    fun `a seeded generator is xoshiro256 starstar`() {
        // Over the whole range, `integer` hands back each word as it is, offset by mininteger.
        val expected = reference(42, 5).map { (it + Long.MIN_VALUE).toString() }
        val result = LuaChecks.run(
            """
            local rng = nf.random.new(42)
            local words = {}
            for i = 1, 5 do
              words[i] = string.format("%d", rng:integer(math.mininteger, math.maxinteger))
            end
            log(table.concat(words, " "))
            """
        )
        assertEquals(listOf(expected.joinToString(" "), "done"), result)
    }

    @Test
    fun `generators repeat for a seed and stay in range`() {
        val result = LuaChecks.run(
            """
            local a, b, c = nf.random.new(7), nf.random.new(7), nf.random.new(8)
            local same, different = true, false
            for _ = 1, 20 do
              local x, y, z = a:number(), b:number(), c:number()
              same = same and x == y
              different = different or x ~= z
            end
            check("same seed", same, true)
            check("another seed", different, true)
            check("its own type", tostring(a):match("^Random: ") ~= nil, true)
            check("not the same handle", a == b, false)

            local rng = nf.random.new(1)
            local seen, outside = {}, false
            for _ = 1, 300 do
              local roll = rng:integer(1, 6)
              seen[roll] = true
              outside = outside or roll < 1 or roll > 6 or math.type(roll) ~= "integer"
            end
            check("every face", #seen, 6)
            check("no other face", outside, false)
            check("one value", rng:integer(5, 5), 5)
            check("negative range", rng:integer(-3, -3), -3)
            local low, high = 1, 0
            for _ = 1, 300 do
              local n = rng:number()
              low, high = math.min(low, n), math.max(high, n)
            end
            check("from 0", low >= 0 and low < 0.05, true)
            check("below 1", high < 1 and high > 0.95, true)
            local n = rng:number(10, 20)
            check("a range", n >= 10 and n < 20, true)

            check("pick", ({ a = true, b = true })[rng:pick({ "a", "b" })], true)
            check("pick from nothing", rng:pick({}), nil)
            local list = { 1, 2, 3, 4, 5, 6, 7, 8 }
            rng:shuffle(list)
            local sum = 0
            for _, v in ipairs(list) do sum = sum + v end
            check("shuffle keeps everything", #list == 8 and sum == 36, true)

            local counts = { common = 0, rare = 0, never = 0 }
            for _ = 1, 400 do
              local key = rng:weighted({ common = 3, rare = 1, never = 0 })
              counts[key] = counts[key] + 1
            end
            check("odds", counts.common > 250 and counts.common < 350, true)
            check("weight 0", counts.never, 0)
            check("nothing to pick", rng:weighted({ none = 0 }), nil)
            -- The same seed picks the same keys however the table was built.
            local forward, backward = { a = 1, b = 1, c = 1, d = 1 }, {}
            for _, k in ipairs({ "d", "c", "b", "a" }) do backward[k] = 1 end
            local p, q = nf.random.new(3), nf.random.new(3)
            local agree = true
            for _ = 1, 20 do agree = agree and p:weighted(forward) == q:weighted(backward) end
            check("weighted repeats", agree, true)

            check("random seeds differ", nf.random.new():number() ~= nf.random.new():number(), true)

            fails("min over max", function() rng:integer(6, 1) end, "greater than max")
            fails("not whole", function() rng:integer(1.5, 3) end, "bad argument 'min'")
            fails("half a range", function() rng:number(1) end, "bad argument 'max'")
            fails("a negative weight", function() rng:weighted({ a = -1 }) end, "the weight of a")
            fails("a weight that isn't a number", function() rng:weighted({ a = "lots" }) end, "the weight of a")
            fails("with a dot", function() rng.number() end, "call Random methods with ':'")
            fails("a seed that isn't whole", function() nf.random.new(1.5) end, "bad argument 'seed'")
            """
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `noise is smooth, seeded and bounded, and ids are unique`() {
        val result = LuaChecks.run(
            """
            local low, high, jump = 1, -1, 0
            local previous = nf.random.noise2(0, 0, { scale = 0.05 })
            for i = 1, 500 do
              local n = nf.random.noise2(i * 0.37, i * 0.11, { scale = 0.05 })
              local m = nf.random.noise3(i * 0.37, i * 0.11, i * 0.53, { seed = 9 })
              low, high = math.min(low, n, m), math.max(high, n, m)
              if i <= 200 then
                local step = nf.random.noise2(i * 0.01, 0)
                jump = math.max(jump, math.abs(step - previous))
                previous = step
              end
            end
            check("bounded below", low >= -1, true)
            check("bounded above", high <= 1, true)
            check("spread out", low < -0.5 and high > 0.5, true)
            check("smooth", jump < 0.1, true)
            check("the same again", nf.random.noise3(1.5, 2.5, 3.5, { seed = 4 }), nf.random.noise3(1.5, 2.5, 3.5, { seed = 4 }))
            check("seeded", nf.random.noise2(10.3, 4.2, { seed = 1 }) ~= nf.random.noise2(10.3, 4.2, { seed = 2 }), true)
            check("scale", nf.random.noise2(2, 4, { scale = 0.5 }), nf.random.noise2(1, 2))
            check("far away", type(nf.random.noise2(1e12, -1e12)), "number")
            fails("not finite", function() nf.random.noise2(0 / 0, 0) end, "finite")
            fails("a misspelled option", function() nf.random.noise2(0, 0, { sead = 1 }) end, "sead")

            local id = nf.random.uuid()
            check("a uuid", id:match("^%x%x%x%x%x%x%x%x%-%x%x%x%x%-4%x%x%x%-[89ab]%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$") ~= nil, true)
            check("unique", id ~= nf.random.uuid(), true)
            """
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `nf math blends, clamps, remaps and eases`() {
        val result = LuaChecks.run(
            """
            check("lerp", nf.math.lerp(10, 20, 0.25), 12.5)
            check("lerp past the end", nf.math.lerp(0, 10, 1.5), 15)
            check("clamp low", nf.math.clamp(-5, 0, 10), 0)
            check("clamp high", nf.math.clamp(15, 0, 10), 10)
            check("clamp inside", nf.math.clamp(5, 0, 10), 5)
            check("remap", nf.math.remap(5, 0, 20, 0.5, 2), 0.875)
            check("remap reversed", nf.math.remap(0, 0, 10, 100, 0), 100)
            for _, easing in ipairs({ "linear", "step", "ease_in", "ease_out", "ease_in_out" }) do
              check(easing .. " at 0", nf.math.ease(easing, 0), 0)
              check(easing .. " at 1", nf.math.ease(easing, 1), 1)
            end
            check("ease_in", nf.math.ease("ease_in", 0.5), 0.25)
            check("ease_out", nf.math.ease("ease_out", 0.5), 0.75)
            check("ease_in_out", nf.math.ease("ease_in_out", 0.25), 0.125)
            check("step", nf.math.ease("step", 0.99), 0)
            check("clamped", nf.math.ease("linear", 2), 1)

            fails("clamp min over max", function() nf.math.clamp(1, 10, 0) end, "greater than max")
            fails("an empty range", function() nf.math.remap(1, 5, 5, 0, 1) end, "has no size")
            fails("an unknown curve", function() nf.math.ease("bounce", 0.5) end, "bad argument 'easing'")
            fails("not a number", function() nf.math.lerp(0, 1, "half") end, "bad argument 'fraction'")
            """
        )
        assertEquals(LuaChecks.DONE, result)
    }
}
