//! `nf.test`: what a test file runs on (Kotlin's `NfTestImpl`). Only a session with a harness installs it, so a
//! server's scripts find `nil` at `nf.test`.

use std::rc::Rc;
use std::time::{Duration, Instant};

use mlua::{Function, Value};
use netherforge_api::api::NfTestApi;
use netherforge_api::handles;
use netherforge_api::raisable::{RAISABLE, Via};
use netherforge_api::{ApiError, ApiResult, Caller, shapes, unions, values};
use netherforge_platform::game_event::{PlayerInput, PlayerStopUsingItem};
use netherforge_platform::{BlockRef, ClickButton, PlatformEvents, PlayerRef};

use crate::network::protocol::{Lifecycle, MapOp, ServerFrame};

use super::TestHarness;
use crate::scripts::Target;
use crate::world::GameContext;

/// How long a tick waits for what scripts started on other threads (WebAssembly calls, programs) to settle.
pub const SETTLE: Duration = Duration::from_secs(10);

/// `nf.test`'s functions.
#[derive(Clone)]
pub struct TestContext {
    pub(crate) game: GameContext,
    /// Where each of the test's other servers last said it is in its life, and whether it takes players.
    pub(crate) statuses:
        Rc<std::cell::RefCell<std::collections::HashMap<String, (Lifecycle, bool)>>>,
}

/// How `nf.test.network_publish` names an admin client rather than a server, as the hub names it to scripts.
const ADMIN_PREFIX: &str = "admin:";

/// A lifecycle by its word (the spec's choice, so always one).
fn lifecycle(word: &str) -> Lifecycle {
    match word {
        "starting" => Lifecycle::Starting,
        "draining" => Lifecycle::Draining,
        "stopping" => Lifecycle::Stopping,
        _ => Lifecycle::Ready,
    }
}

/// A share as `hub.properties` spells it: `ns:name(access+access)`, a bare name `read` (Kotlin's `Share.parse`).
fn share(entry: &str) -> Result<crate::network::protocol::Share, String> {
    use crate::network::protocol::Access;
    let (name, words) = match entry.find('(') {
        None => (entry.trim(), vec!["read"]),
        Some(open) => {
            let Some(inside) = entry.strip_suffix(')') else {
                return Err(format!("\"{entry}\" has a ( with no ) after it"));
            };
            (
                entry[..open].trim(),
                inside[open + 1..].split('+').map(str::trim).collect(),
            )
        }
    };
    let mut access = Vec::new();
    for word in words {
        let found = match word {
            "read" => Access::Read,
            "write" => Access::Write,
            "request" => Access::Request,
            "answer" => Access::Answer,
            _ => {
                return Err(format!(
                    "\"{entry}\": \"{word}\" isn't read, write, request or answer"
                ));
            }
        };
        if !access.contains(&found) {
            access.push(found);
        }
    }
    let pattern = name.strip_suffix('*');
    let fits = crate::network::protocol::is_full(name)
        || pattern.is_some_and(|p| {
            p.find(':')
                .is_some_and(|at| at > 0 && crate::network::protocol::is_namespace(&p[..at]))
        });
    if !fits {
        return Err(format!(
            "\"{name}\" isn't a channel or map's full name (namespace:name, or namespace:* for all of a namespace's)"
        ));
    }
    Ok(crate::network::protocol::Share {
        name: name.to_owned(),
        access,
    })
}

/// A role's grants, as the hub's presets make them (Kotlin's `Grants.preset`).
fn preset(
    role: &str,
    owns: Vec<String>,
    shares: Vec<crate::network::protocol::Share>,
    presence: Option<bool>,
    send_to: Option<Vec<String>>,
    moderation: bool,
) -> Result<crate::network::protocol::Grants, String> {
    use crate::network::protocol::Grants;
    Ok(match role {
        "trusted" => Grants::trusted(),
        "proxy" => Grants {
            role: "proxy".into(),
            ..Grants::trusted()
        },
        "standard" | "isolated" => Grants {
            role: role.into(),
            trusted: false,
            owns,
            shares,
            presence: presence.unwrap_or(role == "standard"),
            send_to: if role == "isolated" {
                Some(send_to.unwrap_or_else(|| vec!["back".into()]))
            } else {
                send_to
            },
            moderation,
        },
        other => {
            return Err(format!(
                "\"{other}\" isn't a role: trusted, standard or isolated"
            ));
        }
    })
}

fn err<T>(message: impl Into<String>) -> ApiResult<T> {
    Err(ApiError(message.into()))
}

/// A player's name as the game allows it: 3 to 16 letters, digits and `_`.
pub(crate) fn is_name(name: &str) -> bool {
    (3..=16).contains(&name.len()) && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
}

pub(crate) fn check_name(name: &str) -> ApiResult<()> {
    if is_name(name) {
        return Ok(());
    }
    err(format!(
        "bad argument 'name' (3 to 16 letters, digits and _ expected, got \"{name}\")"
    ))
}

/// A terrain script's `terrain`, stood in for (the prelude's `utilities.test.terrain`, in Luau).
const TERRAIN: &str = r#"
local floor, randomseed = math.floor, math.randomseed
local function at(option, default, ...)
  if option == nil then
    return default
  end
  if type(option) == "function" then
    return option(...)
  end
  return option
end
return function(options)
  options = options or {}
  local noises = options.noises or {}
  local seed = options.seed or 0
  local terrain = {}
  function terrain.seed()
    return seed
  end
  function terrain.min_y()
    return options.min_y or -64
  end
  function terrain.max_y()
    return options.max_y or 319
  end
  function terrain.sea_level()
    return options.sea_level or 63
  end
  function terrain.noise(name)
    local value = noises[name]
    return {
      at = function(_, x, y, z)
        return at(value, 0, x, y, z)
      end,
    }
  end
  function terrain.height(x, z)
    return floor(at(options.height, 64, x, z))
  end
  function terrain.area(x, y, z)
    return at(options.area, "default", x, y, z)
  end
  function terrain.biome(x, y, z)
    return at(options.biome, "minecraft:plains", x, y, z)
  end
  function terrain.plan(name, size, make)
    local cells = {}
    local plan = {}
    local function cell(cell_x, cell_z)
      local key = cell_x .. "," .. cell_z
      local made = cells[key]
      if made == nil then
        -- Numbers of the cell's own, the same whenever it's made.
        randomseed(seed + cell_x * 7919 + cell_z * 104729 + #name)
        made = make(cell_x, cell_z)
        if made == nil then
          made = false
        end
        cells[key] = made
      end
      return made or nil
    end
    function plan.get(_, cell_x, cell_z)
      return cell(cell_x, cell_z)
    end
    function plan.at(_, x, z)
      local cell_x, cell_z = x // size, z // size
      return cell(cell_x, cell_z), cell_x, cell_z
    end
    function plan.size()
      return size
    end
    return plan
  end
  return terrain
end
"#;

impl TestContext {
    pub(crate) fn shared(&self) -> &Rc<crate::Shared> {
        &self.game.shared
    }

    pub(crate) fn harness(&self) -> ApiResult<Rc<dyn TestHarness>> {
        self.shared()
            .tests
            .borrow()
            .harness
            .clone()
            .ok_or_else(|| ApiError("nf.test is only there in a test run".into()))
    }

    fn events(&self) -> ApiResult<Rc<dyn PlatformEvents>> {
        self.harness()?
            .events()
            .ok_or_else(|| ApiError("the test's server isn't running".into()))
    }

    /// Waits until what scripts started off the main thread is done, so a test sees it land at a known tick.
    pub(crate) fn settle(&self) -> ApiResult<()> {
        let started = Instant::now();
        while !self.shared().wasm.borrow().quiet() {
            if started.elapsed() > SETTLE {
                return err(format!(
                    "the runtime's workers or WebAssembly were still busy after {}ms",
                    SETTLE.as_millis()
                ));
            }
            std::thread::sleep(Duration::from_millis(1));
        }
        Ok(())
    }

    /// Runs `ticks` whole server ticks, each call in them with its own budget, as on a server.
    pub(crate) fn run_ticks(&self, ticks: u64) -> ApiResult<()> {
        let harness = self.harness()?;
        let host = self.game.scripts.host();
        for _ in 0..ticks {
            self.settle()?;
            self.join_sent()?;
            host.detached(|| {
                // What scripts asked of the network since the last tick is answered now, so it lands this tick.
                super::answer_queued(self.shared(), &self.game.scripts);
                harness.tick();
            });
        }
        Ok(())
    }

    /// What `f` does with the far end of `connection` (from `nf.test.connections`), which the test plays.
    fn far(
        &self,
        connection: &handles::Handle,
        f: impl FnOnce(&mut crate::http::connections::Far) -> Result<(), String>,
    ) -> ApiResult<()> {
        let number = match connection {
            handles::Handle::HttpStream(h) => h.number,
            handles::Handle::WebSocket(h) => h.number,
            _ => {
                return err(
                    "bad argument 'connection' (a connection scripts opened in this test expected)",
                );
            }
        };
        let mut http = self.shared().http.borrow_mut();
        if http.connections.recorded(number).is_none() {
            return err(
                "bad argument 'connection' (a connection scripts opened in this test expected)",
            );
        }
        // One the owner's limits refused never reached the transport: it's already ending.
        let Some(far) = http.connections.far(number) else {
            return err("bad argument 'connection' (it never connected: it ended \"limit\")");
        };
        f(far).map_err(ApiError)
    }

    // ---- the test's network: its other servers, which the test plays --------------------------------------------

    fn network(&self) -> crate::network::service::NetworkContext {
        crate::network::service::NetworkContext {
            shared: self.shared().clone(),
            scripts: self.game.scripts.clone(),
        }
    }

    /// The test's network's hub, in this process.
    fn hub(&self) -> ApiResult<Rc<crate::network::local::LocalHub>> {
        self.network()
            .link()
            .and_then(|l| l.local())
            .ok_or_else(|| ApiError("this test's server isn't in its network".into()))
    }

    /// `name` (a channel or map) as the network names it, from the calling package.
    fn full(&self, caller: &Caller, name: &str, what: &str) -> ApiResult<String> {
        let network = self.network();
        network.resolve(&network.namespace(caller.scope), name, what)
    }

    /// The test's server `name`, added to the network if it isn't in it yet (an instance of `group` then).
    fn peer(
        &self,
        name: &str,
        group: Option<String>,
        capacity: Option<i32>,
        state: Lifecycle,
        accepting: bool,
    ) -> ApiResult<Rc<crate::network::local::TestPeer>> {
        self.harness()?;
        if !crate::network::protocol::is_server(name) {
            return err(format!(
                "bad argument 'server' (a server's name expected: letters, digits, _ . -; got \"{name}\")"
            ));
        }
        let link = self.network().link();
        if link.as_ref().and_then(|l| l.name()).as_deref() == Some(name) {
            return err(format!("bad argument 'server' (\"{name}\" is this server)"));
        }
        if let Some(peer) = self.shared().tests.borrow().peers.get(name) {
            return Ok(peer.clone());
        }
        let peer = Rc::new(self.hub()?.peer(name, group, capacity, state));
        if !accepting {
            peer.status(state, false);
        }
        self.statuses
            .borrow_mut()
            .insert(name.to_owned(), (state, accepting));
        self.shared()
            .tests
            .borrow_mut()
            .peers
            .insert(name.to_owned(), peer.clone());
        Ok(peer)
    }

    /// The players the network sent here since the last time, joined as they'd arrive.
    pub(crate) fn join_sent(&self) -> ApiResult<()> {
        let Ok(hub) = self.hub() else { return Ok(()) };
        let harness = self.harness()?;
        for (_, name) in hub.take_joins() {
            harness.join(&name);
        }
        Ok(())
    }

    /// `player` as the server knows them: one online, or an error (a test clicks only for someone there).
    fn online(&self, player: &handles::Player) -> ApiResult<PlayerRef> {
        let Some(uuid) = crate::events::parse_uuid(&player.id) else {
            return err(format!(
                "bad argument 'player' ({} isn't a player's id)",
                player.id
            ));
        };
        self.shared()
            .platform
            .players()
            .get(uuid)
            .ok_or_else(|| ApiError("bad argument 'player' (they aren't online)".into()))
    }

    /// The block a click is on, as the server says it: what's there now, in a loaded chunk.
    fn block_ref(&self, block: &handles::Block) -> ApiResult<BlockRef> {
        let (x, y, z) = crate::world::unpack(block.position);
        let Some(snapshot) = self
            .shared()
            .platform
            .blocks()
            .get(block.world.clone(), x, y, z)
        else {
            return err("bad argument 'options.block' (its chunk isn't loaded)");
        };
        let state = snapshot.state;
        let id = netherforge_format::block_state::BlockState::parse(&state)
            .map_or_else(|| state.clone(), |s| s.id);
        Ok(BlockRef {
            world: block.world.clone(),
            x,
            y,
            z,
            id,
            state,
        })
    }
}

impl NfTestApi for TestContext {
    fn case(&self, caller: &Caller, name: String, callback: Function) -> ApiResult<()> {
        let host = self.game.scripts.host();
        let file = host
            .scope_name(caller.scope)
            .and_then(|n| n.strip_prefix("test ").map(str::to_owned));
        self.shared()
            .tests
            .borrow_mut()
            .register(caller.scope, file, name, callback)
            .map_err(ApiError)
    }

    fn advance(&self, _caller: &Caller, ticks: i64) -> ApiResult<()> {
        if ticks < 1 {
            return err(format!(
                "bad argument 'ticks' (at least 1 expected, got {ticks})"
            ));
        }
        self.run_ticks(ticks as u64)?;
        // A request nobody answered in those ticks fails the test here.
        match self.shared().tests.borrow_mut().take_unanswered() {
            Some(message) => Err(ApiError(message)),
            None => Ok(()),
        }
    }

    fn player(&self, _caller: &Caller, name: String) -> ApiResult<handles::Player> {
        check_name(&name)?;
        let joined = self
            .harness()?
            .join(&name)
            .ok_or_else(|| ApiError(format!("{name} couldn't join")))?;
        Ok(handles::Player {
            id: crate::events::uuid_text(&joined.uuid),
        })
    }

    fn raise(
        &self,
        _caller: &Caller,
        event: String,
        payload: Value,
    ) -> ApiResult<shapes::RaisedEvent> {
        let Some(raisable) = RAISABLE.iter().find(|r| r.event == event) else {
            return err(format!(
                "bad argument 'event' (no event \"{event}\" can be raised)"
            ));
        };
        let lua = self.game.scripts.host().lua().clone();
        let fields = match (raisable.payload, payload) {
            (Some(_), Value::Nil) => {
                return err(format!(
                    "event \"{event}\" needs a payload: a table of {}",
                    raisable.fields.join(", ")
                ));
            }
            (Some(read), given) => match read(&lua, given)? {
                Value::Table(t) => t,
                _ => return err(format!("event \"{event}\" needs a payload")),
            },
            (None, Value::Nil) => lua.create_table().map_err(|e| ApiError(e.to_string()))?,
            (None, _) => return err(format!("event \"{event}\" has no payload")),
        };
        let mut path: Vec<(Target, &str)> = Vec::with_capacity(2);
        if let Some(first) = raisable.first {
            let held: Value = fields.raw_get(first.field).unwrap_or(Value::Nil);
            let target = match (first.via, held) {
                (Via::Handle, Value::UserData(u)) => Some(u),
                (Via::BlockWorld, Value::UserData(u)) => {
                    netherforge_api::handle::read::<handles::Block>(&u).and_then(|b| {
                        netherforge_api::handle::push(&lua, &handles::World { name: b.world }).ok()
                    })
                }
                (Via::LocationWorld, Value::Table(t)) => t
                    .raw_get::<Option<String>>("world")
                    .ok()
                    .flatten()
                    .and_then(|name| {
                        netherforge_api::handle::push(&lua, &handles::World { name }).ok()
                    }),
                _ => None,
            };
            if let Some(target) = target {
                path.push((Target::Handle(target), first.event));
            }
        }
        path.push((Target::Nf, raisable.event));
        let delivered = self.game.scripts.raise_path(&path, fields, false);
        Ok(shapes::RaisedEvent {
            cancelled: delivered.cancelled,
            event: Value::Table(delivered.fields),
        })
    }

    fn terrain(&self, _caller: &Caller, options: Value) -> ApiResult<Value> {
        if !matches!(options, Value::Nil | Value::Table(_)) {
            return err(format!(
                "bad argument 'options' (table expected, got {})",
                netherforge_api::codec::type_name(&options)
            ));
        }
        let lua = self.game.scripts.host().lua().clone();
        const KEY: &str = "netherforge.test.terrain";
        let make: Function = match lua.named_registry_value::<Option<Function>>(KEY) {
            Ok(Some(f)) => f,
            _ => {
                let f: Function = lua
                    .load(TERRAIN)
                    .set_name("=nf/test.terrain")
                    .call(())
                    .map_err(|e| ApiError(e.to_string()))?;
                let _ = lua.set_named_registry_value(KEY, f.clone());
                f
            }
        };
        make.call(options)
            .map_err(|e| ApiError(netherforge_luau::message_of(&e)))
    }

    fn click(
        &self,
        _caller: &Caller,
        player: handles::Player,
        button: String,
        options: Option<shapes::TestClickOptions>,
    ) -> ApiResult<bool> {
        let player = self.online(&player)?;
        let options = options.unwrap_or(shapes::TestClickOptions {
            hand: None,
            block: None,
            face: None,
        });
        let hand = options.hand.unwrap_or_else(|| "main_hand".into());
        let block = match &options.block {
            Some(b) => Some(self.block_ref(b)?),
            None => None,
        };
        if options.face.is_some() && block.is_none() {
            return err("bad argument 'options.face' (only with options.block)");
        }
        let face = block
            .as_ref()
            .map(|_| options.face.unwrap_or_else(|| "up".into()));
        let item = self
            .shared()
            .platform
            .world_entities()
            .equipment(player.uuid, hand.clone());
        let events = self.events()?;
        let clicked = if button == "left" {
            ClickButton::Left
        } else {
            ClickButton::Right
        };
        // As the server does: the click, then (a right one with an item, let through) the item's use.
        if events.player_interact(
            player.clone(),
            clicked,
            block,
            face,
            item.clone(),
            hand.clone(),
        ) {
            return Ok(false);
        }
        if let (ClickButton::Right, Some(item)) = (clicked, item)
            && events.player_use_item(player, item, hand)
        {
            return Ok(false);
        }
        Ok(true)
    }

    fn release(&self, _caller: &Caller, player: handles::Player, ticks: i64) -> ApiResult<()> {
        if ticks < 0 {
            return err(format!(
                "bad argument 'ticks' (0 or more expected, got {ticks})"
            ));
        }
        let player = self.online(&player)?;
        let entities = self.shared().platform.world_entities();
        let Some(item) = entities
            .equipment(player.uuid, "main_hand".into())
            .or_else(|| entities.equipment(player.uuid, "off_hand".into()))
        else {
            return err(format!(
                "{} has nothing in their hands to stop using",
                player.name
            ));
        };
        self.events()?
            .game()
            .player_stop_using_item(&mut PlayerStopUsingItem {
                player,
                item,
                ticks: ticks.min(i32::MAX as i64) as i32,
            });
        Ok(())
    }

    fn input(
        &self,
        _caller: &Caller,
        player: handles::Player,
        held: shapes::TestInputKeys,
    ) -> ApiResult<()> {
        let player = self.online(&player)?;
        self.events()?.game().player_input(&mut PlayerInput {
            player,
            forward: held.forward == Some(true),
            backward: held.backward == Some(true),
            left: held.left == Some(true),
            right: held.right == Some(true),
            jump: held.jump == Some(true),
            sneak: held.sneak == Some(true),
            sprint: held.sprint == Some(true),
        });
        Ok(())
    }

    fn shown(
        &self,
        _caller: &Caller,
        player: handles::Player,
        slot: unions::IntegerOrChoice,
        options: Option<shapes::TestShownOptions>,
    ) -> ApiResult<Option<values::Item>> {
        let player = self.online(&player)?;
        let index = match slot {
            unions::IntegerOrChoice::Integer(i) if i < 0 => {
                return err(format!("bad argument 'slot' (0 or more expected, got {i})"));
            }
            unions::IntegerOrChoice::Integer(i) => Some(i.min(i32::MAX as i64) as i32),
            unions::IntegerOrChoice::Choice(_) => None,
        };
        let menu = options.and_then(|o| o.menu) == Some(true);
        Ok(self
            .harness()?
            .shown(player.uuid, index, menu)
            .map(|item| crate::items::to_value(&item)))
    }

    fn connections(
        &self,
        _caller: &Caller,
        url: Option<String>,
    ) -> ApiResult<Vec<handles::Handle>> {
        let http = self.shared().http.borrow();
        Ok(http
            .connections
            .history(url.as_deref())
            .into_iter()
            .map(|r| {
                if r.websocket {
                    handles::Handle::WebSocket(handles::WebSocket {
                        number: r.number,
                        url: r.url.clone(),
                    })
                } else {
                    handles::Handle::HttpStream(handles::HttpStream {
                        number: r.number,
                        url: r.url.clone(),
                    })
                }
            })
            .collect())
    }

    fn accept(
        &self,
        _caller: &Caller,
        connection: handles::Handle,
        options: Option<shapes::TestAcceptOptions>,
    ) -> ApiResult<()> {
        let status = options.as_ref().and_then(|o| o.status);
        let headers = options
            .as_ref()
            .and_then(|o| o.headers.clone())
            .unwrap_or_default();
        let protocol = options.and_then(|o| o.protocol);
        self.far(&connection, |far| far.accept(status, headers, protocol))
    }

    fn receive(
        &self,
        _caller: &Caller,
        connection: handles::Handle,
        text: String,
    ) -> ApiResult<()> {
        self.far(&connection, |far| far.receive(&text))
    }

    fn sent(&self, _caller: &Caller, connection: handles::WebSocket) -> ApiResult<Vec<String>> {
        let http = self.shared().http.borrow();
        http.connections
            .recorded(connection.number)
            .map(|r| r.sent.clone())
            .ok_or_else(|| {
                ApiError(
                    "bad argument 'connection' (a connection scripts opened in this test expected)"
                        .into(),
                )
            })
    }

    fn request(
        &self,
        _caller: &Caller,
        method: String,
        path: String,
        options: Option<shapes::TestRequestOptions>,
    ) -> ApiResult<shapes::TestWebResponse> {
        let verb = method.to_uppercase();
        if verb.is_empty() || verb.len() > 16 || !verb.chars().all(|c| c.is_ascii_uppercase()) {
            return err(format!(
                "bad argument 'method' (an HTTP method expected, got \"{method}\")"
            ));
        }
        if !path.starts_with('/') {
            return err(format!(
                "bad argument 'path' (a path starting with / expected, got \"{path}\")"
            ));
        }
        let options = options.unwrap_or(shapes::TestRequestOptions {
            headers: None,
            body: None,
            ip: None,
        });
        let headers = options
            .headers
            .unwrap_or_default()
            .into_iter()
            .map(|(k, v)| (k.to_lowercase(), v))
            .collect();
        let (raw_path, query) = match path.split_once('?') {
            Some((p, q)) => (p.to_owned(), Some(q.to_owned())),
            None => (path.clone(), None),
        };
        let head = verb == "HEAD";
        let (call, answer) = crate::web::gate::WebCall::new(
            verb,
            raw_path,
            query,
            headers,
            options.body.unwrap_or_default().into_bytes(),
            options.ip.unwrap_or_else(|| "127.0.0.1".into()),
        );
        crate::web::WebContext {
            shared: self.shared().clone(),
            scripts: self.game.scripts.clone(),
        }
        .route(call.clone());
        // Answered later: the server runs on, as a client would wait, until the handler answers or times out.
        let limit = self.shared().web.borrow().config.handler_timeout_seconds * 20 + 1;
        let mut waited = 0;
        while !call.is_answered() && waited < limit {
            waited += 1;
            self.run_ticks(1)?;
        }
        let got = answer
            .try_recv()
            .unwrap_or_else(|_| crate::web::gate::WebAnswer::text(504, "never answered", &[]));
        Ok(shapes::TestWebResponse {
            status: i64::from(got.status),
            headers: got.headers.into_iter().collect(),
            body: if head {
                String::new()
            } else {
                String::from_utf8_lossy(&got.body).into_owned()
            },
        })
    }

    fn oauth_token(
        &self,
        _caller: &Caller,
        flow: handles::OAuthFlow,
        response: Value,
    ) -> ApiResult<()> {
        let host = self.game.scripts.host();
        let json = super::json_of(&host, &response, "response").map_err(ApiError)?;
        let Some(json @ serde_json::Value::Object(_)) = json else {
            return err("bad argument 'response' (a table of fields expected)");
        };
        let mut web = self.shared().web.borrow_mut();
        let flow = web
            .flows
            .find_mut(flow.number)
            .ok_or_else(|| ApiError("bad argument 'flow' (it has gone)".into()))?;
        flow.test_token = Some(json);
        Ok(())
    }

    fn http(&self, caller: &Caller, handler: Function) -> ApiResult<()> {
        self.harness()?;
        self.shared().tests.borrow_mut().http = Some((caller.scope, handler));
        Ok(())
    }

    fn allow_private(
        &self,
        _caller: &Caller,
        allowance: shapes::TestPrivateAllowance,
    ) -> ApiResult<()> {
        self.harness()?;
        // Read as the owner's entry is (`http.allow-private`), so a test allows only what a server could.
        let namespace = allowance.package.trim().to_owned();
        if !netherforge_format::names::is_namespace(&namespace) {
            return err(format!(
                "bad argument 'allowance' (package: \"{namespace}\" isn't a package's namespace)"
            ));
        }
        let host = allowance.host.trim().to_lowercase();
        if host.is_empty() || host.chars().any(|c| c.is_whitespace() || c == '/') {
            return err(format!(
                "bad argument 'allowance' (host: \"{}\" isn't an address or a host name)",
                allowance.host
            ));
        }
        if !(1..=65535).contains(&allowance.port) {
            return err(format!(
                "bad argument 'allowance' (port: from 1 to 65535 expected, got {})",
                allowance.port
            ));
        }
        let rate = allowance.requests_per_minute;
        if let Some(rate) = rate
            && !(0..=i64::from(i32::MAX)).contains(&rate)
        {
            return err(format!(
                "bad argument 'allowance' (requests_per_minute: a whole number, 0 for no limit, expected, got {rate})"
            ));
        }
        self.shared()
            .tests
            .borrow_mut()
            .allowances
            .push(crate::http::PrivateAllowance {
                namespace,
                host: host
                    .trim_start_matches('[')
                    .trim_end_matches(']')
                    .to_owned(),
                port: allowance.port as u16,
                requests_per_minute: rate.map(|r| r as usize),
            });
        Ok(())
    }

    fn disconnect(
        &self,
        _caller: &Caller,
        connection: handles::Handle,
        options: Option<shapes::TestDisconnectOptions>,
    ) -> ApiResult<()> {
        let code = options.as_ref().and_then(|o| o.code);
        if let Some(code) = code
            && !(1000..=4999).contains(&code)
        {
            return err(format!(
                "bad argument 'options.code' (1000 to 4999 expected, got {code})"
            ));
        }
        let reason = options.as_ref().and_then(|o| o.reason.clone());
        let error = options.and_then(|o| o.error);
        self.far(&connection, |far| {
            far.disconnect(code.map(|c| c as u16), reason, error)
        })
    }

    fn stub_wasm(
        &self,
        caller: &Caller,
        module: String,
        stub: shapes::TestWasmStub,
    ) -> ApiResult<()> {
        self.harness()?;
        let wasm = crate::wasm::api::WasmApi {
            game: self.game.clone(),
        };
        let key = wasm.resolve(caller.scope, &module, "nf.test.stub_wasm")?;
        self.shared()
            .wasm
            .borrow_mut()
            .stubs
            .insert(key, (caller.scope, stub));
        Ok(())
    }

    fn network_server(
        &self,
        _caller: &Caller,
        name: String,
        options: Option<shapes::TestNetworkServerOptions>,
    ) -> ApiResult<()> {
        if let Some(group) = options.as_ref().and_then(|o| o.group.as_ref())
            && !crate::network::protocol::is_server(group)
        {
            return err(format!(
                "bad argument 'options.group' (a group's name expected: letters, digits, _ . -; got \"{group}\")"
            ));
        }
        let capacity = options.as_ref().and_then(|o| o.capacity);
        if let Some(capacity) = capacity
            && capacity < 1
        {
            return err(format!(
                "bad argument 'options.capacity' (at least 1 expected, got {capacity})"
            ));
        }
        let state = options
            .as_ref()
            .and_then(|o| o.state.as_deref())
            .map_or(Lifecycle::Ready, lifecycle);
        let accepting = options.as_ref().and_then(|o| o.accepting).unwrap_or(true);
        self.peer(
            &name,
            options.and_then(|o| o.group),
            capacity.map(|c| c.min(i64::from(i32::MAX)) as i32),
            state,
            accepting,
        )?;
        Ok(())
    }

    fn network_publish(
        &self,
        caller: &Caller,
        server: String,
        channel: String,
        value: Value,
    ) -> ApiResult<()> {
        if value.is_nil() {
            return err("bad argument 'value' (a value expected, got nil)");
        }
        let json = self.network().required(&value, "value")?;
        if let Some(client) = server.strip_prefix(ADMIN_PREFIX) {
            // A service outside the network, through the hub's admin API: heard from "admin:<client>".
            self.harness()?;
            if !crate::network::protocol::is_credential(client) {
                return err(format!(
                    "bad argument 'server' (an admin client's id expected after admin: letters, digits, _ . -; got \"{client}\")"
                ));
            }
            let full = self.full(caller, &channel, "nf.test.network_publish")?;
            if let Some(why) = self.hub()?.admin_publish(client, &full, json) {
                return err(format!("nf.test.network_publish: {why}"));
            }
            return Ok(());
        }
        let peer = self.peer(&server, None, None, Lifecycle::Ready, true)?;
        let full = self.full(caller, &channel, "nf.test.network_publish")?;
        peer.send(ServerFrame::Publish {
            channel: full,
            value: json,
            to: None,
            group: None,
        });
        Ok(())
    }

    fn network_received(
        &self,
        caller: &Caller,
        server: String,
        channel: Option<String>,
    ) -> ApiResult<Vec<shapes::TestNetworkMessage>> {
        let peer = self.peer(&server, None, None, Lifecycle::Ready, true)?;
        let wanted = match &channel {
            Some(c) => Some(self.full(caller, c, "nf.test.network_received")?),
            None => None,
        };
        if let Some(wanted) = &wanted {
            peer.listen(wanted);
        }
        let reader = self.network().namespace(caller.scope);
        let lua = self.game.scripts.host().lua().clone();
        let log = peer.log.borrow();
        Ok(log
            .received
            .iter()
            .filter(|(c, _, _)| wanted.as_ref().is_none_or(|w| w == c))
            .map(|(c, from, value)| shapes::TestNetworkMessage {
                channel: crate::network::service::NetworkContext::spell(c, &reader),
                value: crate::network::service::NetworkContext::to_lua(&lua, Some(value))
                    .unwrap_or(Value::Nil),
                server: from.clone(),
            })
            .collect())
    }

    fn network_answer(
        &self,
        caller: &Caller,
        server: String,
        channel: String,
        callback: Function,
    ) -> ApiResult<()> {
        let peer = self.peer(&server, None, None, Lifecycle::Ready, true)?;
        let full = self.full(caller, &channel, "nf.test.network_answer")?;
        // It listens to the channel, so requests on it are routed to it.
        peer.listen(&full);
        self.shared()
            .tests
            .borrow_mut()
            .peer_answers
            .insert((peer.name.clone(), full), (caller.scope, callback));
        Ok(())
    }

    fn network_request(
        &self,
        caller: &Caller,
        server: String,
        channel: String,
        value: Value,
    ) -> ApiResult<Value> {
        if value.is_nil() {
            return err("bad argument 'value' (a value expected, got nil)");
        }
        let json = self.network().required(&value, "value")?;
        let peer = self.peer(&server, None, None, Lifecycle::Ready, true)?;
        let full = self.full(caller, &channel, "nf.test.network_request")?;
        let link = self
            .network()
            .link()
            .ok_or_else(|| ApiError("this server isn't in a network".into()))?;
        let id = link.next_id();
        peer.send(ServerFrame::Request {
            id,
            channel: full,
            to: link.name(),
            value: json,
            group: None,
            admin: None,
        });
        let most = self
            .shared()
            .network
            .borrow()
            .config
            .request_timeout_seconds
            * 20;
        let lua = self.game.scripts.host().lua().clone();
        for _ in 0..most {
            let reply = peer.log.borrow_mut().replies.remove(&id);
            if let Some((value, error)) = reply {
                if let Some(error) = error {
                    return err(format!(
                        "nf.test.network_request: the request failed: {error}"
                    ));
                }
                return crate::network::service::NetworkContext::to_lua(&lua, value.as_ref())
                    .map_err(|e| ApiError(e.to_string()));
            }
            self.run_ticks(1)?;
        }
        err(format!(
            "nf.test.network_request: this server didn't answer within {most} ticks"
        ))
    }

    fn network_write(
        &self,
        caller: &Caller,
        server: String,
        map: String,
        key: String,
        value: Value,
    ) -> ApiResult<()> {
        let json = self.network().json(&value, "value")?;
        let peer = self.peer(&server, None, None, Lifecycle::Ready, true)?;
        let full = self.full(caller, &map, "nf.test.network_write")?;
        let op = if json.is_some() {
            MapOp::Set
        } else {
            MapOp::Delete
        };
        peer.send(ServerFrame::MapWrite {
            id: 0,
            map: full,
            key,
            op,
            value: json,
            amount: None,
            expires_millis: None,
        });
        Ok(())
    }

    fn network_join(
        &self,
        _caller: &Caller,
        server: String,
        name: String,
    ) -> ApiResult<shapes::NetworkPlayer> {
        check_name(&name)?;
        let peer = self.peer(&server, None, None, Lifecycle::Ready, true)?;
        let id = crate::events::uuid_text(&super::player_id(&name));
        self.hub()?.place(&id, &name, &peer.name);
        Ok(shapes::NetworkPlayer {
            id,
            name,
            server: Some(peer.name.clone()),
            proxy: None,
        })
    }

    fn network_quit(&self, _caller: &Caller, name: String) -> ApiResult<()> {
        self.harness()?;
        let id = crate::events::uuid_text(&super::player_id(&name));
        self.hub()?.leave(&id);
        Ok(())
    }

    fn network_status(
        &self,
        _caller: &Caller,
        server: String,
        status: shapes::TestNetworkStatus,
    ) -> ApiResult<()> {
        let peer = self.peer(&server, None, None, Lifecycle::Ready, true)?;
        let mut statuses = self.statuses.borrow_mut();
        let (was_state, was_accepting) = statuses
            .get(&peer.name)
            .copied()
            .unwrap_or((Lifecycle::Ready, true));
        let now = (
            status.state.as_deref().map_or(was_state, lifecycle),
            status.accepting.unwrap_or(was_accepting),
        );
        statuses.insert(peer.name.clone(), now);
        drop(statuses);
        peer.status(now.0, now.1);
        Ok(())
    }

    fn network_role(&self, _caller: &Caller, role: shapes::TestNetworkRole) -> ApiResult<()> {
        self.harness()?;
        let shares = role
            .shares
            .unwrap_or_default()
            .iter()
            .map(|s| share(s))
            .collect::<Result<Vec<_>, String>>()
            .map_err(|e| ApiError(format!("bad argument 'role.shares' ({e})")))?;
        let owns = role.owns.unwrap_or_default();
        if let Some(bad) = owns
            .iter()
            .find(|o| !crate::network::protocol::is_namespace(o))
        {
            return err(format!(
                "bad argument 'role.owns' (\"{bad}\" isn't a namespace)"
            ));
        }
        let grants = preset(
            &role.role,
            owns,
            shares,
            role.presence,
            role.send_to,
            role.moderation.unwrap_or(false),
        )
        .map_err(|e| ApiError(format!("bad argument 'role.role' ({e})")))?;
        self.hub()?.rejoin_here(grants);
        Ok(())
    }

    fn network_reports(&self, _caller: &Caller) -> ApiResult<Vec<shapes::TestNetworkReport>> {
        self.harness()?;
        let hub = self.hub()?;
        // The hub's newest first; the test's oldest first.
        let mut reports = hub.reports();
        reports.reverse();
        let lua = self.game.scripts.host().lua().clone();
        Ok(reports
            .into_iter()
            .map(|r| shapes::TestNetworkReport {
                player: r.name,
                reason: r.reason,
                details: crate::network::service::NetworkContext::to_lua(&lua, r.details.as_ref())
                    .unwrap_or(Value::Nil),
                by: r.by,
            })
            .collect())
    }

    fn network_send(
        &self,
        _caller: &Caller,
        server: String,
        name: String,
        data: Value,
    ) -> ApiResult<handles::Player> {
        check_name(&name)?;
        let peer = self.peer(&server, None, None, Lifecycle::Ready, true)?;
        let json = self.network().json(&data, "data")?;
        let id = crate::events::uuid_text(&super::player_id(&name));
        let hub = self.hub()?;
        // They're on the sending server first, as a player sent from it is.
        hub.place(&id, &name, &peer.name);
        let here = self.network().link().and_then(|l| l.name());
        peer.send(ServerFrame::Send {
            id: 0,
            players: vec![id],
            server: here,
            data: json,
            group: None,
            balance: None,
            wait_millis: None,
        });
        self.join_sent()?;
        let joined = self
            .shared()
            .platform
            .players()
            .online()
            .into_iter()
            .find(|p| p.name == name)
            .ok_or_else(|| ApiError(format!("{name} couldn't be sent here")))?;
        Ok(handles::Player {
            id: crate::events::uuid_text(&joined.uuid),
        })
    }
}
