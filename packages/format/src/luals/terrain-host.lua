---@meta terrain-host

-- What the Kotlin host (`TerrainScriptState.hostFunctions`) hands `terrain-glue.lua` as globals, which the glue
-- takes as locals and removes before any script runs. This file only describes them for lua-language-server
-- (`pnpm lint` checks the glue); it is not shipped.

---The noise [index] (the file's order, from 0) at (x, y) or (x, y, z).
---@param index integer
---@param x number
---@param y number
---@param z? number
---@return number
function host_noise(index, x, y, z) end

---Fills the box between two corners (both included) with palette entry [block], where it's in the chunk.
---@param x1 integer
---@param y1 integer
---@param z1 integer
---@param x2 integer
---@param y2 integer
---@param z2 integer
---@param block integer
function host_fill(x1, y1, z1, x2, y2, z2, block) end

---The palette entry at a place in the chunk, or -1 outside it.
---@param x integer
---@param y integer
---@param z integer
---@return integer
function host_block(x, y, z) end

---The file's own height of column (x, z).
---@param x integer
---@param z integer
---@return number
function host_file_height(x, z) end

---The area of column (x, z), as an index in the file's areas.
---@param x integer
---@param z integer
---@return integer
function host_area(x, z) end

---The area of the place (x, y, z), as an index in the file's areas.
---@param x integer
---@param y integer
---@param z integer
---@return integer
function host_point_area(x, y, z) end

---Sets loot table [table] (an index in the file's loot) on the container at a place in the chunk.
---@param x integer
---@param y integer
---@param z integer
---@param table integer
function host_set_loot(x, y, z, table) end

---A block id's palette entry, or below 0 when it names no block.
---@param block string
---@return integer
function host_resolve(block) end

---The project path a `require` of [name] from [caller] (a chunk's source) finds, or nil.
---@param name string
---@param caller string
---@return string?
function host_module_path(name, caller) end

---The script at [path], compiled (not run).
---@param path string
---@return function
function host_load(path) end

---The standard library's caps (`caps.lua`), compiled (not run): called with the library's own functions, the
---limits, `spend` and `refuse`, it returns the capped `string` and `table` functions and `setmetatable`.
---@return fun(raw: table, limits: table, spend: fun(steps: number), refuse: fun(message: string)): { string: table<string, function>, table: table<string, function>, setmetatable: function }
function host_caps() end
