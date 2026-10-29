local std = require("std")
local input = require("input")
local handles = require("handles")
local values = require("values")
local schema = require("schema")
local guard = require("guard").guard

local prim = input.prim
local raw, format, concat, math_type = std.raw, std.format, std.concat, std.math_type
local ids, parents, new = handles.ids, handles.parents, handles.new
local is_vec3, is_location = values.is_vec3, values.is_location
local new_vec3, new_location = values.new_vec3, values.new_location
local type, next, pairs, ipairs, tostring, tonumber = type, next, pairs, ipairs, tostring, tonumber
local getmetatable, rawget = getmetatable, rawget

---@type fun(value: any, path: string, out: string[], problems: table, seen: table, depth: integer): boolean
local encode_data
---@type fun(value: any): any
local decode_data
-- ---------------------------------------------------------------- JSON
--
-- The one Lua↔JSON codec: what `data()` tables and `Item.data` are saved as,
-- what `nf.json` and `File:read_json`/`write_json` read and write, and what the
-- tables `nf.menus.create` and `nf.dialogs.create` take are read as. Every
-- script value the runtime turns into JSON or back goes through here, so a
-- value means the same everywhere (a `Vec3` in `nf.json.encode` is the `Vec3`
-- a saved table keeps).
--
-- Plain values are JSON as they are: strings, booleans, numbers (an integer
-- stays an integer, a float a float), tables keyed by strings (objects) and
-- tables numbered from 1 (lists; a hole is `null`). The API's own values are
-- objects with one tagged key:
--
--   {"$vec3":[x,y,z]}
--   {"$location":[world,x,y,z,yaw,pitch]}  the world by name; yaw and pitch null when unset
--   {"$entity":[id]}, {"$centity":[id]}, {"$world":[name]}   a handle's key (a player is an entity)
--
-- A key of the script's own that would read as a tag ("$vec3", or "$$vec3")
-- is written with one more "$", so no table of a script's is ever read back
-- as a tag; any other key ("$ref", "$set") is written as it is, so JSON for
-- another service is what the script said. Object keys are written in
-- order, so the same table is always the same text (an unchanged table isn't
-- written again, and two items' data compare as text or as JSON).
--
-- Anything else is a problem at its key path (`data.inventory[3]: a function
-- can't be saved`): skipped and reported when a table is saved, an error
-- anywhere else.

do
  -- The handle classes a table may keep, by class, with their tags (`saveable`
  -- in the spec): each at the top of its chain, so a Player or a Mob is saved as
  -- the Entity it is (its key is its UUID) and comes back as whatever the server
  -- says it is.
  local SAVED_HANDLES = schema.saved_handles
  local TAGGED_HANDLES = {}
  -- Every tag, without its "$".
  local TAGS = { vec3 = true, location = true }
  for class_name, tag in pairs(SAVED_HANDLES) do
    TAGGED_HANDLES["$" .. tag] = class_name
    TAGS[tag] = true
  end

  -- Whether a key reads as a tag, with any number of "$" in front ("$vec3", "$$vec3").
  local function tag_like(key)
    local word = raw.match(key, "^%$+([%a_][%w_]*)$")
    return word ~= nil and TAGS[word] == true
  end

  local MAX_DATA_DEPTH = 64
  local huge = math.huge

  local json_escapes = {
    ['"'] = '\\"',
    ["\\"] = "\\\\",
    ["\b"] = "\\b",
    ["\f"] = "\\f",
    ["\n"] = "\\n",
    ["\r"] = "\\r",
    ["\t"] = "\\t",
  }

  local function json_escape(c)
    return json_escapes[c] or format("\\u%04x", c:byte())
  end

  -- What a JSON string escapes: ASCII's control characters, quotes and backslashes. Not
  -- `%c`, which is the C library's and, in a locale like the JVM's, takes the
  -- bytes 0x80-0x9F too: the middle of a UTF-8 character ("✓").
  local ESCAPED = '[\0-\31"\\\127]'

  local function json_string(s)
    if not raw.find(s, ESCAPED) then
      return '"' .. s .. '"'
    end
    return '"' .. raw.gsub(s, ESCAPED, json_escape) .. '"'
  end

  -- A string, boolean or integer as JSON (none of them can fail), or nil for anything else.
  local function json_scalar(value)
    local kind = type(value)
    if kind == "string" then
      return json_string(value)
    elseif kind == "boolean" then
      return value and "true" or "false"
    elseif math_type(value) == "integer" then
      return format("%d", value)
    end
    return nil
  end

  -- A number as JSON, or nil for one JSON can't hold (NaN, infinities). A
  -- float keeps a decimal point, so it comes back a float.
  local function json_number(n)
    if math_type(n) == "integer" then
      return format("%d", n)
    end
    if n ~= n or n == huge or n == -huge then
      return nil
    end
    local text = format("%.14g", n)
    if tonumber(text) ~= n then
      text = format("%.17g", n)
    end
    if not raw.find(text, "[%.eEn]") then
      text = text .. ".0"
    end
    return text
  end

  -- `data.items[3]`, `data.owner`, `data["two words"]`.
  local function key_path(path, key)
    if math_type(key) == "integer" then
      return path .. "[" .. format("%d", key) .. "]"
    elseif type(key) == "string" then
      if raw.match(key, "^[%a_][%w_]*$") then
        return path .. "." .. key
      end
      return path .. "[" .. format("%q", key) .. "]"
    end
    return path .. "[" .. tostring(key) .. "]"
  end

  local function a_or_an(word)
    return (raw.match(word, "^[AEIOUaeiou]") and "an " or "a ") .. word
  end

  -- What sorting `n` strings costs the calling script: about n log2 n
  -- comparisons, each a `strcoll` (the caps' measure) that no hook counts.
  local STEPS_PER_STRING_COMPARE = 150
  local SORT_FREE = 64

  local function sorted_strings(list)
    local n = #list
    if n > SORT_FREE then
      local compares = n * math.log(n, 2)
      local why = guard.charge_work(compares // 1, compares * STEPS_PER_STRING_COMPARE)
      if why ~= nil then
        error(why, 0)
      end
    end
    raw.sort(list)
    return list
  end

  -- Appends `value` as JSON to `out`; false (and a problem) when it can't be saved.
  function encode_data(value, path, out, problems, seen, depth)
    local kind = type(value)
    if kind == "string" then
      out[#out + 1] = json_string(value)
      return true
    elseif kind == "boolean" then
      out[#out + 1] = value and "true" or "false"
      return true
    elseif kind == "number" then
      local text = json_number(value)
      if text == nil then
        problems[#problems + 1] = path .. ": " .. tostring(value) .. " can't be " .. problems.verb
        return false
      end
      out[#out + 1] = text
      return true
    elseif kind ~= "table" then
      local what = kind == "thread" and "coroutine" or kind
      problems[#problems + 1] = path .. ": " .. a_or_an(what) .. " can't be " .. problems.verb
      return false
    end

    if is_vec3(value) or is_location(value) then
      local parts, numbers = {}, {}
      if is_vec3(value) then
        numbers = { value[1], value[2], value[3] }
      else
        local position = value[2]
        parts[1] = json_string((prim["handles.key"](value[1])))
        numbers = { position[1], position[2], position[3], value[3] or false, value[4] or false }
      end
      for _, n in ipairs(numbers) do
        local text = n == false and "null" or json_number(n)
        if text == nil then
          problems[#problems + 1] = path
            .. ": a vector with "
            .. tostring(n)
            .. " in it can't be "
            .. problems.verb
          return false
        end
        parts[#parts + 1] = text
      end
      local tag = is_vec3(value) and "$vec3" or "$location"
      out[#out + 1] = '{"' .. tag .. '":[' .. concat(parts, ",") .. "]}"
      return true
    end

    local protected = getmetatable(value)
    if protected ~= nil and type(protected) ~= "table" then
      local class_name = ids[value] ~= nil and protected or nil
      local tag
      local saved = class_name
      while saved ~= nil and tag == nil do
        tag = SAVED_HANDLES[saved]
        saved = parents[saved]
      end
      if tag ~= nil then
        local key = {}
        for i, part in ipairs({ prim["handles.key"](value) }) do
          key[i] = type(part) == "string" and json_string(part) or json_number(part)
        end
        out[#out + 1] = "{" .. json_string("$" .. tag) .. ":[" .. concat(key, ",") .. "]}"
        return true
      end
      local what = class_name and (class_name .. " handle")
        or (type(protected) == "string" and protected)
        or "protected table"
      problems[#problems + 1] = path .. ": " .. a_or_an(what) .. " can't be " .. problems.verb
      return false
    end

    if seen[value] then
      problems[#problems + 1] = path .. ": a table inside itself can't be " .. problems.verb
      return false
    end
    if depth >= MAX_DATA_DEPTH then
      problems[#problems + 1] = path .. ": tables nested this deep can't be " .. problems.verb
      return false
    end
    seen[value] = true
    -- A list when every key is a position from 1 (a few holes allowed, which
    -- come back as nil); otherwise an object, which takes string keys only.
    local positions, max, others, strings = 0, 0, 0, {}
    for key in next, value do
      if math_type(key) == "integer" and key > 0 then
        positions = positions + 1
        if key > max then
          max = key
        end
      elseif type(key) == "string" then
        strings[#strings + 1] = key
      else
        others = others + 1
      end
    end
    if positions > 0 and #strings == 0 and others == 0 and max <= 2 * positions then
      out[#out + 1] = "["
      for i = 1, max do
        if i > 1 then
          out[#out + 1] = ","
        end
        local item = rawget(value, i)
        -- A plain value needs no path: only what can fail is told where it was.
        local text = json_scalar(item)
        if text ~= nil then
          out[#out + 1] = text
        elseif
          item == nil or not encode_data(item, key_path(path, i), out, problems, seen, depth + 1)
        then
          out[#out + 1] = "null"
        end
      end
      out[#out + 1] = "]"
    else
      for key in next, value do
        if type(key) ~= "string" then
          local why = math_type(key) == "integer"
              and "a number key is saved only in a list numbered from 1"
            or (a_or_an(type(key)) .. " key can't be " .. problems.verb)
          problems[#problems + 1] = key_path(path, key) .. ": " .. why
        end
      end
      out[#out + 1] = "{"
      local first = true
      for _, key in ipairs(sorted_strings(strings)) do
        local mark = #out
        out[mark + 1] = (first and "" or ",")
          .. json_string(tag_like(key) and "$" .. key or key)
          .. ":"
        local item = rawget(value, key)
        local text = json_scalar(item)
        if text ~= nil then
          out[mark + 2] = text
          first = false
        elseif encode_data(item, key_path(path, key), out, problems, seen, depth + 1) then
          first = false
        else
          for i = #out, mark + 1, -1 do
            out[i] = nil
          end
        end
      end
      out[#out + 1] = "}"
    end
    seen[value] = nil
    return true
  end

  -- The saved form of one tagged value, or nil for a tag that isn't one of ours.
  local function revive(tag, parts)
    if type(parts) ~= "table" then
      return nil
    end
    if tag == "$vec3" then
      local x, y, z = parts[1], parts[2], parts[3]
      if type(x) == "number" and type(y) == "number" and type(z) == "number" then
        return new_vec3(x, y, z)
      end
    elseif tag == "$location" then
      local world, x, y, z = parts[1], parts[2], parts[3], parts[4]
      if
        type(world) == "string"
        and type(x) == "number"
        and type(y) == "number"
        and type(z) == "number"
      then
        return new_location(new.World(world), new_vec3(x, y, z), parts[5], parts[6])
      end
    else
      -- Each saved class is keyed by one string.
      local class_name = TAGGED_HANDLES[tag]
      if class_name ~= nil and type(parts[1]) == "string" then
        return new[class_name](parts[1])
      end
    end
    return nil
  end

  -- A table as it was read from JSON, with its tagged values made typed again.
  function decode_data(value)
    if type(value) ~= "table" then
      return value
    end
    local key, inner = next(value)
    if
      type(key) == "string"
      and next(value, key) == nil
      and key:sub(1, 1) == "$"
      and key:sub(2, 2) ~= "$"
    then
      local typed = revive(key, inner)
      if typed ~= nil then
        return typed
      end
    end
    local out = {}
    for k, v in next, value do
      if type(k) == "string" and k:sub(1, 2) == "$$" and tag_like(k) then
        k = k:sub(2)
      end
      out[k] = decode_data(v)
    end
    return out
  end
end

return { encode = encode_data, decode = decode_data }
