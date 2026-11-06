//! `nf.test`: what a test file runs on (Kotlin's `NfTestImpl`). Only a session with a harness installs it, so a
//! server's scripts find `nil` at `nf.test`.

use std::rc::Rc;
use std::time::{Duration, Instant};

use mlua::{Function, Value};
use netherforge_api::api::NfTestApi;
use netherforge_api::handles;
use netherforge_api::raisable::{RAISABLE, Via};
use netherforge_api::unported::Unported;
use netherforge_api::{ApiError, ApiResult, Caller, shapes, unions, values};
use netherforge_platform::game_event::{PlayerInput, PlayerStopUsingItem};
use netherforge_platform::{BlockRef, ClickButton, PlatformEvents, PlayerRef};

use super::TestHarness;
use crate::scripts::Target;
use crate::world::GameContext;

/// How long a tick waits for what scripts started on other threads (WebAssembly calls, programs) to settle.
pub const SETTLE: Duration = Duration::from_secs(10);

/// `nf.test`'s functions.
#[derive(Clone)]
pub struct TestContext {
    pub(crate) game: GameContext,
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
        NfTestApi::stub_wasm(&Unported, caller, module, stub)
    }

    fn network_server(
        &self,
        caller: &Caller,
        name: String,
        options: Option<shapes::TestNetworkServerOptions>,
    ) -> ApiResult<()> {
        NfTestApi::network_server(&Unported, caller, name, options)
    }

    fn network_publish(
        &self,
        caller: &Caller,
        server: String,
        channel: String,
        value: Value,
    ) -> ApiResult<()> {
        NfTestApi::network_publish(&Unported, caller, server, channel, value)
    }

    fn network_received(
        &self,
        caller: &Caller,
        server: String,
        channel: Option<String>,
    ) -> ApiResult<Vec<shapes::TestNetworkMessage>> {
        NfTestApi::network_received(&Unported, caller, server, channel)
    }

    fn network_answer(
        &self,
        caller: &Caller,
        server: String,
        channel: String,
        callback: Function,
    ) -> ApiResult<()> {
        NfTestApi::network_answer(&Unported, caller, server, channel, callback)
    }

    fn network_request(
        &self,
        caller: &Caller,
        server: String,
        channel: String,
        value: Value,
    ) -> ApiResult<Value> {
        NfTestApi::network_request(&Unported, caller, server, channel, value)
    }

    fn network_write(
        &self,
        caller: &Caller,
        server: String,
        map: String,
        key: String,
        value: Value,
    ) -> ApiResult<()> {
        NfTestApi::network_write(&Unported, caller, server, map, key, value)
    }

    fn network_join(
        &self,
        caller: &Caller,
        server: String,
        name: String,
    ) -> ApiResult<shapes::NetworkPlayer> {
        NfTestApi::network_join(&Unported, caller, server, name)
    }

    fn network_quit(&self, caller: &Caller, name: String) -> ApiResult<()> {
        NfTestApi::network_quit(&Unported, caller, name)
    }

    fn network_status(
        &self,
        caller: &Caller,
        server: String,
        status: shapes::TestNetworkStatus,
    ) -> ApiResult<()> {
        NfTestApi::network_status(&Unported, caller, server, status)
    }

    fn network_role(&self, caller: &Caller, role: shapes::TestNetworkRole) -> ApiResult<()> {
        NfTestApi::network_role(&Unported, caller, role)
    }

    fn network_reports(&self, caller: &Caller) -> ApiResult<Vec<shapes::TestNetworkReport>> {
        NfTestApi::network_reports(&Unported, caller)
    }

    fn network_send(
        &self,
        caller: &Caller,
        server: String,
        name: String,
        data: Value,
    ) -> ApiResult<handles::Player> {
        NfTestApi::network_send(&Unported, caller, server, name, data)
    }
}
