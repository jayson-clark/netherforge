//! `nf.rooms` and `Room`: a room's scripts reaching the server's other rooms (ROADMAP.md phase 2). What a room's
//! state is lives in the instance (`RoomInfo`); what changes the session's rooms (opening, closing, sending a player,
//! raising a room's events) is asked of the instance and done by the session at the end of the tick
//! (`Session::apply_room_requests`), so no room changes under a running script.

use std::rc::Rc;

use netherforge_api::{ApiError, ApiResult, Caller, handles, shapes};

use crate::instance::RoomId;
use crate::shared::Shared;

/// Where a room is in its life.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RoomState {
    /// Asked for this tick; open at its end.
    Opening,
    Open,
    Started,
    Finished,
    /// Asked to close this tick; gone at its end.
    Closing,
}

impl RoomState {
    pub fn name(self) -> &'static str {
        match self {
            RoomState::Opening => "opening",
            RoomState::Open => "open",
            RoomState::Started => "started",
            RoomState::Finished => "finished",
            RoomState::Closing => "closing",
        }
    }
}

/// What the instance knows of a room: what it's for, whether what it makes outlasts it, and where it is in its life.
#[derive(Clone, Debug)]
pub struct RoomInfo {
    /// Its identity (an owner's id, a match's): one open room at a time has it.
    pub key: Option<String>,
    /// Whether its worlds are saved when it closes and its `nf.data` tables kept under its key (an owner's world, a
    /// party's), rather than going with it (a match's). Only a room with a key is kept.
    pub keep: bool,
    pub state: RoomState,
}

/// What a room's scripts asked of the session's rooms, done at the end of the tick.
#[derive(Clone, Debug)]
pub enum RoomRequest {
    Open(RoomId),
    Close(RoomId),
    Send {
        player: netherforge_platform::Uuid,
        to: RoomId,
    },
    /// Raise `event` (`room_start`, `room_finish`) in room `id`.
    Raise {
        id: RoomId,
        event: &'static str,
    },
}

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// `nf.rooms` and `Room`, for one room's scripts.
#[derive(Clone)]
pub struct RoomsContext {
    pub shared: Rc<Shared>,
}

fn handle(id: RoomId) -> handles::Room {
    handles::Room {
        id: i64::from(id.0),
    }
}

fn id_of(room: &handles::Room) -> Option<RoomId> {
    u32::try_from(room.id).ok().map(RoomId)
}

impl RoomsContext {
    fn info(&self, room: &handles::Room) -> Option<(RoomId, RoomInfo)> {
        let id = id_of(room)?;
        let info = self.shared.instance.rooms.borrow().get(&id).cloned()?;
        Some((id, info))
    }

    fn ask(&self, request: RoomRequest) {
        self.shared
            .instance
            .room_requests
            .borrow_mut()
            .push(request);
    }
}

impl netherforge_api::api::NfRoomsApi for RoomsContext {
    fn current(&self, _: &Caller) -> ApiResult<handles::Room> {
        Ok(handle(self.shared.room))
    }

    fn main(&self, _: &Caller) -> ApiResult<handles::Room> {
        Ok(handle(RoomId::MAIN))
    }

    fn all(&self, _: &Caller) -> ApiResult<Vec<handles::Room>> {
        Ok(self
            .shared
            .instance
            .rooms
            .borrow()
            .keys()
            .copied()
            .map(handle)
            .collect())
    }

    fn get(&self, _: &Caller, id: i64) -> ApiResult<Option<handles::Room>> {
        let room = handles::Room { id };
        Ok(self.info(&room).map(|_| room))
    }

    fn open(&self, _: &Caller, options: Option<shapes::RoomOptions>) -> ApiResult<handles::Room> {
        let (key, keep) = options.map_or((None, false), |o| (o.key, o.keep.unwrap_or(false)));
        let id = self
            .shared
            .instance
            .reserve_room(key, keep)
            .map_err(|why| err(format!("bad argument 'options' ({why})")))?;
        self.ask(RoomRequest::Open(id));
        Ok(handle(id))
    }
}

impl netherforge_api::api::RoomApi for RoomsContext {
    fn id(&self, this: &handles::Room) -> ApiResult<i64> {
        Ok(this.id)
    }

    fn key(&self, this: &handles::Room) -> ApiResult<Option<String>> {
        Ok(self.info(this).and_then(|(_, info)| info.key))
    }

    fn is_kept(&self, this: &handles::Room) -> ApiResult<bool> {
        Ok(self.info(this).is_some_and(|(_, info)| info.keep))
    }

    fn is_main(&self, this: &handles::Room) -> ApiResult<bool> {
        Ok(this.id == 0)
    }

    fn state(&self, this: &handles::Room) -> ApiResult<String> {
        Ok(match self.info(this) {
            // A room closing is still there until the end of the tick, but it's no use: it says it's closed.
            Some((_, info)) if info.state != RoomState::Closing => info.state.name(),
            _ => "closed",
        }
        .to_owned())
    }

    fn players(&self, this: &handles::Room) -> ApiResult<Vec<handles::Player>> {
        let Some((id, _)) = self.info(this) else {
            return Ok(Vec::new());
        };
        let routes = self.shared.instance.routes.borrow();
        Ok(self
            .shared
            .platform
            .players()
            .online()
            .into_iter()
            .filter(|p| routes.player(p.uuid) == id)
            .map(|p| handles::Player {
                id: crate::events::uuid_text(&p.uuid),
            })
            .collect())
    }

    fn worlds(&self, this: &handles::Room) -> ApiResult<Vec<handles::World>> {
        let Some((id, _)) = self.info(this) else {
            return Ok(Vec::new());
        };
        let routes = self.shared.instance.routes.borrow();
        Ok(self
            .shared
            .platform
            .worlds()
            .names()
            .into_iter()
            .filter(|w| routes.world(w) == id)
            .map(|name| handles::World { name })
            .collect())
    }

    fn start(&self, this: &handles::Room) -> ApiResult<bool> {
        self.change(
            this,
            &[RoomState::Opening, RoomState::Open],
            RoomState::Started,
            "room_start",
        )
    }

    fn finish(&self, this: &handles::Room) -> ApiResult<bool> {
        self.change(
            this,
            &[RoomState::Open, RoomState::Started],
            RoomState::Finished,
            "room_finish",
        )
    }

    fn close(&self, this: &handles::Room) -> ApiResult<bool> {
        let Some((id, info)) = self.info(this) else {
            return Ok(false);
        };
        if id == RoomId::MAIN || info.state == RoomState::Closing {
            return Ok(false);
        }
        self.set_state(id, RoomState::Closing);
        self.ask(RoomRequest::Close(id));
        Ok(true)
    }

    fn send(&self, this: &handles::Room, player: handles::Player) -> ApiResult<bool> {
        let Some((id, info)) = self.info(this) else {
            return Ok(false);
        };
        let Some(uuid) = crate::events::parse_uuid(&player.id) else {
            return Ok(false);
        };
        if info.state == RoomState::Closing || self.shared.platform.players().get(uuid).is_none() {
            return Ok(false);
        }
        self.ask(RoomRequest::Send {
            player: uuid,
            to: id,
        });
        Ok(true)
    }

    fn give_world(&self, this: &handles::Room, world: handles::World) -> ApiResult<bool> {
        let Some((id, info)) = self.info(this) else {
            return Ok(false);
        };
        if info.state == RoomState::Closing
            || !self.shared.platform.worlds().exists(world.name.clone())
        {
            return Ok(false);
        }
        self.shared
            .instance
            .routes
            .borrow_mut()
            .give_world(&world.name, id);
        Ok(true)
    }
}

impl RoomsContext {
    fn set_state(&self, id: RoomId, state: RoomState) {
        if let Some(info) = self.shared.instance.rooms.borrow_mut().get_mut(&id) {
            info.state = state;
        }
    }

    /// Moves room `this` from one of `from` to `to`, its scripts hearing `event` at the end of the tick.
    fn change(
        &self,
        this: &handles::Room,
        from: &[RoomState],
        to: RoomState,
        event: &'static str,
    ) -> ApiResult<bool> {
        let Some((id, info)) = self.info(this) else {
            return Ok(false);
        };
        if !from.contains(&info.state) {
            return Ok(false);
        }
        self.set_state(id, to);
        self.ask(RoomRequest::Raise { id, event });
        Ok(true)
    }
}
