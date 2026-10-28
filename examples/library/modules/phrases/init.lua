-- What the library says. Not exported: the library's own modules require it,
-- and nothing outside the library can.
local phrases = {}

-- The library's own setting (its netherforge.json's settings): a server that
-- runs the library picks how it sounds. Read here, as this module loads, so a
-- change restarts it, and with it every module that required it.
phrases.opening = nf.config("style") == "casual" and "Hey" or "Well met"

return phrases
