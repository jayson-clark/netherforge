-- The event core: `:on` and `:once` on every evented handle and on `nf`,
-- event objects, bubbling, custom events, lifetimes and the handler error
-- policy. What each class may listen for comes from the generated registry
-- (`schema.events`, from the spec).
--
-- A subscription is filed under its target, the handle itself (or "nf" for
-- `nf.on`), which keeps the handle's table (and so its entry in the runtime's
-- handle table) alive while anything listens to it, and under its scope. It
-- goes when either does: the scope when its script unloads (`host.drop_env`)
-- or fails, the target when the thing is gone (`host.drop_targets`), so a
-- module's handler on a centity's node lives exactly as long as both. The
-- Kotlin side is told how many handlers each target has per event
-- (`events.listening`), so it can skip work nobody listens to: ticking a
-- centity, reporting a physics contact.
--
-- Handlers run synchronously, in registration order, each in its own scope's
-- budget frame. One that errors is reported (`events.failed`) and the rest
-- still run; after MAX_ERRORS in a row its subscription is cancelled. What a
-- handler returns means nothing: stop, cancel and the writable fields are how
-- it answers.

local std = require("std")
local input = require("input")
local handles = require("handles")
local gates = require("gates")
local schema = require("schema")
local guard_module = require("guard")

local prim = input.prim
local raw, format, concat, unpack, floor = std.raw, std.format, std.concat, std.unpack, std.floor
local math_type = std.math_type
local new, keys = handles.new, handles.keys
local gated, needs_version = gates.gated, gates.needs_version
local timing, invoke, current = guard_module.timing, guard_module.invoke, guard_module.current
local unbilled = guard_module.guard.unbilled
local pairs, ipairs, next, error = pairs, ipairs, next, error
local setmetatable, getmetatable = setmetatable, getmetatable

local registry, custom_events = schema.events, schema.custom_events
local NF = "nf"
local MAX_ERRORS = 20

-- What a scope's handlers run with: its budget, from host.new_env.
local budgets = {}
-- The package each scope's code is in (its namespace), from host.new_env: an
-- event's fields are spelled for the package whose handlers get them.
local namespaces = {}

local subscriptions = {} -- id -> subscription
local listeners = {} -- target (a handle, or "nf") -> event name -> subscriptions, oldest first
local scope_subscriptions = {} -- scope -> { [id] = true }
local next_subscription = 1

-- The class and listeners' key of a target: a handle, or nil for `nf`.
local function class_of(target)
  return target == nil and NF or getmetatable(target)
end

local function key_of(target)
  return target == nil and NF or target
end

local function event_names(class_name)
  local names = {}
  for name in pairs(registry[class_name] or {}) do
    names[#names + 1] = name
  end
  raw.sort(names)
  return concat(names, ", ")
end

-- A target's handlers for one event are a list, oldest first, that also
-- keeps what telling Kotlin needs, so neither subscribing nor cancelling walks
-- it: `live`, the active ones; `unrated`, the active ones without a rate;
-- `dead`, cancelled ones still in it (skipped, and dropped once they're half
-- of it); `rates`, every active rated one's "rate:phase" (nil when that must
-- be worked out again). A handler subscribing among a thousand others costs
-- what the first did.

---@class nf.HandlerList: table
---@field live integer
---@field unrated integer
---@field dead integer
---@field rates string?

-- Tells Kotlin how many handlers `event` has on `key`, and, when every one of
-- them has a rate, each one's "rate:phase", so it raises only the ticks one
-- is due on (nil: one runs every time).
local function notify(key, event)
  local list = listeners[key] and listeners[key][event]
  local rates = nil
  if list ~= nil and list.unrated == 0 then
    rates = list.rates
    if rates == nil then
      local parts = {}
      for _, sub in ipairs(list) do
        if sub.active then
          parts[#parts + 1] = sub.every .. ":" .. sub.phase
        end
      end
      rates = concat(parts, " ")
      list.rates = rates
    end
  end
  prim["events.listening"](key ~= NF and key or nil, event, list and list.live or 0, rates)
end

local function unsubscribe(sub)
  if not sub.active then
    return
  end
  sub.active = false
  subscriptions[sub.id] = nil
  local owned = scope_subscriptions[sub.scope]
  if owned ~= nil then
    owned[sub.id] = nil
  end
  local by_event = listeners[sub.key]
  local list = by_event and by_event[sub.event]
  if list ~= nil then
    list.live = list.live - 1
    if sub.every == nil then
      list.unrated = list.unrated - 1
    else
      list.rates = nil
    end
    list.dead = list.dead + 1
    if list.live == 0 then
      by_event[sub.event] = nil
      if next(by_event) == nil then
        listeners[sub.key] = nil
      end
    elseif list.dead > list.live then
      -- Half of it cancelled: a new list of the live ones. (Delivery walks a
      -- snapshot, so one running now isn't disturbed.)
      local kept = { live = list.live, unrated = list.unrated, dead = 0, rates = list.rates }
      for _, other in ipairs(list) do
        if other.active then
          kept[#kept + 1] = other
        end
      end
      by_event[sub.event] = kept
    end
  end
  notify(sub.key, sub.event)
end

-- The options `:on` was given, which the wrapper has checked against the spec's
-- `EventOptions` (a table of known fields): what's left is whether this event
-- takes them, and `every`'s range.
local function listen_options(options, spec, event, level)
  local every = options ~= nil and options.every or nil
  if every == nil then
    return nil
  end
  if spec == nil or not spec.options.every then
    error(format("option 'every' doesn't apply to the %s event", event), level)
  end
  if every < 1 then
    error("bad field 'options.every' (a whole number of ticks, at least 1, expected)", level)
  end
  return floor(every)
end

-- Registers `handler` for `event` on `target` (nil for nf), for `scope`.
-- `level` is where argument errors point, counted from here: the caller of
-- `on`/`once`, which call this as a statement (never a tail call), is 3.
local function subscribe(scope, target, event, handler, options, once, level)
  local class_name = class_of(target)
  local spec = registry[class_name] and registry[class_name][event]
  if spec == nil then
    local custom = raw.find(event, ":", 1, true) ~= nil
    if not (custom and custom_events[class_name]) then
      local where = class_name == NF and "nf.on" or class_name
      local hint = custom_events[class_name] and "; a custom event's name has a ':' in it"
        or (custom and "; only nf and Centity take custom events" or "")
      error(
        format('no event "%s" for %s (events: %s%s)', event, where, event_names(class_name), hint),
        level
      )
    end
  end
  local since = gated.events[class_name] and gated.events[class_name][event]
  if since ~= nil then
    error(needs_version(format('the event "%s"', event), since), level)
  end
  local every = options ~= nil and listen_options(options, spec, event, level + 1) or nil
  if scope == nil then
    error("no script is running to own this handler", level)
  end
  local id = next_subscription
  next_subscription = id + 1
  -- A handle to something already gone gets a subscription that never runs.
  if target ~= nil and not prim["events.alive"](target) then
    return new.Subscription(id)
  end
  local key = key_of(target)
  local sub = {
    id = id,
    scope = scope,
    class_name = class_name,
    key = key,
    event = event,
    fn = handler,
    once = once,
    every = every,
    -- A handler with a rate runs on the ticks where (tick + phase) % every == 0: phased by the
    -- thing it's on (so one centity's handlers agree and a crowd of them spreads), or by its id on nf.
    phase = every ~= nil and (target == nil and id or prim["events.phase"](target)) or nil,
    count = 0,
    errors = 0,
    active = true,
  }
  subscriptions[id] = sub
  local by_event = listeners[key]
  if by_event == nil then
    by_event = {}
    listeners[key] = by_event
  end
  local list = by_event[event]
  if list == nil then
    list = { live = 0, unrated = 0, dead = 0, rates = "" }
    by_event[event] = list
  end
  list[#list + 1] = sub
  list.live = list.live + 1
  if every == nil then
    list.unrated = list.unrated + 1
  elseif list.rates ~= nil then
    local rate = every .. ":" .. sub.phase
    list.rates = list.rates == "" and rate or list.rates .. " " .. rate
  end
  local owned = scope_subscriptions[scope]
  if owned == nil then
    owned = {}
    scope_subscriptions[scope] = owned
  end
  owned[id] = true
  notify(key, event)
  return new.Subscription(id)
end

local function running_scope()
  local f = current()
  return f and f.scope
end

-- ---- event objects
--
-- An event is an empty table whose state lives in a weak side table, so its
-- read-only members can't be assigned and its methods can't be replaced.
-- Fields are the payload's (for a custom event, the very table emit was
-- given); writes are checked against the event's writable fields.

local event_state = setmetatable({}, { __mode = "k" })
local Event = {}
local RESERVED = { name = true, current = true, cancelled = true, stopped = true }

-- An event's own members, which a script reads (`name`, `current`,
-- `cancelled`, `stopped`, the methods): kept in its state under the same
-- names, and in its view ([new_event]), which reads find first.
local function set(state, key, value)
  state[key] = value
  state.view[key] = value
end

local function event_self(self)
  local state = event_state[self]
  if state == nil then
    error("call Event methods with ':' on an event, like event:stop()", 3)
  end
  return state
end

function Event.stop(self)
  set(event_self(self), "stopped", true)
end

function Event.cancel(self)
  local state = event_self(self)
  if not state.cancellable then
    error(format("the %s event can't be cancelled", state.name), 2)
  end
  set(state, "cancelled", true)
end

function Event.uncancel(self)
  local state = event_self(self)
  if not state.cancellable then
    error(format("the %s event can't be cancelled", state.name), 2)
  end
  set(state, "cancelled", false)
end

local function writable_list(writable)
  local names = {}
  for name in pairs(writable) do
    names[#names + 1] = name
  end
  raw.sort(names)
  return #names > 0 and ("writable: " .. concat(names, ", ")) or "it has no writable fields"
end

local function event_newindex(ev, key, value)
  local state = event_state[ev]
  if RESERVED[key] or Event[key] ~= nil then
    error(format("event.%s can't be assigned", tostring(key)), 2)
  end
  local writable = state.writable
  if writable ~= true then
    if writable[key] == nil then
      error(
        format(
          "event.%s can't be assigned on a %s event (%s)",
          tostring(key),
          state.name,
          writable_list(writable)
        ),
        2
      )
    end
    -- Read as the payload's field will be read back, so a mistake (an item
    -- in a list included) is an error at this line rather than a dropped value later.
    prim["events.check"](state.owner, state.event, key, value, state.spelled)
  end
  state.fields[key] = value
end

local function event_pairs(ev)
  return next, event_state[ev].fields, nil
end

local function event_tostring(ev)
  return "Event: " .. event_state[ev].name
end

-- A new event over `fields`; `writable` is the registry's table, or true for
-- a custom event (anything may be assigned).
--
-- Handlers read events' fields all the time, so a read runs no Lua at all:
-- the event is an empty table whose `__index` is its view (its own members),
-- whose `__index` is its fields, a chain the VM follows itself. Every write
-- finds the event empty, so `__newindex` checks each.
local function new_event(name, fields, cancellable, writable)
  local view_mt = { __index = fields }
  local view = setmetatable({
    name = name,
    cancelled = false,
    stopped = false,
    stop = Event.stop,
    cancel = Event.cancel,
    uncancel = Event.uncancel,
  }, view_mt)
  local ev = setmetatable({}, {
    __index = view,
    __newindex = event_newindex,
    __pairs = event_pairs,
    __tostring = event_tostring,
    __metatable = "Event",
    __name = "Event",
  })
  local state = {
    name = name,
    fields = fields,
    cancellable = cancellable,
    writable = writable,
    cancelled = false,
    stopped = false,
    view = view,
    view_mt = view_mt,
  }
  event_state[ev] = state
  return ev, state
end

-- Hands the event's handlers [fields] from now on (spelled for another package).
local function set_fields(state, fields)
  state.fields = fields
  state.view_mt.__index = fields
end

-- ---- delivery

local function handler_failed(sub, message, file, line, trace)
  sub.errors = sub.errors + 1
  local gave_up = sub.errors >= MAX_ERRORS
  if gave_up then
    unsubscribe(sub)
  end
  local what = sub.event
    .. " handler"
    .. (sub.class_name == NF and "" or (" on a " .. sub.class_name))
  prim["events.failed"](sub.scope, what, message, file, line, trace, gave_up)
end

-- Every live handler for `event` on the target filed under `key`, oldest
-- first. Handlers registered meanwhile wait for the next time.
local function deliver(key, event, ev, state, only_scope)
  local by_event = listeners[key]
  local list = by_event and by_event[event]
  if list == nil then
    return
  end
  local snapshot = { unpack(list) }
  for _, sub in ipairs(snapshot) do
    if state.stopped then
      return
    end
    if
      sub.active
      and (only_scope == nil or sub.scope == only_scope)
      and (state.only == nil or namespaces[sub.scope] == state.only)
    then
      local run = true
      if sub.every ~= nil then
        local tick = state.fields.tick
        if math_type(tick) == "integer" then
          run = (tick + sub.phase) % sub.every == 0
        else
          -- Raised by hand without a tick (a test): counted instead.
          sub.count = sub.count + 1
          run = sub.count % sub.every == 0
        end
      end
      if run then
        if sub.once then
          unsubscribe(sub)
        end
        -- A server event's fields name things as the package whose handler runs next writes them.
        local namespace = namespaces[sub.scope]
        if state.spelled ~= nil and namespace ~= nil and namespace ~= state.spelled then
          set_fields(state, prim["events.respell"](state.fields, state.spelled, namespace))
          state.spelled = namespace
        end
        timing.next_kind = sub.event
        local status, message, file, line, trace = invoke(budgets[sub.scope], sub.scope, sub.fn, ev)
        if status == "error" then
          handler_failed(sub, message, file, line, trace)
        else
          sub.errors = 0
        end
      end
    end
  end
end

-- Makes a custom event and delivers it: what `emit_custom` has done unbilled.
local function deliver_custom(target, key, event, payload)
  local ev, state = new_event(event, payload or {}, true, true)
  set(state, "current", target)
  deliver(key, event, ev, state)
  return state.cancelled
end

-- A custom event (a name with a ':') on `target`, or on nf for nil.
local function emit_custom(target, event, payload, level)
  if not raw.find(event, ":", 1, true) then
    error(
      format(
        "\"%s\" is a built-in event name: only the server raises those; a custom event's name has a ':' in it",
        event
      ),
      level
    )
  end
  local key = key_of(target)
  -- Nobody listening: nothing to build an event for, and nothing can cancel it.
  if listeners[key] == nil or listeners[key][event] == nil then
    return false
  end
  return unbilled(deliver_custom, target, key, event, payload)
end
-- ---- the hand-written functions
--
-- Every evented class's `on`, `once` and (where it takes custom events) `emit`
-- are these same bodies: the generated wrapper checks `self` and the
-- arguments, and the class comes from the handle itself. Each assigns the
-- result to a local first: a tail call would take the body's frame away from
-- the error level `subscribe` counts.

local Subscription_body = {}

---@param self table
function Subscription_body.cancel(self)
  local sub = subscriptions[keys.Subscription(self)]
  if sub ~= nil then
    unsubscribe(sub)
  end
end

---@param self table
---@return boolean
function Subscription_body.is_active(self)
  local sub = subscriptions[keys.Subscription(self)]
  return sub ~= nil and sub.active
end

---@param self table
---@param event string
---@param handler function
---@param options {every: integer?}?
---@return table subscription
local function on(self, event, handler, options)
  local f = current()
  local subscription = subscribe(f and f.scope, self, event, handler, options, false, 3)
  return subscription
end

---@param self table
---@param event string
---@param handler function
---@return table subscription
local function once(self, event, handler)
  local f = current()
  local subscription = subscribe(f and f.scope, self, event, handler, nil, true, 3)
  return subscription
end

---@param self table
---@param event string
---@param payload table?
---@return boolean cancelled
local function emit(self, event, payload)
  local cancelled = emit_custom(self, event, payload, 3)
  return cancelled
end

-- Every scope's `nf` table, so `nf.wait_for(nf, ...)` can tell it from a handle.
---@type table<table, true>
local nf_tables = setmetatable({}, { __mode = "k" })

return {
  NF = NF,
  -- The debugger reads an event's name and fields without its metatable.
  event_state = event_state,
  set_event = set,
  registry = registry,
  custom_events = custom_events,
  budgets = budgets,
  namespaces = namespaces,
  subscriptions = subscriptions,
  listeners = listeners,
  scope_subscriptions = scope_subscriptions,
  nf_tables = nf_tables,
  class_of = class_of,
  key_of = key_of,
  unsubscribe = unsubscribe,
  subscribe = subscribe,
  running_scope = running_scope,
  new_event = new_event,
  deliver = deliver,
  emit_custom = emit_custom,
  Event = Event,
  Subscription_body = Subscription_body,
  on = on,
  once = once,
  emit = emit,
}
