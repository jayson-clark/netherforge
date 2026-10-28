# Using git with a project

A NetherForge project is a folder of text files and PNGs, designed to live in
git. NetherForge has no accounts, sync or cloud of its own: git is how you keep
history, work with other people, review changes, and deploy.

## Start a repository

```sh
cd my-server
git init
git add .
git commit -m "New NetherForge project"
```

The `.gitignore` the editor created leaves out `.netherforge/`, what the
editor generates into the project: the JSON Schemas, the docs and command
for coding agents, and cached pictures. Never commit it. The dev server and
its world aren't in the project at all; they're in the editor's
[data folder](install.md#where-the-editor-keeps-things).

Push it anywhere you like (GitHub, GitLab, your own server) and collaborators
clone it and open the folder in NetherForge.

## Why diffs stay small

- **One canonical form.** The editor always writes a JSON file the same way:
  two-space indentation, keys in a fixed order, unset keys left out. Saving a
  file you didn't change produces no diff, and reformatting never shows up as a
  change.
- **Lua is in `.lua` files**, never inside JSON, so script changes diff like
  code.
- **Ids are folder names.** Renaming a centity is a folder rename, which git
  records as one.

## Why merges stay clean

Things people add at the same time (nodes, animations, tracks, menu
slots) are stored as objects keyed by name and written in sorted order, not as
lists. Two people who each add a different node to the same centity touch
different lines, and git merges them without a conflict.

When there is a conflict, it's in readable JSON or Lua. Resolve it in any
editor, then open the project: the Problems panel tells you if the result
isn't valid, down to the key.

## Working alongside the editor

You can run git while the editor has the project open. Checking out a branch,
pulling or resetting changes files on disk, and the editor follows: open files
you hadn't changed reload silently; files you had unsaved changes in show a
banner offering **keep mine**, **take theirs** or a diff; deleted files close.

The dev server doesn't reload on its own after a checkout; run `/nf reload`
(see [Hot reload](dev-loop.md#edits-from-outside-the-editor)).

## Reviewing changes

Because the files are plain and canonical, pull requests on a project read
well: a moved node is a changed `translation`, a new animation is a new block
of keyframes, and a script change is a code diff. Review content the way you
review code.

## Deploying from git

A production server runs the project straight from a checkout: `git pull` and
`/nf reload`. See [Deploying to a server](deploying.md).
