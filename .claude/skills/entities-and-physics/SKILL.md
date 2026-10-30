---
name: entities-and-physics
description: Centity node trees, transforms, animation and hitboxes as the server runs them, and the rigid-body physics solver - bodies, colliders, contacts (SAT), sequential impulses, inertia, sleeping, collision with world blocks and other centities' hitboxes, how physics writes node poses, the Lua velocity/impulse API, and the deliberate behaviour changes from the reference (client-only shapes fitted by the editor). Read before touching apps/plugin/runtime/.../centity or .../physics, PhysicsDef, or the Node physics API.
---

# Entities and physics

Spec: `docs/format/centity.md`. This skill is the runtime's side of
centities: the tick pipeline, instances and their entities, natural
spawning, hitboxes and click routing (the sections at the end), where
shapes come from, and physics.

## Shapes the server can and can't know

The plugin never needs a client-only fact (game-data skill). So:

- `world:raycast` tests centities with the same shapes clicks are
  (`Centities.raycast` → `nearestHitbox`: a `raycast` node's boxes, any
  other node's interaction-entity bounds, skipping a node set not
  clickable), in loaded instances only, and
  takes whichever is nearer of that and the platform's block ray cast.
- Hitboxes are explicit `boxes`, or `shape: "collision"` (the block's live
  collision shape, `GameData.collisionBoxes`, cached per state), or the unit
  cube. A stair's **model** shape or a **text** quad is fitted by the editor
  into explicit boxes (`fittedTo`); the validator warns when stale.
- A script that changes a block or text at runtime can only make a hitbox
  follow the **collision** shape, or keep its explicit boxes. This is a
  deliberate change from the reference, which resolved model/text shapes on the
  server from baked tables.

## Spatial queries and `touch`

`world:overlap_box`/`overlap_sphere`/`overlap_cone`, `sweep` and `nearest` (`centity/Spatial.kt`):
a `QueryShape` (its broad-phase `region`, the `origin` distances and the line of sight are measured
from, and `test(box)`, the narrow phase) run by `SpatialQueries` against centities' hitboxes through
`Centities.obstacles` (the same `ObstacleGrid` physics uses, whose `Obstacles.nodes` says which node
each box is of) and the server's entity boxes (`WorldEntityOps.boxes`, a contract case). A centity is
found once, by its nearest box; results are sorted by distance, then the line of sight (one block ray
each) and the limit apply. `Capsule` (a sweep) is exact where the sphere first meets a face (a slab
test against the box grown by the radius) and halves its way in at an edge or a corner. `sweep` itself
casts one block ray first and shortens the capsule to it, so nothing past the block is found.
`world:raycast`'s centity half uses the grid too. `touch` is `Centities.touches()`, after the whole
pass so every instance has moved: `SpatialQueries.contacts` per listening instance, compared with
`Instance.touching`. Tests: `SpatialQueryTest`.

## Bodies (`centity.json`'s `body`)

A centity's `body` moves the whole centity, where a node's `physics` moves one node. Format: `BodyDef`
(the file), `ResolvedBody` (defaults and clamps, what the validator's `centity.body-*` problems say),
`CompiledCentity.body`. Runtime: `Instance.mover` (a `Mover`: settings, velocity, walk, what its last
move found; reset by `redefine`) and `centity/Movers.kt`, stepped in the pipeline after a walk and
before physics (skipped while `move_to` walks it). One step: drag, then gravity (less in a liquid,
read from the block at its middle), then the walk's approach (so a walk lands on exactly the speed it
asks for); then the move, Minecraft's way: one `RegionShapes` read of the blocks around the swept box
(unloaded chunks are full cubes), clipped up or down first, then the longer sideways axis first; a
blocked sideways move on the ground is tried again from `stepHeight` higher and kept if it got
further. A body that starts overlapping a block rises out. `jumpSpeed` halves its way to the launch
speed whose apex, integrated tick by tick exactly as the step does, is the height asked. The anchor is
set directly (only into a chunk whose entities are loaded), as a walk sets it; `land` and `bump` are
raised from what the step returns. Tests: `CentityBodyTest`.

## Flights (`centity:launch`)

`Instance.flight` (a `Flight`: the launch's options resolved, with its velocity, age, bounces, whether it's
`still` (stuck or resting) and `hitAt`, what it hit and when), stepped by `centity/Flights.kt` first in the
pipeline; while it flies a walk is ended and the body waits. One step: homing (the nearest a `Sphere` query
finds, turned toward by at most its rate), gravity after its delay, drag; one block ray from here to there,
then one `Capsule` query up to the block for what the path touches, in order, each raising `hit` (cancelled:
it passes) and using a `pierce` or ending it; then the block: stick, bounce (the normal part by `bounce`, the
part along the face by `friction`, resting on a floor below `rest_speed`) or end. A handler that removes it,
stops it or launches it again ends that step (`instance.flight !== flight`). `end` raises `flight_end` and
removes it unless `remove_on_end` is false. Tests: `FlightTest`.

## Physics: where things are

| Path (`apps/plugin/runtime/.../`) | Owns                                                                              |
| --------------------------------- | --------------------------------------------------------------------------------- |
| `physics/Quat.kt`                 | orientation maths; euler convention identical to `Matrix4.rotateXYZ`              |
| `physics/RigidBody.kt`            | `RigidBody`, `ColliderPart`, `BodyState` (velocity, sleep, …)                     |
| `physics/Inertia.kt`              | inverse inertia of box compounds (parallel-axis) and spheres                      |
| `physics/Contacts.kt`             | SAT (15 axes), manifolds, sphere cases, `BodyShape`                               |
| `physics/PhysicsSolver.kt`        | slicing, sequential impulses, friction, restitution, bias separation              |
| `physics/PhysicsCollider.kt`      | collider boxes from `physics.collider`/hitbox, centre of mass, origin             |
| `centity/Physics.kt`              | the per-instance pass: snapshot, obstacles, solve, write back; script API helpers |

The solver is a faithful Double port of the reference's
(`centity/packages/core/.../physics`), pure and Platform-free. Tests:
`physics/PhysicsTest`, `PhysicsPairTest`, `QuatTest` (solver alone) and
`PhysicsCentityTest` (spawned centities on the fake world).

## How a tick moves bodies

Order per instance: **animate → path → physics → scripts → sync** (path: see
"Walking" below). After the
solve, `Physics.report` raises the node events: `collide` for each thing a
body started touching (`PhysicsSolver.Entry.touches`, keyed by the other body
or, for obstacles, by which of six ways the surface faces, compared with
`BodyState.touching` from its last simulated step, so resting contact never
fires and a sleeper keeps its set), then `wake`/`sleep` when `asleep` differs
from `BodyState.reportedAsleep` (whatever woke it: a hit, a script). Contacts
are only turned into payloads when something listens (`Centities.listening`).

1. **Snapshot** every node with `physics` against one composition. Each
   carries a `BodyState` (on `Instance`, rebuilt by `redefine`: a reload
   restarts bodies at rest from the file's pose). If the node's rotation
   differs from what physics last wrote, a script or clip set it: re-seed the
   orientation from the pose (and wake).
2. **Obstacles**, in the simulation frame (relative to the anchor, the
   world's axes: `Instance.placed()`, see Coordinate spaces): world blocks from `WorldOps.collisionBoxes` (live collision shapes, loaded
   chunks only, region capped at 32 blocks a side) and the hitboxes of every
   instance within 48 blocks whose boxes reach the body's region, **each box
   separately**, found through `ObstacleGrid` (each instance's boxes measured
   once per change and filed by 4-block cell; `Instance` marks itself dirty on
   any pose, facing, visibility, display or anchor change) and listed in
   instance order, since the solver's result depends on obstacle order — minus the body's own
   subtree and the other bodies of its own instance (those are paired by the
   solver, which is what shares momentum). Bodies of other instances are
   immovable scenery. Sleeping bodies skip this unless due a support check
   (every 20 ticks) or near something awake; unsupported sleepers wake.
3. **Solve** all bodies together (`PhysicsSolver.solve`).
4. **Write** translation (and rotation unless `rotates: false`) through
   `Instance.set`, in the parent's frame (for a root, the centity's turn,
   `Instance.turn()`) — the same path a script's setter takes, so sync and
   read-back need nothing special. Only bodies that moved
   are written, so a settled instance costs nothing.
5. **Re-anchor** (`Physics.reanchor`): once a body is more than a chunk
   (`REANCHOR`, 16 blocks) from the anchor horizontally, the anchor moves
   under it (whole blocks) and every root node moves back by as much, so no
   world position changes but the entities, chunk loading, tracking and the
   index follow the body (the shift is world-axis, so it's turned back into
   the centity's space for the root translations). Skipped while a clip plays (it writes root
   translations itself). A script reading `Centity:position()` sees the
   anchor move; node translations are relative to it, as always.

Nodes a script adds (`add_node`/`add_child`) are bodies and hitboxes like
declared ones (the snapshot, obstacle grid and click routing all read
`Instance.definition`); see "Script-added nodes" below
for how they're kept and dropped.

Node poses aren't persisted: after a restart a body starts again from its
authored pose, relative to the (possibly moved) anchor and yaw, which are.

## Lua

`Node`: `velocity`/`set_velocity`/`add_velocity` (blocks/s, world axes),
`angular_velocity`/`set_angular_velocity` (degrees/s), `mass`, `apply_impulse`,
`apply_impulse_at` (the point a world position), `apply_force` (an impulse of
force / 20: a push for one tick), `is_on_ground`, `is_asleep`, `wake`, and
three switches on `BodyState`, none saved: `set_gravity` (the snapshot runs
the body with `gravity = 0`), `set_kinematic` and `set_physics_enabled`. A
kinematic or frozen body is **scenery** (`BodyState.scenery`): left out of the
solve, and an obstacle (the box around its collider) for its own centity's
other bodies in `hitboxes()`. A kinematic one is moved by `moveKinematic`
before the snapshot, by its own velocity and spin, through the setters; a
frozen one doesn't move at all and keeps its velocity for when it's let go. Every value is a `Vec3` in world space. Bodies simulate along the
world's axes, so velocities and impulses pass straight through; only the
impulse point moves, to the anchor-relative frame (`Instance.fromAnchor`),
before `Physics.impulse`. Every write wakes the body; non-bodies return
`false`/`vec3.zero`.

## Coordinate spaces

World; the **centity's** (origin at the anchor, turned by the centity's yaw:
root translations are in it); each node's **parent** space (`translation`,
`rotation`, `scale`). `Instance.space()` is the one matrix from centity space
to the world (`translate(anchor) · turn()`), and every conversion the Lua API
offers goes through it (`toWorld`, `toCentity`, `worldMatrix(index)` =
`space() · compose()[index]`, `parentWorldMatrix`). `compose()` uses the pose
as shown (rest plus clips, physics already written), which is what "as shown
now" means for `world_position`, `world_rotation`, `to_local`, `to_world` and
`world_direction`. `look_at` and `rotate` set the node's rotation in its
parent's space (a look rotation keeps +X level; `rotate` pre-multiplies a
parent-space axis-angle), so on a root they work in the turned space.

### Centity yaw

`Instance.yaw` (Minecraft degrees, wrapped into [-180, 180), set only through
`turnTo`; the store gets it when the world saves, see below). `turn()` is the
rotation it means, `rotateY(-yaw)`, with exact quarter turns so a centity
facing a compass direction reports whole numbers. The yaw is applied in one
place per consumer, never on the entities:

- **`Instance.placed()`** = `turn() · compose()[i]`: node matrices relative to
  the anchor along the **world's axes**. Sync pushes these as display
  matrices (the entities stand at the anchor with yaw 0), measures hitbox
  bounds with them (interaction entities are axis-aligned in the world, so a
  turned box's AABB is taken after turning), and click picking casts the
  sight ray in this frame (`fromAnchor` / `atAnchor`), so hit positions and
  normals come back as world values directly.
- **Physics simulates in the `placed()` frame**, not the centity's: gravity,
  `BodyState.velocity`/`angularVelocity`/`orientation`, world blocks, other
  instances' hitboxes and contacts are all along the world's axes, offset by
  the anchor. A turned centity therefore collides with the world exactly as
  an unturned one would. Writing back goes through the parent matrix (a
  root's parent is `turn()`); reanchoring turns its world-axis shift back
  into centity space.
- **`turnTo`** rotates every body's velocity, spin and orientation by the
  change in yaw, so a turn carries the bodies with it rather than leaving
  them pointing the old way (which would also break the writtenRotation
  re-seed check).

The anchor `Location`'s own yaw/pitch are always 0. `centity:location()`
returns the yaw (pitch nil); `nf.centities.spawn` and `centity:teleport` take
a `Location`'s yaw when it has one (`LuaPlace.yaw`), and teleport keeps the
current yaw otherwise. The yaw is saved in `InstanceRecord.yaw` (the
store's `instances.yaw`; non-finite reads as 0) and survives a reload because
`redefine` keeps it. Like the anchor, it's kept in memory and written when the
world saves, the anchor's chunk unloads or the session stops (the
plugin-persistence skill's "Persistence"), so a centity turning every tick writes
nothing.

## Walking (`centity:move_to`)

Our own A*, not the game's pathfinder (centities aren't mobs). Where things are:

| Path (`apps/plugin/runtime/.../`) | Owns                                                                                          |
| --------------------------------- | --------------------------------------------------------------------------------------------- |
| `pathing/Space.kt`                | `BlockShapes` (a block's collision boxes, null = unloaded), `Walker`, `Space.clear`/`floorAt` |
| `pathing/PathSearch.kt`           | the shared `AStar` core, `GroundSearch` (walk), `AirSearch` (fly), smoothing, `NODE_LIMIT`    |
| `centity/WorldShapes.kt`          | `BlockShapes` from `WorldOps.collisionBoxes`: `RegionShapes` (one call), `BrickShapes` (8³)   |
| `centity/CentityPaths.kt`         | `Walk` (on `Instance.walk`), `move_to`'s start, the per-tick step, re-paths, `path_end`       |

- **Platform-free search**, tested alone (`pathing/PathSearchTest`) against a
  map of blocks, and on spawned centities in `CentityPathTest`. It only asks
  `BlockShapes`, i.e. the live **collision** shapes (`GameData`'s server
  facts): a fence is 1.5 tall, a carpet nothing. Unloaded chunks are walls,
  never loaded. Other centities' hitboxes aren't obstacles.
- **The walker** is a square footprint `width` across and `height` tall,
  measured once at `move_to` from the shown hitboxes in the centity's space
  (wider side, height; 1×1 without hitboxes; the options override). Its
  **feet** (bottom middle of the hitboxes, turned into the world) are what
  walks: `Walk.feet` is world state, and each step moves the anchor by as
  much (turning it about the feet when `face`), so physics re-anchoring
  can't disturb it. Grid cells are centred on block centres for odd widths
  and corners for even ones (`Walker.offset`), so a 2-wide walker fits a
  2-wide gap.
- **Walk vs fly cost.** `GroundSearch` nodes are (column, standing height): 8
  neighbours, each one `Space.floorAt` probe (box tops in the footprint from
  `step_height` above to `max_drop` below, highest with head room) plus a
  clear check of the box round both footprints (no corner cutting). It never
  looks at the air above or below the walker. `AirSearch` is separate: 26
  neighbouring cells, each a 3D clear check of the box round the walker at
  both. On the "round a wall" test, walking looked at ~15.7k blocks
  (70 places) and flying ~30k (122). Walking costs nothing extra for flying
  existing; only `fly = true` pays for 3D.
- **Bounded.** One search: `range` from its start (default 48, max 128) and
  `NODE_LIMIT` (4096) expanded places; past either it answers the closest
  place reached (an incomplete `PathResult`), and an empty one only when
  there's no way any closer. `move_to` always searches at once (it answers
  whether there's a way). Searches walks start on their own (following, a
  block in the way, a partial path walked to its end) share `TICK_NODES`
  (16384) a tick and wait for the next tick past it, so a long way is found
  in pieces as it's walked rather than in one stalling search.
- **Smoothing**: from each kept point, the furthest of the next 16 a straight
  line reaches: walking, only on level ground, tested every 0.25 blocks for
  room (box round consecutive samples) and support (so it never walks off an
  edge); flying, room only. Steps and drops keep their grid points.
- **The step** (`CentityPaths.step`, in the pipeline after animate and before
  physics, so bodies settle where the centity was walked to and scripts see
  the result): moves `speed / 20` along the path, checking each stretch it
  passes in one `RegionShapes` read (a step up when something is in the way
  and it fits, else blocked); walking, then held up where it stops or falls
  under gravity (32 b/s², at most 78.4). Blocked or 40 ticks still: a new
  path (at most 3 in a row without reaching a point, then `path_end` false).
  The anchor is set directly; the store gets it when the world saves, as
  for any move.
- **Ends**: a place when its path is walked (reached = feet within 2 blocks;
  a partial path searches on while that gets it 0.5 closer), something it
  follows when the feet are within 2 blocks (re-pathing every 10 ticks if
  the target moved over a block), and gave up when that's gone or in another
  world. `stop_pathing`, `teleport` (`Centities.teleport`), a new `move_to`
  and `Instance.redefine` (a reload: the walk was sized from the old
  hitboxes and its handlers were the old script's) end it without
  `path_end`. Not saved; an unloaded instance isn't ticked, so its walk waits.

## Known limits (shared with the reference)

- **Stacks are unstable**: two boxes resting on each other drift apart over a
  few seconds (the pair normal tilts with the upper body's face). Body-on-world
  contact is stable.
- **A sleeping body is soft** against very slow contact (impulses on it aren't
  integrated); its velocity is zeroed every step so nothing accumulates.
- **Chains of touching bodies** resolve as one event (a Newton's cradle moves
  the whole row).

## How to…

**Tune or change the solver**: `physics/`, then the solver tests; check the
per-instance behaviour in `PhysicsCentityTest` and on a real server
(`pnpm test:integration` drops the example crate and waits for it to settle).

**Add a `physics` field**: format (project-format skill): the field on
`PhysicsDef`, its default and clamp in `ResolvedPhysics.of` (format's
`centity/ResolvedPhysics.kt`; the compiler resolves every body once, so the
solver only ever sees `ResolvedPhysics`), and the default in
`codegen/Constants.kt` if the editor shows it → use it in the
solver/collider → `docs/format/centity.md`.

**Add a body API**: `packages/api/` (`nodeClass`), `pnpm generate`, the new
`NodeApi` method in `api/NodeImpl.kt` using `centities.physics` /
`Instance.body`, a test.

## Centities: the tick pipeline

After the centity pipeline, `ParticleEffects.tick()` makes one pass over
every playing particle effect (so a centity moved this tick is where its
effect plays this tick): follow its target (gone: it ends `target_gone`),
step its `EffectSampler` (always: its time is the server's), find viewers
(online, same world, within 32 blocks, 128 for a `force` emitter's spawns,
of its `viewers` when given) and send one `ParticleOps.spawn` batch, within
4096 points a tick across all effects (the rest wait, starting one effect
further along each tick; a warning every 10 s). At most 1024 play at once.

Per instance, each tick: a change in whether its chunk is loaded raises
`chunk_load`/`chunk_unload`; then, when loaded: **animate** (clips advance,
the pose is recomputed from rest + clips; `animation_end`), **path** (a
walk `move_to` started moves the whole centity a step; see
entities-and-physics), **physics**
(bodies move and write their nodes' translation/rotation, then `collide`,
`wake`, `sleep`; see entities-and-physics), **scripts** (`tick`, only when
something listens; the rate is each handler's `every`: it runs where
`(tick + phase) % every == 0`, the phase the centity's (`Scripts.phaseOf`, a
hash of its handle, so a crowd spreads over the ticks) or the subscription's
id on `nf`; the event core tells Kotlin each handler's rate and phase
(`events.listening`), and `Scripts.due` raises `tick` only on ticks one is
due), **sync** (compose world matrices,
push only what changed).

### Natural spawning (`centity/NaturalSpawner.kt`)

A `RuntimeService` registered after `Centities` that runs a centity's
`spawning` block (format's `SpawningDef`, carried on `CompiledCentity.spawning`)
in `TickPhase.WORLD`. It spawns, keeps and removes through `Centities` and
holds nothing that outlives the session (its per-player due times and the
resolved rules are rebuilt).

- **Budget.** Each player gets a pass every `PASS_INTERVAL` (20) ticks,
  staggered by their UUID; at most `PLAYERS_PER_TICK` (2) passes run in a tick,
  each looking at `ATTEMPTS` (4) places. A place is a column 24 to 48 blocks
  away (`SpawningDef.MIN_DISTANCE`/`MAX_DISTANCE`), searched from near the
  player's height for air over a solid block (`spotIn`: `BlockOps.get` reads,
  bounded by `SEARCH`), so the block reads per tick have a fixed ceiling
  whatever the player count. Nothing here loads a chunk.
- **Rules.** `Rule.of` resolves a block's ids and `#tag`s against `Platform.game`
  once per definition (cached by identity, so a reload re-resolves). A place
  `fits` when world, height, light (`BlockSnapshot.light` of the feet block),
  biome (`WorldOps.biome`) and the block stood on (the block under the feet,
  id without properties) all match; one of the rules that fit is chosen by
  weight, then a group, the first member at the found place and the rest within
  three blocks where the rule still fits.
- **Caps** are per player and per centity: the natural, un-kept instances of
  that centity within its `despawnDistance` of the player. A script's spawn,
  a kept instance and another player's neighbourhood don't count. They are the
  project's own and unrelated to `WorldOps.spawnLimit` (vanilla's).
- **The handler.** Each group member is first offered to
  `nf.on("centity_natural_spawn")` (only built when something listens): a
  cancel drops that member, `event.location` moves it (yaw is its facing).
  The centity's own `spawn` runs afterwards, as for any instance.
- **Temporary.** `Centities.spawn(..., natural = true)` sets `Instance.natural`
  before anything is made: its entities are claimed with
  `EntityOps.setPersistent(false)` (so a crash can't leave them in a chunk),
  `flush`/`savePlacements` never write it to the store, and a restart doesn't
  know it (its stray entities are removed by `entitiesLoaded`, as any tagged
  entity nobody owns is). `InstanceRecord.natural` only carries it across a
  full reload's `Handover`. `Centities.keep` clears the flag, makes the
  entities persistent again and marks it dirty so the store gets it.
  `spawning.keepOnInteract` calls it from `Centities.click`, once a click has
  picked a node (a click through a `raycast` node's padding keeps nothing),
  before scripts hear the click.
  A natural instance whose centity is deleted is discarded, never made inert.
- **Script-temporary** (`nf.centities.spawn(kind, place, { temporary, lifetime })`): `Instance.temporary`, with
  `owner` (the spawning scope's id) and `expires` (a server tick). Persistence treats `Instance.transient`
  (natural or temporary) alike: never staged, entities non-persistent. `Centities.scopeReleased` removes the
  owner's, `pass()` removes the expired before anything ticks (unloaded ones too), and `stop()` removes every
  temporary one before the scripts stop, so a full reload or a restart leaves nothing behind. `set_lifetime`
  makes a normal instance temporary (the store forgets its row); `keep` ends both kinds. Tests: `TemporaryCentityTest`.
  Every `NaturalSpawner.PASS_INTERVAL` ticks a sweep removes the natural
  instances with no player within their `despawnDistance` (in their world).
- **Tests.** `NaturalSpawningTest` runs it on the fake: `FakeWorlds.biomes`,
  `defaultBiome`, `lights` and `defaultLight` set a place's biome and light,
  `NaturalSpawner.random` takes a seed.

## Instances: transforms, anchors, looks and loading

- **Local transforms are the truth**, not matrices. A script's setter writes
  the _rest_ pose (and the shown pose immediately); a clip owns the channels
  its tracks drive while it plays and they fall back to rest when it ends.
- Displays all **ride the anchor entity** (`Instance.anchorEntity`, role
  `ANCHOR`, an empty text display at the anchor: `EntityOps.spawnAnchor`,
  `mount`, `dismount`) and carry their offset in the transformation matrix,
  so moving a centity is one teleport, of the anchor entity. A hitbox rides
  it too while its bounds sit at the anchor (`Bounds.atAnchor`: centred, its
  bottom at the anchor's height), since an interaction entity has no
  transform to carry an offset in; any other hitbox stands on its own and is
  teleported, getting off and on as it moves. `Instance.riders` is what
  rides now (runtime state: a `forceSync` mounts again, and mounting is
  idempotent); something that couldn't get on is teleported as before. A
  killed anchor entity is respawned by reconcile and picks its riders up; an
  instance stored before anchors existed gets one at its first reconcile.
  The anchor's own facing is always zero: the centity's yaw is baked into
  the matrices (`Instance.placed()`), so turning it is a pose update, never
  an entity rotation.
- **Following** (`Centities.follow`, `centity:follow(player)`): the anchor
  entity is mounted on a player (`Instance.follower`), and each tick
  (`followTick`, before the walk and sync) the anchor moves to their place and
  the centity turns to their body: `LivingEntity.getBodyYaw()` through
  `WorldEntityOps.bodyYaw`, not their look, since the body lags the head and
  follows walking. While it rides them (`followMounted`) sync
  never teleports the anchor, since a teleport would dismount it; if the
  mount fails (other world) the anchor is teleported as usual. A teleport or
  `move_to` ends a follow; physics bodies refuse one. Not persisted.
  Hidden = zero-scale matrix, collapsed at the node's own place (`Centities.collapsed`), never at the anchor: the
  client eases from the collapsed matrix, so collapsing to the anchor glided a shown node in from there.
  `Instance.jumps` (set by `setVisible` and `set(…, instant = true)`, read up the parent chain like `edited`,
  cleared by `markSynced`) sends that node's next pose with 0 interpolation ticks, so showing, hiding and
  `{ instant = true }` moves land at once while later moves still ease. `cullSize` widens the client's culling box.
- **Rects, images and anchored text** (`centity/DisplayDrawing.kt`, geometry in format's `DisplayGlyphs`): what the
  entity shows isn't the node's content but `DisplayDrawing.drawn(content)`: a rect is a text display whose text is
  one space character and whose background is the colour, an image rows of the engine's pixel glyphs one
  `<color>` per run, anchored text the text with a line width that never wraps; each with a `fit`, a matrix in the
  display's own space post-multiplied in `Centities.drawn` (so the pushed pose is compared fit included). Drawn
  displays are cached by content (`Pixels` is equal by its pixels, so the same picture again is the same display
  and sends nothing). Changed images share `IMAGE_BYTES_PER_TICK` per instance per sync; what's past it waits
  (`Instance.deferContent`). Adapters never see `RectDisplay`/`ImageDisplay` (they answer false/null for them).
  Interpolation is each node's `interpolation_ticks` (1 unless a script
  changed it: smooth at 20 Hz), 0 when forced; the roots' least, capped at
  59, is the anchor entity's teleport duration (`Instance.moveTicks`), so a
  moving centity glides too.
- **Clips as keyframes** (`centity/Keyframes.kt`). While only clips move a
  node (`Instance.edited`: not it or a node above it set by a script or
  physics, hidden, re-eased, the centity not turned, no clip started,
  stopped or moved since the last sync: `AnimationPlayer.version`), sync
  doesn't send its pose every tick: it sends the pose `h` ticks on with `h`
  ticks of interpolation and nothing more until `Instance.due`, `h` being as
  far as the client's easing (translation and scale straight, rotation the
  short arc: Paper's decomposition, see the file) passes within a hundredth
  of a block of the clip at each tick between (`Keyframes.reach`, at most
  20, looking ahead with `Instance.placedAhead` / `AnimationPlayer.applyAhead`).
  So a linear segment is one send, an eased one a few, a step one at the
  step, and a child on a turning parent pieces short enough that the chord
  stays near the arc. Shear, mirrors, a collapsed node or an uneven scale
  that changes aren't planned. The server's own pose (hitboxes, physics,
  `world_position`) is still every tick's.
- **Looks.** What a script changes about how a display is drawn (glow and
  its colour, brightness, a billboard, a text display's opacity, an item
  display's whole item) is a per-node `DisplayLook` on the `Instance`, reset
  by `redefine`; `shownLook` adds the centity's view range (blocks / 64) and
  the teleport duration. Sync pushes it with `EntityOps.setLook` after the
  content (so a look's billboard or item has the last word) whenever it or the
  content changed. Text style other than opacity lives in the node's
  `TextDisplay` content copy.
- **Clickable.** An unclickable node's hitbox is collapsed like a hidden
  one's and skipped by click picking; physics still treats it as solid.
- **Per-player hiding** (`hide_from`): `Instance.hiddenFrom`
  is the record (kept across reloads, not restarts). `Centities.setHidden`
  hides every display and hitbox (`EntityOps.setHidden`, Paper's
  `hideEntity`); a newly spawned entity is hidden as it's claimed; the server
  forgets on quit and on unload, so `playerJoined` and `entitiesLoaded` hide
  them again. A click from a player it's hidden from goes nowhere.
- **Entities are held by node name.** `redefine` (a reload) resets per-node
  state from the new definition; **reconcile** then removes entities whose
  node or component is gone, updates every display's content, respawns one
  whose kind changed, and spawns what's missing. Entities that couldn't be
  removed wait as **orphans**, tried once more when the instance is next
  loaded and then forgotten (one still out of reach goes as a stray when its
  chunk loads).
- **Script-added nodes** (`Centity:add_node`, `Node:add_child`, `Node:remove`):
  `Instance.declared` is the file's `CompiledCentity`, `Instance.definition`
  is `declared` plus the added nodes appended after it (so declared indices
  never move, which clips rely on, and a parent always precedes its children).
  `Instance.addNode` / `removeNode` (through `Centities.addNode` / `removeNode`,
  which also drops handlers on removed nodes) rebuild the per-node arrays by
  node name and renumber `BodyState.touching`; both set `needsReconcile`, so
  the same tick's sync spawns or retires the entities. The table a script
  passes is read by `nodeFrom` (`api/Definitions.kt`): Lua spelling to the
  file's `NodeDef`, then format's `CentityValidator.validateNode` and
  `CentityCompiler.compileNode` (the file's one node model, not a parallel
  one). Added nodes are runtime state: `redefine` (any reload of the centity)
  goes back to `declared`, and they aren't in `InstanceRecord`, so after a
  restart reconcile retires their stale entities; only added nodes can be
  removed (`Instance.isAdded`). Code that holds node **indices** across a
  script call (a handler may remove nodes and renumber) must go by name, as
  `Physics.report` does.
- **Loaded is the anchor's chunk**, `WorldOps.entitiesLoaded(anchor)`, not
  whether an entity answers (and an instance with no entities at all is
  always loaded, so spawning anywhere puts them in the world): `isLoaded(id)` is false both for an entity in an
  unloaded chunk and for one that's gone. With the chunk's entities loaded, an
  entity of ours that doesn't answer was killed or removed by something else,
  so `dropGone` forgets it and reconcile spawns it again.
- **Unloaded instances do nothing.** On a Paper server nothing keeps chunks loaded
  without players, so a centity far from everyone (or at spawn with nobody
  online) is unloaded: no ticks, no sync, reconcile deferred until
  `EntitiesLoadEvent` brings the entities back (`entitiesLoaded` →
  `forceSync`). Don't force-load chunks from the plugin. The fake platform
  unloads by chunk (`FakeWorlds.unloaded`), as Paper does.
- **Inert instances**: a record whose centity is gone (deleted, or the
  project refused) keeps its entities and comes back when the definition does.
  A centity file with errors keeps its _old_ definition running.

## Hitboxes and clicks

Picking and ray tests are `CentityClicks` (`Centities.click`, `clickTarget`, `aim` and `raycast` hand them
over): it reads who owns an entity, the instances' boxes and the obstacle grid, and never changes an instance
but to keep a natural one a click reached.

- An interaction entity is axis-aligned with a square footprint, so
  `Hitboxes.bounds` spawns the extent of the node's transformed boxes. Boxes
  are explicit `boxes`, or `shape: "collision"` → `GameData.collisionBoxes`
  of the node's _current_ block (`set_display_block` reshapes it; `PaperGameData`
  caches per state), or the unit cube.
- A click names an entity; `Centities.click` re-picks among the instance's
  hitbox nodes along the player's sight (narrow phase for `raycast` nodes,
  the entity box otherwise), nearest wins. A ray through a `raycast` node's
  padding hits nothing. The hit's point and face normal become the click's
  `hit_position`/`hit_normal`. The click then goes along its path: the node's
  `click` handlers, each ancestor's, the centity's, then `nf.on("centity_click")`,
  until a handler calls `event:stop()`.
- A click on any tagged entity is cancelled even when no script wants it.
