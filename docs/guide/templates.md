# Starting from a template

Three small, commented projects show a whole feature working, and you can
start your own from any of them:

| Template     | What it is                                                                                                                                                         | What it shows                                                               |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------- |
| **Minigame** | A lobby, a countdown and three timed rounds between two teams: every death scores for the other team. `/arena join` plays it.                                      | Timers, teams, a boss bar, a sidebar, custom events, commands.              |
| **Shop**     | A menu that sells bread and a lucky charm for coins. Each player's coins are kept in the project's database. `/shop` opens it, `/coins` shows the balance.         | Menus and project items, `nf.db()`, tasks that wait, a purchase event.      |
| **RPG mob**  | A goblin that appears by itself near players, takes four hits to kill, drops loot from a loot table, and is counted per player. `/slayer` shows how many you have. | Natural spawning, loot tables, custom events on a centity, `nf.db()`, hits. |

## Adding one

In the editor, **Add template…** (under the explorer's kinds) lists them, and
**Create project** has a **Start with** choice, so a new project can begin
with one.

Adding one copies its files into your project as your own: its menus, items,
centities, modules and loot tables where they'd be in any project, and its
`tests/` beside them, as examples of testing your copy. They're yours to change
from then on. If your project already has a resource or file of the same name
(a `shop` menu, say), nothing is copied and the editor says which.

Two of them keep data in the project's database (the shop's coins, the goblin
kills). What a template requires (`"requires": { "db": true }`) is added to
your `netherforge.json` with it, so it works straight away. Their modules make
their own tables when they start.

## They are tested

Each template is also a project in its own right, under `examples/template_*` in
the NetherForge repository, with `*_test.lua` tests that
[run on the fake server](testing.md): the minigame is played through its rounds,
a purchase is made and refused, a goblin is hit until it dies and appears by
itself near a fake player. The same tests run in NetherForge's own CI, so a
change to the Lua API that would break a template is caught there, and the
templates are kept working as the API changes. Read the tests beside the code to
see how to test your own copy; they are the best examples of `nf.test` there are.

For everything at once, a booth for every feature and worlds of its own, see
[The showcase](showcase.md).
