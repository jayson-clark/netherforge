-- The sandbox's caps on the standard library. The prelude runs this once, as
-- the chunk "=nf/caps", with the library's own functions (`raw`), the host's
-- limits, and its `spend` and `refuse`. It returns the capped functions, which
-- the prelude puts into the real `string` and `table` tables, so the sandbox's
-- proxies and the string metatable (`("x"):rep(n)`) both reach them. The
-- prelude itself keeps calling `raw`.
--
-- Why: the instruction budget counts Lua's VM instructions, and a call to a C
-- function is one instruction however long it runs. `string.rep` with a huge
-- count, a pattern that backtracks, or a sort of a million values would stall
-- the server inside one instruction, where no hook can stop it. So each
-- function whose work isn't bounded by its arguments' size works out, before
-- calling the library, the most the call could cost:
--
--  - a string it makes: refused over `max_string` bytes (and `string.rep`'s
--    count too: `(""):rep(n)` loops n times making nothing);
--  - its work, in steps of about a nanosecond (measured: see the
--    plugin-sandbox skill): refused over `max_work`, so one call can't stall
--    the server for more than about a tenth of a second;
--  - and either is spent (`spend`) against the running call's next deadline
--    and memory checks, so a loop of calls each under the caps is still
--    stopped by the deadline, though each is a single instruction.
--
-- A refusal is an error at the script's line. So is an error the library
-- raises (a bad argument): the prelude runs this chunk without its debug
-- information, so the library, which locates its errors at its caller, finds
-- no line here and the message handler finds the script's.
--
-- A pattern's cost is its worst case, worked out from its shape (`steps`), so
-- a pattern that only backtracks on unlucky input is refused on a long enough
-- string even when the string at hand would have been quick; the message
-- says what to do instead.

local raw, limits, spend, refuse = ...

local type, tostring, tonumber, select, rawget, setmetatable, pcall =
  type, tostring, tonumber, select, rawget, setmetatable, pcall
local byte, sub, char = string.byte, string.sub, string.char
local format, find, gsub, gmatch, concat = raw.format, raw.find, raw.gsub, raw.gmatch, raw.concat
local pack, unpack = table.pack, table.unpack
local tointeger, log, max, floor, ult = math.tointeger, math.log, math.max, math.floor, math.ult
local raw_getmetatable = raw.getmetatable

local MAX_STRING = limits.max_string
local MAX_WORK = limits.max_work

-- Work worth spending (counting towards the next checks) rather than ignoring.
local SPEND_FROM = 4096

-- What each library function's work costs per unit, in steps (about a
-- nanosecond each), from the measurements in the plugin-sandbox skill.
local STEPS_PER_MOVE = 10 -- table.move, per element
local STEPS_PER_SHIFT = 8 -- table.insert and table.remove, per element shifted
local STEPS_PER_COMPARE = 15 -- table.sort, per comparison of numbers
local STEPS_PER_STRING_COMPARE = 150 -- and of strings (strcoll)
local BYTES_PER_PLAIN_STEP = 32 -- a plain find compares this many bytes a step (memcmp)

-- A plain find (`plain` set) is always cheap within these.
local PLAIN_SAFE, PLAIN_SAFE_NEEDLE = 2048, 32

local caps = { string = {}, table = {} }

-- At most how many bytes `v` is as text: a string's length, a number's
-- longest; 0 for anything else, which the library rejects itself.
local function size_of(v)
  local t = type(v)
  if t == "string" then
    return #v
  elseif t == "number" then
    return 24
  end
  return 0
end

local function bytes(n)
  if n >= 1048576 and n % 1048576 == 0 then
    return format("%d MB", n // 1048576)
  end
  return format("%d bytes", n)
end

local function too_big(what, size)
  refuse(
    format(
      "%s: the result would be %.0f bytes, over the limit of %s",
      what,
      size,
      bytes(MAX_STRING)
    )
  )
end

local function too_long(what, doing, work)
  refuse(
    format(
      "%s: %s could take up to %.2g steps, over the limit of %.2g",
      what,
      doing,
      work,
      MAX_WORK
    )
  )
end

-- Counts a string about to be made: refused when too big, spent when big.
local function making(what, size)
  if size > MAX_STRING then
    too_big(what, size)
  end
  if size > SPEND_FROM then
    spend(size)
  end
end

-- ---------------------------------------------------------------- patterns
--
-- A pattern's cost model follows the matcher in Lua's lstrlib.c. Matching
-- tries the pattern at each start in turn (one, if anchored); at each, it
-- works through the items, and a quantified item backtracks: `*` and `+`
-- take as many as they can, then give them back one by one while the rest
-- fails to match; `-` takes one more each time the rest fails; `?` tries with
-- and without. So, read from the last item back, with L the subject's
-- length and n an item's length in the pattern (what testing one character
-- against it costs):
--
--   F, the most a failing try of the rest can cost, and W, the most a
--   successful one can, besides the characters it takes (each tested once
--   by one item: `widest * L` over the whole call);
--   `fails`, whether the rest can fail at all, and `empty`, whether it can
--   match nothing at the end of the subject.
--
-- A quantified item multiplies F by up to L + 1 only when the rest can fail:
-- `[^,]+` at the end of a pattern takes its run and is done, and `.-` or `.*`
-- before a rest that matches nothing at the end can't fail either (`trim`'s
-- `^%s*(.-)%s*$`). That keeps the common patterns linear or quadratic in L
-- where they really are, and `.-.-.-.-b` at L^5, where it really is.
--
-- A quantified item gives back (or, lazy, steps over) only characters its own
-- class matched, so every try of the rest but one starts at such a character.
-- When the rest can only start with a character outside that class (`%s*` before
-- `[%w_]`, `[^"]*` before `"`), each of those tries fails at its first item:
-- a constant (`cheap`, the cost of reaching that item), not F. Such an item adds
-- L times that constant rather than multiplying F by L, which keeps patterns
-- like `^%[%s*([%w_]+)%s*"` linear.

local ONE, OPT, STAR, PLUS, LAZY, CAP, END, BAL, FRONT, REF = 0, 1, 2, 3, 4, 5, 6, 7, 8, 9
local ANY = 16 -- added to a quantifier's kind when its class is `.`, which matches anything

local compiled, compiled_count = {}, 0
local PATTERNS_KEPT = 256
local bounds, cheap_tries

-- The index just past the single-character class at `i`, or nil when it's malformed.
local function class_end(p, i, n)
  local c = byte(p, i)
  if c == 37 then -- %x
    if i + 1 > n then
      return nil
    end
    return i + 2
  elseif c == 91 then -- [set]
    local j = i + 1
    if byte(p, j) == 94 then
      j = j + 1
    end
    repeat
      if j > n then
        return nil
      end
      local cc = byte(p, j)
      j = j + 1
      if cc == 37 and j <= n then
        j = j + 1
      end
    until byte(p, j) == 93
    return j + 1
  end
  return i + 1
end

-- What `steps` below works out for any length, as a scale and a degree:
-- scale * (L + 1)^degree is at least `steps(c, L)` for every L, so a call on
-- a string short enough for it to be under the limit (nearly every call)
-- needs no more than a multiplication. It's `steps` with x = L + 1 standing
-- for both L and L + 1: a polynomial in x with no negative coefficients, so
-- at most the sum of its coefficients (its value at x = 1) times its highest
-- power. Both are worked out here, the value and the degree side by side.
-- `c.one_*` is for one match (`find`, `match`, an anchored `gsub`),
-- `c.every_*` for every match (`gmatch`, `gsub`).
function bounds(c, m)
  local F, W, dF, dW, fails, empty, widest = 0, 1, 0, 0, false, true, 1
  for i = #c - 1, 1, -2 do
    local kind, n = c[i], c[i + 1]
    local any = kind >= ANY
    if any then
      kind = kind - ANY
    end
    if n > widest then
      widest = n
    end
    if kind == ONE or kind == FRONT then
      F, W, fails, empty = fails and n + F or n, n + W, true, false
      dF = fails and dF or 0
    elseif kind == CAP then
      F, W = fails and F + 1 or F, W + 1
    elseif kind == END then
      F, W, dF, dW, fails, empty = 1, 1, 0, 0, true, true
    elseif kind == BAL or kind == REF then
      F, W, fails, empty = fails and 1 + F or 1, 1 + W, true, false
      dF, dW = max(1, dF), max(1, dW)
    elseif kind == OPT then
      local k = c.cheap[i]
      if fails and k then
        F, W = n + F + k, n + W + k
      elseif fails then
        F, W, dW = n + 2 * F, n + F + W, max(dF, dW)
      else
        W = n + W
      end
    elseif kind == STAR or kind == PLUS then
      local first = kind == PLUS and n or 0
      local k = c.cheap[i]
      if not fails or (any and empty) then
        F, W, dF, fails = first, first + W, 0, kind == PLUS
      elseif k then
        F, W = first + n + F + k, first + n + k + W
        dF, dW = max(dF, 1), max(dW, 1)
      else
        F, W = first + n + F, first + n + F + W
        dF, dW = dF + 1, max(dF + 1, dW)
      end
      empty = kind == STAR and empty
    elseif fails then
      local k = c.cheap[i]
      if any and empty then
        F, W, dF, dW, fails = 0, F + n + W, 0, max(dF + 1, dW), false
      elseif k then
        F, W, dF, dW = F + n + k, n + k + W, max(dF, 1), max(dW, 1)
      else
        F, W, dF, dW = F + n, F + n + W, dF + 1, max(dF + 1, dW)
      end
    end
  end
  if c.anchored then
    c.one_scale, c.one_degree = F + W + widest, max(dF, dW, 1)
  else
    c.one_scale, c.one_degree = F + W + widest, max(dF + 1, dW, 1)
  end
  -- gmatch reads a leading "^" as itself: one more character to test at every start.
  local caret = c.anchored and 1 or 0
  c.every_scale, c.every_degree = max(F, W) + caret + widest, max(dF, dW) + 1
  -- The longest strings each function is cheap on (under SPEND_FROM steps):
  -- what its call checks first.
  local function safe(scale, degree)
    return floor((SPEND_FROM / scale) ^ (1 / degree)) - 1
  end
  c.match_safe = safe(c.one_scale, c.one_degree)
  c.every_safe = safe(c.every_scale, c.every_degree)
  c.gsub_safe = c.anchored and c.match_safe or c.every_safe
  if c.plain then
    c.find_safe = floor(SPEND_FROM / (1 + m / BYTES_PER_PLAIN_STEP)) + m - 1
  else
    c.find_safe = c.match_safe
  end
end

-- Every byte, for listing the bytes a class matches.
local EVERY_BYTE
do
  local all = {}
  for b = 0, 255 do
    all[b + 1] = char(b)
  end
  EVERY_BYTE = concat(all)
end

-- The bytes single-character class `class` matches, as a string; nil when it can't be worked out.
local function members(class)
  local ok, found = pcall(function()
    local list = {}
    for m in gmatch(EVERY_BYTE, class) do
      list[#list + 1] = m
    end
    return concat(list)
  end)
  return ok and found or nil
end

-- Whether no byte class `class` matches is matched by any of the classes in `lead`.
local function disjoint(class, lead)
  local matched = members(class)
  if matched == nil then
    return false
  end
  for k = 1, #lead do
    local ok, at = pcall(find, matched, lead[k])
    if not ok or at ~= nil then
      return false
    end
  end
  return true
end

-- For each quantified item whose rest can only start with a character the item
-- can't match, what a try of the rest costs at one it can: the items it gets
-- through before it fails. By the item's kind's index; the rest are left out.
-- Read from the last item back, `lead` is the classes the rest could start
-- with: the optional items up to the first it must match (none for `$`,
-- which fails anywhere but the end), and `lead` false when one of them could
-- start with anything (`.`, `%1`, `%b`, a mistake).
function cheap_tries(c, classes)
  local cheap = {}
  ---@type string[]|false
  local lead
  local cost, closed = 0, false
  lead = {}
  for i = #c - 1, 1, -2 do
    local kind, n = c[i], c[i + 1]
    local class = classes[i]
    if kind == STAR or kind == PLUS or kind == LAZY or kind == OPT then
      if closed and lead and class and disjoint(class, lead) then
        cheap[i] = cost
      end
    end
    local base = kind >= ANY and kind - ANY or kind
    if base == CAP or base == FRONT then
      cost = cost + n
    elseif base == STAR or base == LAZY or base == OPT then
      if lead and class then
        lead[#lead + 1] = class
      else
        lead = false
      end
      cost = cost + n
    elseif base == END then
      lead, cost, closed = {}, 1, true
    elseif base == ONE or base == PLUS then
      lead, cost, closed = class and { class } or false, n, true
    else -- %b, %1, a quantified `.`: could start with anything
      lead, cost, closed = false, n, true
    end
  end
  return cheap
end

-- `p` as the cost model reads it: { kind, length, kind, length, ... } with
-- `anchored` (a leading "^") and `plain` (no special characters: `find` then
-- searches for the bytes). A malformed pattern is the library's to report,
-- when matching gets to the mistake: what comes before it is costed as
-- usual, and the rest as one plain item. Kept per pattern, a few hundred at a
-- time.
local function compile(p)
  local c = compiled[p]
  if c ~= nil then
    return c
  end
  local n = #p
  c = { anchored = byte(p, 1) == 94, plain = find(p, "[%^%$%*%+%?%.%(%[%%%-]") == nil }
  local i = c.anchored and 2 or 1
  -- Each item's class as pattern text (nil: it could be any character), by its kind's index.
  local classes = {}
  local function item(kind, length, class)
    local last = #c
    if kind == ONE and last > 0 and c[last - 1] == ONE then
      c[last] = c[last] + length
    else
      c[last + 1], c[last + 2] = kind, length
      classes[last + 1] = class
    end
  end
  while i <= n do
    local ch, next_ch = byte(p, i), byte(p, i + 1)
    if ch == 40 then -- "(" or "()"
      i = i + (next_ch == 41 and 2 or 1)
      item(CAP, 1)
    elseif ch == 41 then
      i = i + 1
      item(CAP, 1)
    elseif ch == 36 and i == n then
      i = i + 1
      item(END, 1)
    elseif ch == 37 and next_ch == 98 then -- %bxy
      if i + 3 > n then
        item(ONE, n - i + 1)
        break
      end
      i = i + 4
      item(BAL, 4)
    elseif ch == 37 and next_ch == 102 then -- %f[set]
      local j = byte(p, i + 2) == 91 and class_end(p, i + 2, n)
      if not j then
        item(ONE, n - i + 1)
        break
      end
      item(FRONT, j - i)
      i = j
    elseif ch == 37 and next_ch ~= nil and next_ch >= 48 and next_ch <= 57 then -- %1
      i = i + 2
      item(REF, 2)
    else
      local j = class_end(p, i, n)
      if j == nil then
        item(ONE, n - i + 1)
        break
      end
      local q = byte(p, j)
      local any = (ch == 46 and j == i + 1) and ANY or 0
      local class = any == 0 and sub(p, i, j - 1) or nil
      if q == 63 then
        item(OPT + any, j - i + 1, class)
        i = j + 1
      elseif q == 42 then
        item(STAR + any, j - i + 1, class)
        i = j + 1
      elseif q == 43 then
        item(PLUS + any, j - i + 1, class)
        i = j + 1
      elseif q == 45 then
        item(LAZY + any, j - i + 1, class)
        i = j + 1
      else
        item(ONE, j - i, class)
        i = j
      end
    end
  end
  c.cheap = cheap_tries(c, classes)
  bounds(c, n)
  if compiled_count >= PATTERNS_KEPT then
    compiled, compiled_count = {}, 0
  end
  compiled[p] = c
  compiled_count = compiled_count + 1
  return c
end

-- The most steps matching compiled pattern `c` against `L` bytes could take:
-- for one match (`find`, `match`, an anchored `gsub`) or, when `every`, for
-- every match there is (`gmatch`, `gsub`).
local function steps(c, L, every)
  L = L + 0.0 -- floats: a bound past 2^63 must not wrap around
  local F, W, fails, empty, widest = 0, 1, false, true, 1
  for i = #c - 1, 1, -2 do
    local kind, n = c[i], c[i + 1]
    local any = kind >= ANY
    if any then
      kind = kind - ANY
    end
    if n > widest then
      widest = n
    end
    if kind == ONE or kind == FRONT then
      F, W, fails, empty = fails and n + F or n, n + W, true, false
    elseif kind == CAP then
      F, W = fails and F + 1 or F, W + 1
    elseif kind == END then
      F, W, fails, empty = 1, 1, true, true
    elseif kind == BAL or kind == REF then
      F, W, fails, empty = fails and L + F or L, L + W, true, false
    elseif kind == OPT then
      local k = c.cheap[i]
      if fails and k then
        -- Without the item, the rest starts at a character the item matched.
        F, W = n + F + k, n + W + k
      elseif fails then
        F, W = n + 2 * F, n + F + W
      else
        W = n + W
      end
    elseif kind == STAR or kind == PLUS then
      local first = kind == PLUS and n or 0
      local k = c.cheap[i]
      if not fails or (any and empty) then
        -- The rest matches wherever the run ends: the run is all taken.
        F, W, fails = first, first + W, kind == PLUS
      elseif k then
        -- Only the try after the whole run can get past the rest's first item.
        F, W = first + n * L + F + L * k, first + n * L + L * k + W
      else
        F, W = first + n * L + (L + 1) * F, first + n * L + L * F + W
      end
      empty = kind == STAR and empty
    elseif fails then -- LAZY, before a rest that can fail
      local k = c.cheap[i]
      if any and empty then
        F, W, fails = 0, L * (F + n) + W, false
      elseif k then
        F, W = F + L * (k + n), L * (k + n) + W
      else
        F, W = (L + 1) * (F + n), L * (F + n) + W
      end
    end
  end
  if every then
    local caret = c.anchored and 1 or 0
    return (L + 1) * ((F > W and F or W) + caret) + widest * L
  end
  return (c.anchored and 1 or L + 1) * F + W + widest * L
end

local function shown(p)
  if #p > 40 then
    p = sub(p, 1, 37) .. "..."
  end
  return format("%q", p)
end

-- Refuses matching `p` against `L` bytes when it could take too long, and spends what it may take.
local function matching(what, c, p, L, every)
  local work
  if every then
    work = c.every_scale * (L + 1.0) ^ c.every_degree
  else
    work = c.one_scale * (L + 1.0) ^ c.one_degree
  end
  if work > MAX_WORK then
    work = steps(c, L, every)
  end
  if work > MAX_WORK then
    refuse(
      format(
        "%s: the pattern %s could take up to %.2g steps on %d bytes, over the limit of %.2g "
          .. "(match a shorter string, or %s the pattern)",
        what,
        shown(p),
        work,
        L,
        MAX_WORK,
        c.anchored and "simplify" or "anchor or simplify"
      )
    )
  end
  if work > SPEND_FROM then
    spend(work)
  end
end

-- Where in `s` (of length `L`) a search from `init` starts, as the library
-- works it out; nil when `init` isn't an integer (the library says so).
local function start_at(init, L)
  if init == nil then
    return 1
  end
  local i = tointeger(init)
  if i == nil then
    return nil
  elseif i > 0 then
    return i
  elseif i == 0 or i < -L then
    return 1
  end
  return L + i + 1
end

local function search(what, s, p, init, plain)
  if type(s) ~= "string" or type(p) ~= "string" then
    return
  end
  local L = #s
  local from = start_at(init, L)
  if from == nil or from > L + 1 then
    return
  end
  local span = L - from + 1
  local c = not plain and compile(p)
  if not c or c.plain then
    local m = #p
    if m > 0 and span >= m then
      local work = (span - m + 1.0) * (1 + m / BYTES_PER_PLAIN_STEP)
      if work > MAX_WORK then
        too_long(what, format("looking for %d bytes in %d", m, span), work)
      end
      if work > SPEND_FROM then
        spend(work)
      end
    end
  else
    matching(what, c, p, span, false)
  end
end

-- Each pattern function first compares the string's length with the
-- longest the pattern (seen before) is cheap on, which is all nearly every
-- call needs, and works the cost out only when it's longer.

function caps.string.find(s, p, init, plain)
  if plain then
    if
      type(s) == "string"
      and type(p) == "string"
      and #s <= PLAIN_SAFE
      and #p <= PLAIN_SAFE_NEEDLE
    then
      return raw.find(s, p, init, plain)
    end
  else
    local c = compiled[p]
    if c ~= nil and type(s) == "string" and #s <= c.find_safe then
      return raw.find(s, p, init)
    end
  end
  search("string.find", s, p, init, plain)
  return raw.find(s, p, init, plain)
end

function caps.string.match(s, p, init)
  local c = compiled[p]
  if c ~= nil and type(s) == "string" and #s <= c.match_safe then
    return raw.match(s, p, init)
  end
  search("string.match", s, p, init, false)
  return raw.match(s, p, init)
end

function caps.string.gmatch(s, p, init)
  local c = compiled[p]
  if c ~= nil and type(s) == "string" and #s <= c.every_safe then
    return raw.gmatch(s, p, init)
  end
  if type(s) == "string" and type(p) == "string" then
    local L = #s
    local from = start_at(init, L)
    if from ~= nil then
      matching("string.gmatch", compile(p), p, max(L - from + 1, 0), true)
    end
  end
  return raw.gmatch(s, p, init)
end

function caps.string.gsub(s, p, repl, n)
  local c = compiled[p]
  if
    c ~= nil
    and type(s) == "string"
    and #s <= c.gsub_safe
    and type(repl) == "string"
    and (#s + 1) * (#repl + 1) <= SPEND_FROM
    and find(repl, "%", 1, true) == nil
  then
    return raw.gsub(s, p, repl, n)
  end
  if type(s) ~= "string" or type(p) ~= "string" then
    return raw.gsub(s, p, repl, n)
  end
  c = c or compile(p)
  local L = #s
  local most = n == nil and L + 1 or tointeger(n)
  if most ~= nil then
    matching("string.gsub", c, p, L, not c.anchored)
    if most > L + 1 then
      most = L + 1
    elseif most < 0 then
      most = 0
    end
    if c.anchored and most > 1 then
      most = 1
    end
  end
  local kind = type(repl)
  if (kind == "string" or kind == "number") and most ~= nil then
    -- Each match adds the replacement, whose captures (%0 to %9) add up to
    -- at most the subject, once per reference: matches don't overlap.
    local text = tostring(repl)
    local refs = select(2, gsub(text, "%%", ""))
    making("string.gsub", L + most * (#text + 0.0) + refs * L)
  elseif kind == "function" or kind == "table" then
    -- What the replacements return is only known as they return it.
    local total = L
    local function counted(...)
      local value
      if kind == "function" then
        value = repl(...)
      else
        value = repl[(...)]
      end
      total = total + size_of(value)
      if total > MAX_STRING then
        too_big("string.gsub", total)
      end
      return value
    end
    return raw.gsub(s, p, counted, n)
  end
  return raw.gsub(s, p, repl, n)
end

-- ---------------------------------------------------------------- making strings

function caps.string.rep(s, n, sep)
  if
    sep == nil
    and type(s) == "string"
    and type(n) == "number"
    and n <= SPEND_FROM
    and #s * n <= SPEND_FROM
  then
    return raw.rep(s, n)
  end
  local count = tointeger(n)
  if count ~= nil and count > 0 then
    local size = count * (size_of(s) + 0.0) + (count - 1) * (sep == nil and 0 or size_of(sep))
    if size > MAX_STRING then
      too_big("string.rep", size)
    elseif count > MAX_STRING then
      -- It loops once a time, even copying nothing.
      refuse(format("string.rep: repeating %d times is over the limit of %d", count, MAX_STRING))
    end
    making("string.rep", size > count and size or count + 0.0)
  end
  return raw.rep(s, n, sep)
end

-- The longest a number converts to: a float with 99 digits of precision
-- (lstrlib's MAX_ITEMF, 110 + the largest exponent) in a 99-wide field.
local NUMBER_TEXT = 520

-- What a format string makes, kept per format string: `fixed`, the most its
-- own text and its number conversions come to, and in its array part the
-- arguments whose size depends on their text: `%s` by its argument's
-- position, `%q` by its position negated. A spec the library would refuse
-- ends it: the library stops there too.
local formats, formats_count = {}, 0

local function format_spec(fmt)
  local spec = { fixed = #fmt + 0.0 }
  local i, arg = 1, 0
  while true do
    local at = find(fmt, "%", i, true)
    if at == nil then
      break
    end
    if byte(fmt, at + 1) == 37 then
      i = at + 2
    else
      local _, spec_end = find(fmt, "^[-+ #0]*%d*%.?%d*", at + 1)
      local letter = byte(fmt, spec_end + 1)
      if letter == nil then
        break
      end
      arg = arg + 1
      if letter == 115 then
        spec[#spec + 1] = arg
      elseif letter == 113 then
        spec[#spec + 1] = -arg
        spec.fixed = spec.fixed + 2
      else
        spec.fixed = spec.fixed + NUMBER_TEXT
      end
      i = spec_end + 2
    end
  end
  if formats_count >= PATTERNS_KEPT then
    formats, formats_count = {}, 0
  end
  formats[fmt] = spec
  formats_count = formats_count + 1
  return spec
end

function caps.string.format(fmt, ...)
  if type(fmt) ~= "string" then
    return raw.format(fmt, ...)
  end
  local spec = formats[fmt] or format_spec(fmt)
  local size = spec.fixed
  local args
  for k = 1, #spec do
    local at = spec[k]
    if at > 0 then -- %s: a string, a number, or the tostring of anything else
      local value = args ~= nil and args[at] or (select(at, ...))
      local kind = type(value)
      if kind == "string" then
        size = size + #value
      elseif kind == "number" or kind == "nil" or kind == "boolean" then
        size = size + 24
      else
        -- A __tostring or __name could make text of any size: made here, measured, and passed on.
        if args == nil then
          args = pack(...)
        end
        value = tostring(value)
        args[at] = value
        size = size + #value
      end
    else -- %q: escapes take up to four bytes for one
      size = size + 4 * size_of((select(-at, ...)))
    end
  end
  if size > SPEND_FROM then
    making("string.format", size)
  end
  if args ~= nil then
    return raw.format(fmt, unpack(args, 1, args.n))
  end
  return raw.format(fmt, ...)
end

function caps.string.pack(fmt, ...)
  if type(fmt) == "string" then
    -- Each option is at most its size (`c1000`, `i16`, `!8`), or 16 bytes by default and alignment.
    local size = #fmt * 16.0
    for digits in gmatch(fmt, "%d+") do
      size = size + tonumber(digits)
    end
    local args = pack(...)
    for k = 1, args.n do
      size = size + size_of(args[k])
    end
    making("string.pack", size)
  end
  return raw.pack(fmt, ...)
end

local function lengths(t, n)
  local size = 0
  for k = 1, n do
    size = size + #t[k]
  end
  return size
end

-- A table with a metatable can answer `#t` and `t[k]` differently each time
-- they're asked (`__len`, `__index`), so whatever a cap measures, the library
-- would read something else when it runs: a length of 1 measured, then two
-- billion shifted in C where no hook fires. Such a table is read once, here,
-- where the hook counts every read, and the library gets what was read.

-- The length of `t` as the library would take it (`luaL_len`): an integer, or an error.
---@param t table
---@param what string
---@return integer
local function length_of(t, what)
  local n = tointeger(#t)
  if n == nil then
    refuse(what .. ": object length is not an integer")
    return 0 -- never reached: `refuse` raises
  end
  return n
end

local function has_metatable(t)
  return type(t) == "table" and raw_getmetatable(t) ~= nil
end

function caps.table.concat(t, sep, i, j)
  if has_metatable(t) then
    local from = i == nil and 1 or tointeger(i)
    local to = j == nil and length_of(t, "table.concat") or tointeger(j)
    if from ~= nil and to ~= nil then
      local copy, n = {}, 0
      for k = from, to do
        n = n + 1
        copy[n] = t[k]
      end
      return caps.table.concat(copy, sep, 1, n)
    end
    return raw.concat(t, sep, i, j)
  end
  if i == nil and j == nil and type(t) == "table" then
    -- A long list of strings is summed with nothing but `#` (a number among
    -- them stops that, and the loop below goes element by element).
    local n = #t
    local ok, size
    if n <= 8 then
      ok, size = true, 0
      for k = 1, n do
        size = size + size_of(t[k])
      end
    else
      ok, size = pcall(lengths, t, n)
    end
    if ok then
      if sep ~= nil then
        size = size + (n - 1) * size_of(sep)
      end
      if size > SPEND_FROM then
        making("table.concat", size)
      end
      return raw.concat(t, sep)
    end
  end
  if type(t) == "table" then
    local from = i == nil and 1 or tointeger(i)
    local to = j == nil and #t or tointeger(j)
    if from ~= nil and to ~= nil and to >= from then
      local size = (to - from + 0.0) * (sep == nil and 0 or size_of(sep))
      -- Each element is read to size the result: a big concat costs instructions, not just time.
      for k = from, to do
        local value = t[k]
        local kind = type(value)
        if kind == "string" then
          size = size + #value
        elseif kind == "number" then
          size = size + 24
        else
          break -- the library says what's wrong with it
        end
      end
      making("table.concat", size)
    end
  end
  return raw.concat(t, sep, i, j)
end

-- ---------------------------------------------------------------- tables

local function working(what, doing, work)
  if work > MAX_WORK then
    too_long(what, doing, work)
  end
  if work > SPEND_FROM then
    spend(work)
  end
end

function caps.table.move(a1, f, e, t, a2)
  local first, last = tointeger(f), tointeger(e)
  if first ~= nil and last ~= nil and last >= first then
    local count = last - first + 1.0
    working("table.move", format("moving %.0f elements", count), count * STEPS_PER_MOVE)
  end
  return raw.move(a1, f, e, t, a2)
end

function caps.table.insert(t, ...)
  if select("#", ...) == 2 and has_metatable(t) then
    -- lstrlib's tinsert, read once (see `length_of`).
    local pos, value = ...
    local e = length_of(t, "table.insert") + 1
    local at = tointeger(pos)
    if at == nil then
      return raw.insert(t, pos, value) -- the library says what's wrong with it
    end
    if not ult(at - 1, e) then
      refuse("bad argument #2 to 'insert' (position out of bounds)")
    end
    for k = e, at + 1, -1 do
      t[k] = t[k - 1]
    end
    t[at] = value
    return
  end
  if select("#", ...) == 2 and type(t) == "table" then -- appending (one argument) shifts nothing
    local at = tointeger((...))
    if at ~= nil then
      local shifted = #t - at + 1
      if shifted > 0 then
        working("table.insert", format("shifting %d elements", shifted), shifted * STEPS_PER_SHIFT)
      end
    end
  end
  return raw.insert(t, ...)
end

function caps.table.remove(t, pos)
  if has_metatable(t) then
    -- lstrlib's tremove, read once (see `length_of`).
    local size = length_of(t, "table.remove")
    local at = size
    if pos ~= nil then
      local given = tointeger(pos)
      if given == nil then
        return raw.remove(t, pos) -- the library says what's wrong with it
      end
      if given ~= size and ult(size, given - 1) then
        refuse("bad argument #2 to 'remove' (position out of bounds)")
      end
      at = given
    end
    local value = t[at]
    while at < size do
      t[at] = t[at + 1]
      at = at + 1
    end
    t[at] = nil
    return value
  end
  if pos ~= nil and type(t) == "table" then
    local at = tointeger(pos)
    if at ~= nil then
      local shifted = #t - at
      if shifted > 0 then
        working("table.remove", format("shifting %d elements", shifted), shifted * STEPS_PER_SHIFT)
      end
    end
  end
  return raw.remove(t, pos)
end

function caps.table.sort(t, comp)
  if has_metatable(t) then
    -- Sorted as a plain copy, read and written back once each (see `length_of`).
    local n = length_of(t, "table.sort")
    local copy = {}
    for k = 1, n do
      local value = t[k]
      if value == nil then
        refuse(format("table.sort: the table has nil at position %d of %d", k, n))
      end
      copy[k] = value
    end
    caps.table.sort(copy, comp)
    for k = 1, n do
      t[k] = copy[k]
    end
    return
  end
  -- A comparator is Lua, which the budget counts; so are `__lt` metamethods.
  if comp == nil and type(t) == "table" then
    local n = #t
    if n > 1 then
      local per = type(t[1]) == "string" and STEPS_PER_STRING_COMPARE or STEPS_PER_COMPARE
      working("table.sort", format("sorting %d values", n), n * log(n, 2) * per)
    end
  end
  return raw.sort(t, comp)
end

-- ---------------------------------------------------------------- finalizers

-- A finalizer (`__gc`) runs whenever the collector gets to it: inside
-- whichever script's call happens to be running, on that script's budget,
-- with its errors swallowed. Scripts can't have them.
function caps.setmetatable(t, mt)
  if type(mt) == "table" and rawget(mt, "__gc") ~= nil then
    refuse(
      "setmetatable: __gc isn't allowed: a finalizer would run inside whichever script's call the "
        .. "garbage collector happens to run in"
    )
  end
  return setmetatable(t, mt)
end

return caps
