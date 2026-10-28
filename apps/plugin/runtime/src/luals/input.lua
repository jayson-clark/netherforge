---@meta input

-- What the prelude's entry (`prelude.lua`) is given by the Kotlin host, and hands to its
-- modules as `require("input")`. This file only describes it for lua-language-server
-- (`pnpm lint` checks the prelude); it is not shipped.

---@class nf.Limits The host's limits for scripts.
---@field hook_every integer VM instructions between two count hooks.
---@field deadline_every integer Hooks between two looks at the clock.
---@field memory_every integer Hooks between two memory checks.
---@field deadline_ms integer How long one call in may run.
---@field memory_mb integer How much memory scripts may hold together.
---@field max_string integer The longest string a script may build.
---@field max_work integer The most steps of work a capped library function may do.

---@class nf.Input
---@field prim table<string, function> The Kotlin primitives by name (`prim["Centity.play_animation"]`).
---@field bindings fun(core: table): nf.Bindings The generated bindings, which the `api` module runs.
---@field caps function The standard library's caps ("=nf/caps"), which the sandbox runs.
---@field limits nf.Limits

---@diagnostic disable-next-line: missing-fields
return {} --[[@as nf.Input]]
