-- Whether a world was made with `keep_spawn_loaded`: the chunks around its spawn are held loaded whenever it is (Paper
-- 26.x's own setting does nothing since Minecraft dropped spawn chunks), so loading it again holds them again.
ALTER TABLE worlds ADD COLUMN keep_spawn_loaded INTEGER NOT NULL DEFAULT 0;
