# Install the editor

Download the editor for your operating system from the
[latest release](https://github.com/jayson-clark/netherforge/releases/latest) on GitHub.

| OS          | File                                                                        |
| ----------- | --------------------------------------------------------------------------- |
| **macOS**   | `NetherForge_<version>_universal.dmg` (Apple silicon and Intel)             |
| **Windows** | `NetherForge_<version>_x64-setup.exe`, or the `.msi` if you deploy with MSI |
| **Linux**   | `.AppImage` (any distribution), `.deb` (Debian, Ubuntu) or `.rpm` (Fedora)  |

Each release also carries the Paper plugin jars (`NetherForge-<version>-paper-<minecraft>.jar`)
for [deploying to a server](deploying.md), which the editor already contains, and the
Velocity plugin (`NetherForge-<version>-velocity.jar`) for a [server network](network.md)'s proxy.

## What else you need

- **Minecraft: Java Edition**, with an account, to join the dev server. The
  dev server runs in online mode, so you join with your real account.
- **Java 25 or newer** to run the server. You don't have to install it: if
  the editor can't find one, it downloads Eclipse Temurin (about 200 MB) into
  its own data folder the first time you start a server.
- **Optional: Minecraft installed locally**, in the version your project
  targets. NetherForge ships no Minecraft assets, so the 3D previews read
  textures and models from your own install. Without it, previews show
  placeholders and everything else works.

## Unsigned builds

Release builds are signed when the project has signing certificates set up.
If a build isn't signed, your OS warns you the first time you open it:

- **macOS** says the app "can't be opened" or "is damaged". Open **System
  Settings → Privacy & Security** and choose **Open Anyway**, or clear the
  download flag in a terminal:
  `xattr -dr com.apple.quarantine /Applications/NetherForge.app`.
- **Windows** SmartScreen says it "protected your PC". Choose **More info →
  Run anyway**.
- **Linux**: make the AppImage executable (`chmod +x NetherForge_*.AppImage`)
  and run it.

## Where the editor keeps things

Nothing goes into your project except the files you create and the
`.netherforge/` folder of generated schemas and docs (which a project's
`.gitignore` excludes).

| What                                          | macOS                                        | Windows                        | Linux                          |
| --------------------------------------------- | -------------------------------------------- | ------------------------------ | ------------------------------ |
| Settings, recent projects, EULA               | `~/Library/Application Support/NetherForge/` | `%APPDATA%\NetherForge\`       | `~/.config/NetherForge/`       |
| Java, Paper jars, Minecraft cache             | `~/Library/Application Support/NetherForge/` | `%LOCALAPPDATA%\NetherForge\`  | `~/.local/share/NetherForge/`  |
| Dev servers and their worlds, one per project | the same folder, in `servers/`               | the same folder, in `servers\` | the same folder, in `servers/` |

Each project's dev server has its own folder in `servers/`;
`netherforge-project.txt` in it says which project it's for. Delete a
project's folder there for a fresh dev world. It's kept out of the project on
purpose: a project you download can't slip a server or a plugin of its own
into it.

Next: [create your first project](first-project.md).
