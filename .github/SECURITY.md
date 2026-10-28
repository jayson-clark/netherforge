# Security

## Reporting a vulnerability

Please report it privately, not in a public issue: on GitHub, open the
repository's **Security** tab and choose **Report a vulnerability**. Say what
it lets someone do, which part it's in, and how to reproduce it.

You'll get an answer once it's been looked at, and credit in the release notes
of the fix unless you'd rather not.

## Supported versions

Fixes go into the newest release. Until 1.0, there are no fixes for older
ones: update to the latest editor, plugin and `@netherforge/cli`.

## What counts

Anything that breaks a promise NetherForge makes, for example:

- A script escaping the plugin's Lua sandbox: reading or writing files,
  running code outside Lua, getting past its instruction, time or memory
  limits, or reaching another package's data.
- A package using a capability it didn't declare in `requires`, or
  `nf.http` reaching an address its guard should refuse (the server's own
  network, for example).
- The editor reading or writing outside a project it was given, or a project
  running code in the editor before the user trusted it.
- Anyone other than the user's own agents using the editor's MCP server or the
  dev bridge, or getting their tokens.
- A release artifact or the npm package not being what this repository built.

What a server owner chooses to run is theirs: a package they installed doing
what its declared capabilities allow isn't a vulnerability.
