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
            host.detached(|| harness.tick());
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

    fn connections(&self, caller: &Caller, url: Option<String>) -> ApiResult<Vec<handles::Handle>> {
        NfTestApi::connections(&Unported, caller, url)
    }

    fn accept(
        &self,
        caller: &Caller,
        connection: handles::Handle,
        options: Option<shapes::TestAcceptOptions>,
    ) -> ApiResult<()> {
        NfTestApi::accept(&Unported, caller, connection, options)
    }

    fn receive(&self, caller: &Caller, connection: handles::Handle, text: String) -> ApiResult<()> {
        NfTestApi::receive(&Unported, caller, connection, text)
    }

    fn sent(&self, caller: &Caller, connection: handles::WebSocket) -> ApiResult<Vec<String>> {
        NfTestApi::sent(&Unported, caller, connection)
    }

    fn request(
        &self,
        caller: &Caller,
        method: String,
        path: String,
        options: Option<shapes::TestRequestOptions>,
    ) -> ApiResult<shapes::TestWebResponse> {
        NfTestApi::request(&Unported, caller, method, path, options)
    }

    fn oauth_token(
        &self,
        caller: &Caller,
        flow: handles::OAuthFlow,
        response: Value,
    ) -> ApiResult<()> {
        NfTestApi::oauth_token(&Unported, caller, flow, response)
    }

    fn http(&self, caller: &Caller, handler: Function) -> ApiResult<()> {
        NfTestApi::http(&Unported, caller, handler)
    }

    fn allow_private(
        &self,
        caller: &Caller,
        allowance: shapes::TestPrivateAllowance,
    ) -> ApiResult<()> {
        NfTestApi::allow_private(&Unported, caller, allowance)
    }

    fn disconnect(
        &self,
        caller: &Caller,
        connection: handles::Handle,
        options: Option<shapes::TestDisconnectOptions>,
    ) -> ApiResult<()> {
        NfTestApi::disconnect(&Unported, caller, connection, options)
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
