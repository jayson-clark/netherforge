# Network demo

Three servers behind one proxy, made to be played by one person: a **lobby**,
an **arena** and a **market**, each busy with bots that chat, fight, trade and
wander from server to server. Everything that crosses between servers goes
through `nf.network`, so this project is a tour of it:

| What you see                                               | What does it                                                                                 |
| ---------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| Chat from every server, tagged `[Arena]`, `[Market]`       | the `chat` channel (`modules/chat`)                                                          |
| News: fights won, bots moving, big sales                   | the `news` channel (`modules/net`)                                                           |
| Walking into a portal takes you to another server          | `nf.network.send_player`, and `player_arrive` puts you at the portal back (`modules/travel`) |
| The sidebar's head count for every server, `/who`, `/find` | the `population` map, each server's key expiring if it stops (`modules/presence`)            |
| Arena champions on the lobby's board                       | the `wins` map, `map:add` from the arena (`modules/arena`, `modules/boards`)                 |
| One purse on every server, `/coins`                        | the `coins` map, paid and spent with `map:add` (`modules/economy`)                           |
| `/daily` pays once every five minutes, network-wide        | `map:insert` with an expiry: a claim only one server wins                                    |
| `/price` and `/buy` from any server                        | requests on the `market` channel, answered by the market (`modules/market`)                  |
| Bots leaving one server and turning up on another          | the `travel` channel: one server's bot leaves, the other's joins (`modules/crowd`)           |

## Running it

The same project runs on all three servers; a server's name in the network
picks its part (`arena`, `market`, anything else is the lobby).

1. Open this folder in the editor and trust it.
2. Open **View → Network**, tick **Run the dev server as a network**, and name
   this project's server `lobby` (any name but `arena` or `market` will do).
3. **Add server** twice: `arena` and `market`, each with project `.` (this
   one).
4. **Start**, and join `localhost` in Minecraft. You arrive in the lobby.

Run alone (the network off), the dev server plays the lobby by itself and tells
you how to start the rest when you join.

## Things to try

- Say something: every server shows it, and the bots answer from all over.
- Walk into the red portal (or `/go arena`), then `/duel` to fight the next free
  bot. Win, and the lobby's board shows it.
- `/daily` on one server, then try again on another.
- `/price diamond` from the lobby, `/buy diamond` with your winnings.
- `/who` and `/find Flint`: the bots don't stay put.
- Save a script: every server running it reloads.

## Bots and the proxy

A bot is a real player to the server it joins (the player list, chat, damage),
but the proxy never saw it log in, so the proxy can't move it and
`nf.network.players()` doesn't list it. The demo moves bots its own way (the
`travel` channel), and counts them in its own map (`population`). Players
travel the real way, through the proxy.

`tests/network_test.lua` tests the network parts on a fake network, where the
other servers are played by `nf.test.network_*`.
