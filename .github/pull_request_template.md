## What it changes

<!-- What someone using NetherForge notices, then how it works if that isn't obvious. Link the issue it fixes: "Fixes #123". -->

## Checklist

- [ ] `pnpm lint` and `pnpm test` pass.
- [ ] Tested at the lowest layer that can catch its bug (see the [testing guide](https://github.com/jayson-clark/netherforge/blob/main/.claude/skills/testing/SKILL.md)).
- [ ] Generated files regenerated (`pnpm generate` after changing `packages/api/`), not edited.
- [ ] A format change updates `docs/format/` and the examples.
- [ ] Golden updates (`UPDATE_GOLDEN=1`) read before committing.
- [ ] Something users notice is under `## [Unreleased]` in `CHANGELOG.md`.
