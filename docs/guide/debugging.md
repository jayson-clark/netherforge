# Debugging scripts

The dev server has a debugger: set a breakpoint on a line of a script, and
when the server reaches it, the whole server stops there while you look at
what the script holds, step through it line by line, and let it go on. It
works on the dev server the editor runs, never on a production server.

## Breakpoints

Click in the margin left of a line's number in a script (or press **F9** on
the line) to set a breakpoint; click it again to remove it. Breakpoints are
remembered per project, and they stay on their line as you edit: add lines
above one and it moves down with its code. Rename or move the file (or its
folder) and they go with it; delete it and they go too.

A dependency's scripts can be debugged the same way: open one of its files
(read-only) and set a breakpoint. The stack names its files by package path
(`library:modules/greetings/init.lua`), as script errors do.

The **Debug** panel (beside the Console and the Profiler) lists every
breakpoint; click one to open the script there. **Breakpoints** turns them all
off and on again without forgetting them. **Break on script errors** stops
wherever a script's error goes uncaught (one no `pcall` catches), before it's
reported, so you can see what led to it.

## When the server stops

A breakpoint stops the **whole server**, not just the script: the tick is
frozen, nothing moves, and nothing else runs until you go on. Players stay
connected (their action bar says where the server is paused), and the server
doesn't decide it has crashed however long you take.

The editor opens the script at the line, marks it, and brings the Debug panel
forward:

- **Call stack**: the functions that led here, innermost first, each with its
  file and line. Select one to see its variables and jump to it.
- **Variables**: the selected function's **Locals**, its **Upvalues** (the
  locals of enclosing functions it uses) and the script's **Globals**. Tables
  open to show what's in them, as deep as you like; handles show what they
  stand for (`Player Steve`, `Centity tower 1a2b3c4d`) and open to their
  details, such as where they are. Nothing of your script runs to show a
  value: a table's own `__tostring` or `__index` isn't called.

Then, from the panel's toolbar or the Run menu:

| Action    | Key       | What it does                                                                                                      |
| --------- | --------- | ----------------------------------------------------------------------------------------------------------------- |
| Continue  | F5        | Runs on until the next breakpoint.                                                                                |
| Step Over | F10       | Runs to the next line of this function (or the function that called it, once it returns).                         |
| Step Into | F11       | Runs to the next line of any script, inside a function this line calls if it calls one.                           |
| Step Out  | Shift+F11 | Runs until this function has returned, to the next line of its caller.                                            |
| Pause     |           | Stops at the next line any script runs.                                                                           |
| Stop      |           | Lets the server go and turns breakpoints off (they're kept; turn them back on with **Breakpoints** in the panel). |

A handler is called by the server, not by another script, so stepping over
its last line runs on rather than stopping in whatever runs next. In a
[task](scripting.md), stepping over `nf.wait(20)` stops at the next line when
the task wakes, twenty ticks later.

## What waits while it's stopped

- **Saves.** Hot reload can't happen in the middle of a script: files you save
  while the server is stopped are reloaded as soon as it runs again.
- **Time limits and costs.** The time a script spends stopped isn't counted
  against its [time limit](performance.md#other-limits), its cost in
  `/nf scripts`, or the Profiler.
- **The rest of the editor's requests** (spawning, the instance list, the
  server's settings) are refused with a note that the server is paused.

Stopping the dev server, or closing the editor, while it's paused lets it go
first, so nothing is left frozen.
