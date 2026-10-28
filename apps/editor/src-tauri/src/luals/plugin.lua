-- NetherForge's lua-language-server plugin: `require` as the server resolves it.
--
-- On the server, a script of a resource (a centity, menu, dialog or item) finds
-- `require("lib.steps")` beside its script first (`centities/tower/lib/steps.lua`),
-- and only then in `modules/`. LuaLS has one search path for the whole project, so
-- a pattern like `?.lua` would match a `lib/steps.lua` in any folder. This hook
-- answers for a file inside a resource; everything else (and a name not found
-- beside the script) falls through to `runtime.path`, which reaches `modules/`.
--
-- Resources and modules may sit in group folders, `(name)`, which only sort them:
-- `centities/(towers)/tower/` is the centity `tower`, `modules/(social)/greeter/` the
-- module `greeter`. `runtime.path` can't see through those, so this hook finds a
-- module wherever it is (`modules=<folder>`), and a module's own files first, as
-- the server does.
--
-- The editor's backend writes this file into its data folder and the editor's
-- language client points LuaLS at it (`Lua.runtime.plugin`), with the scripted
-- kinds as arguments: `"centities=centity.json"`, one per folder whose resources
-- have a main file naming their script.
--
-- A package's module (`require("library:greetings")`) resolves to the stub the
-- editor writes for it (`packages=<folder>`, see `@netherforge/project-support`'s `luals.ts`): one per
-- file of each module a package the project depends on exports.

local files = require('files')
local furi = require('file-uri')
local json = require('json')
local util = require('utility')

local _, _, args = ...

--- `centities` → `centity.json`, from the arguments.
local mainFiles = {}
--- Where the editor writes its stubs of the packages' exported modules
--- (`packages=.netherforge/luals/packages`), relative to the project.
local packageStubs
--- The project's modules' folder (`modules=modules`).
local modulesFolder
for _, arg in ipairs(type(args) == 'table' and args or {}) do
    local folder, main = tostring(arg):match('^([%w_]+)=([%w_.]+)$')
    local stubs = tostring(arg):match('^packages=([%w_./]+)$')
    local modules = tostring(arg):match('^modules=([%w_]+)$')
    if stubs then
        packageStubs = stubs
    elseif modules then
        modulesFolder = modules
    elseif folder then
        mainFiles[folder] = main
    end
end

--- `centities/(towers)/tower/lib/x.lua` → `centities`, `centities/(towers)/tower`: the
--- kind's folder and the resource's, past any group folders; nil outside one.
local function resourceOf(relative)
    local kind, rest = relative:match('^([^/]+)/(.*)$')
    if not kind then
        return nil
    end
    local folder = kind
    while true do
        local group, more = rest:match('^(%([^/]*%))/(.*)$')
        if not group then
            break
        end
        folder, rest = folder .. '/' .. group, more
    end
    local id = rest:match('^([^/]+)/')
    if not id then
        return nil
    end
    return kind, folder .. '/' .. id, id
end

--- [file]'s path under the workspace [uri], decoded (a URI escapes a group's
--- parentheses), with `/` separators; nil outside it.
local function relativeOf(uri, file)
    local root, path = furi.decode(uri), furi.decode(file)
    if not root or not path then
        return nil
    end
    root, path = root:gsub('\\', '/'), path:gsub('\\', '/')
    if path:sub(1, #root + 1) ~= root .. '/' then
        return nil
    end
    return path:sub(#root + 2)
end

--- The URI of [relative], a path under the workspace [uri].
local function uriOf(uri, relative)
    return furi.encode(furi.decode(uri):gsub('\\', '/') .. '/' .. relative)
end

--- Module [id]'s folder under the project (`modules/(social)/greeter`), in whichever
--- group folder it is; nil when the workspace has none of its Lua.
local function moduleFolder(uri, id)
    for _, file in ipairs(files.getAllUris(uri)) do
        local relative = relativeOf(uri, file)
        if relative then
            local kind, folder, found = resourceOf(relative)
            if kind == modulesFolder and found == id then
                return folder
            end
        end
    end
    return nil
end

--- The first of [candidates] (paths under the project) that exists, but [except].
local function first(uri, candidates, except)
    for _, candidate in ipairs(candidates) do
        local found = uriOf(uri, candidate)
        if candidate ~= except and files.exists(found) then
            return { found }
        end
    end
    return nil
end

--- `require("library:greetings")` (a module the package `library` exports, or
--- `library:greetings.messages` for a file in it): the editor's stub of that
--- file. Nil when the package doesn't export it (requiring it is an error).
local function packageModule(uri, name)
    local namespace, module = name:match('^([%w_]+):([%w_][%w_.]*)$')
    if not namespace or not packageStubs or module:find('..', 1, true) or module:sub(-1) == '.' then
        return nil
    end
    local base = uri .. '/' .. packageStubs .. '/' .. namespace .. '/' .. module:gsub('%.', '/')
    for _, candidate in ipairs({ base .. '.lua', base .. '/init.lua' }) do
        if files.exists(candidate) then
            return { candidate }
        end
    end
    return nil
end

--- The script a resource's main file names (`script.file`), as a path under the project.
local function scriptOf(scopeUri, folder, main)
    local text = util.loadFile(furi.decode(uriOf(scopeUri, folder .. '/' .. main)))
    if not text then
        return nil
    end
    local ok, model = pcall(json.decode, text)
    if not ok or type(model) ~= 'table' or type(model.script) ~= 'table' then
        return nil
    end
    local file = model.script.file
    if type(file) ~= 'string' or file:find('..', 1, true) then
        return nil
    end
    return folder .. '/' .. file
end

---@param uri string the workspace's
---@param name string what's required
---@param suri string the requiring file's
---@return string[]?
function ResolveRequire(uri, name, suri)
    if not suri or suri:sub(1, #uri + 1) ~= uri .. '/' then
        return nil
    end
    if name:find(':', 1, true) then
        return packageModule(uri, name)
    end
    -- Dotted parts of letters, digits and `_`, as the server accepts.
    if not name:match('^[%w_]+$') and not name:match('^[%w_][%w_.]*[%w_]$') or name:find('..', 1, true) then
        return nil
    end
    local relative = relativeOf(uri, suri)
    local path = name:gsub('%.', '/')
    local kind, resource
    if relative then
        kind, resource = resourceOf(relative)
    end
    if kind and kind == modulesFolder then
        -- A module's own files come first.
        local found = first(uri, { resource .. '/' .. path .. '.lua', resource .. '/' .. path .. '/init.lua' })
        if found then
            return found
        end
    elseif kind and mainFiles[kind] then
        local script = scriptOf(uri, resource, mainFiles[kind])
        local folder = script and script:match('^(.*)/') or resource
        -- The server refuses the script itself: it's already running.
        local found = first(uri, { folder .. '/' .. path .. '.lua', folder .. '/' .. path .. '/init.lua' }, script)
        if found then
            return found
        end
    end
    -- A module, in whichever group folder it is: its init.lua, or a file in it.
    if not modulesFolder then
        return nil
    end
    local id, rest = name:match('^([%w_]+)%.?(.*)$')
    local module = moduleFolder(uri, id)
    if not module then
        return nil
    end
    if rest == '' then
        return first(uri, { module .. '/init.lua' })
    end
    rest = rest:gsub('%.', '/')
    return first(uri, { module .. '/' .. rest .. '.lua', module .. '/' .. rest .. '/init.lua' })
end
