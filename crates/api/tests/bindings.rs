//! The generated glue end to end: a script calls the API, a fake implements a
//! few of its classes, and every other class answers that it's unported.

use std::cell::RefCell;
use std::rc::Rc;

use netherforge_api::api::*;
use netherforge_api::{
    ApiError, ApiResult, Caller, Pending, Runtime, handles, install, nf_for, pending, shapes,
    unions, values,
};
use netherforge_luau::mlua::{Function, Value};
use netherforge_luau::{Failure, Host, Limits, ScopeId};

#[derive(Default)]
struct Fake {
    callers: RefCell<Vec<ScopeId>>,
    bars: RefCell<Vec<shapes::BossBarOptions>>,
    joins: RefCell<Vec<(String, bool)>>,
}

impl Runtime for Fake {
    fn requires(&self, _: Option<ScopeId>, requirement: &str, called: &str) -> ApiResult<()> {
        if requirement == "bots" {
            return Ok(());
        }
        Err(ApiError(format!(
            "{called} needs `{requirement}` in requires"
        )))
    }
}

impl NfLooksApi for Fake {
    fn qualify(&self, caller: &Caller, look: String) -> ApiResult<String> {
        self.callers.borrow_mut().push(caller.scope);
        Ok(format!("demo:{look}"))
    }
}

impl NfStructuresApi for Fake {
    fn exists(&self, _: &Caller, id: String) -> ApiResult<bool> {
        Ok(id == "tower")
    }
    fn size(&self, _: &Caller, id: String) -> ApiResult<Option<values::Vec3>> {
        Ok((id == "tower").then_some(values::Vec3 {
            x: 3.0,
            y: 9.0,
            z: 3.0,
        }))
    }
}

impl NfBossbarsApi for Fake {
    fn create(
        &self,
        _: &Caller,
        options: Option<shapes::BossBarOptions>,
    ) -> ApiResult<handles::BossBar> {
        let mut bars = self.bars.borrow_mut();
        if let Some(options) = options {
            bars.push(options);
        }
        Ok(handles::BossBar { id: 7 })
    }
}

impl NfBotsApi for Fake {
    fn join(
        &self,
        _: &Caller,
        name: String,
        at: Option<unions::LocationOrVec3>,
        _: Option<shapes::BotJoinOptions>,
    ) -> ApiResult<Pending<handles::Bot>> {
        self.joins.borrow_mut().push((name.clone(), at.is_some()));
        if name == "nobody" {
            return Ok(Box::pin(async {
                Err(ApiError("that name is taken".into()))
            }));
        }
        Ok(Box::pin(async move { Ok(handles::Bot { id: name }) }))
    }
    fn all(&self, _: &Caller) -> ApiResult<Vec<handles::Bot>> {
        Ok(vec![])
    }
}

impl LuaApi for Fake {
    fn nf_looks(&self) -> &dyn NfLooksApi {
        self
    }
    fn nf_structures(&self) -> &dyn NfStructuresApi {
        self
    }
    fn nf_bossbars(&self) -> &dyn NfBossbarsApi {
        self
    }
    fn nf_bots(&self) -> &dyn NfBotsApi {
        self
    }
}

fn setup() -> (Host, Rc<Fake>) {
    let host = Host::new(Limits::default()).unwrap();
    let fake = Rc::new(Fake::default());
    install(&host, fake.clone()).unwrap();
    (host, fake)
}

fn scope(host: &Host, name: &str) -> ScopeId {
    let scope = host.open_scope(name, name).unwrap();
    let nf = nf_for(host.lua(), scope).unwrap();
    host.env(scope).unwrap().raw_set("nf", nf).unwrap();
    scope
}

fn run<R: netherforge_luau::mlua::FromLuaMulti>(
    host: &Host,
    scope: ScopeId,
    source: &str,
) -> Result<R, Failure> {
    host.run(scope, 1_000_000, "modules/t/init.lua", source)
}

fn error_of(result: Result<Value, Failure>) -> String {
    match result {
        Err(Failure::Error { message, .. }) => message,
        other => panic!("expected an error, got {other:?}"),
    }
}

#[test]
fn a_namespace_function_gets_its_arguments_and_its_caller() {
    let (host, fake) = setup();
    let a = scope(&host, "a");
    let b = scope(&host, "b");
    let qualified: String = run(&host, a, "return nf.looks.qualify('hat')").unwrap();
    assert_eq!(qualified, "demo:hat");
    run::<()>(&host, b, "nf.looks.qualify('x')").unwrap();
    assert_eq!(*fake.callers.borrow(), [a, b]);
}

#[test]
fn a_function_belongs_to_the_scope_whose_code_reads_it() {
    // A module's function, called from another scope's call, still calls the
    // API as the module.
    let (host, fake) = setup();
    let module = scope(&host, "module");
    let caller = scope(&host, "caller");
    let f: Function = run(
        &host,
        module,
        "return function() return nf.looks.qualify('x') end",
    )
    .unwrap();
    host.env(caller).unwrap().raw_set("from_module", f).unwrap();
    run::<()>(&host, caller, "from_module()").unwrap();
    assert_eq!(*fake.callers.borrow(), [module]);
}

#[test]
fn mistakes_say_what_was_expected() {
    let (host, _) = setup();
    let s = scope(&host, "s");
    let message = error_of(run(&host, s, "return nf.looks.qualify(5)"));
    assert!(
        message.contains("bad argument 'look' (string expected, got number)"),
        "{message}"
    );
    let message = error_of(run(
        &host,
        s,
        "return nf.bossbars.create({ colour = 'red' })",
    ));
    assert!(
        message.contains("unknown field 'options.colour' (fields: "),
        "{message}"
    );
    let message = error_of(run(
        &host,
        s,
        "return nf.bossbars.create({ color = 'mauve' })",
    ));
    assert!(
        message.contains("bad argument 'options.color' (one of "),
        "{message}"
    );
    assert!(message.contains("got \"mauve\""), "{message}");
}

#[test]
fn shapes_and_values_cross() {
    let (host, fake) = setup();
    let s = scope(&host, "s");
    run::<()>(
        &host,
        s,
        "nf.bossbars.create({ text = 'Boss', progress = 0.5, color = 'red' })",
    )
    .unwrap();
    let bars = fake.bars.borrow();
    assert_eq!(bars[0].text.as_ref().map(|t| t.0.as_str()), Some("Boss"));
    assert_eq!(bars[0].progress, Some(0.5));
    assert_eq!(bars[0].color.as_deref(), Some("red"));
    let size: String = run(
        &host,
        s,
        "local v = nf.structures.size('tower') return tostring(v.y) .. ' ' .. typeof(v)",
    )
    .unwrap();
    assert_eq!(size, "9 vector");
    let none: Value = run(&host, s, "return nf.structures.size('nothing')").unwrap();
    assert!(none.is_nil());
}

#[test]
fn a_handle_is_one_value_per_thing() {
    let (host, _) = setup();
    let s = scope(&host, "s");
    let same: (bool, bool, String, String) = run(
        &host,
        s,
        "local a, b = nf.bossbars.create(), nf.bossbars.create() return a == b, rawequal(a, b), typeof(a), tostring(a)",
    )
    .unwrap();
    assert_eq!(same, (true, true, "handle".into(), "BossBar 7".into()));
}

#[test]
fn a_handle_cant_be_changed_or_called_with_a_dot() {
    let (host, _) = setup();
    let s = scope(&host, "s");
    let message = error_of(run(
        &host,
        s,
        "local bar = nf.bossbars.create() bar.exists = nil",
    ));
    assert!(
        message.contains("a BossBar handle is shared by every script and can't be changed"),
        "{message}"
    );
    let message = error_of(run(
        &host,
        s,
        "local bar = nf.bossbars.create() return bar.exists()",
    ));
    assert!(
        message.contains("call BossBar methods with ':'"),
        "{message}"
    );
    let hidden: bool = run(
        &host,
        s,
        "return getmetatable(nf.bossbars.create()) == false",
    )
    .unwrap();
    assert!(hidden);
}

#[test]
fn an_unported_class_says_so() {
    let (host, _) = setup();
    let s = scope(&host, "s");
    let message = error_of(run(&host, s, "return nf.bossbars.create():exists()"));
    assert!(
        message.contains("BossBar:exists isn't on the Rust runtime yet"),
        "{message}"
    );
    let message = error_of(run(&host, s, "return nf.server.tick()"));
    assert!(
        message.contains("isn't on the Rust runtime yet"),
        "{message}"
    );
}

#[test]
fn nf_is_the_apis_and_cant_be_changed() {
    let (host, _) = setup();
    let s = scope(&host, "s");
    let message = error_of(run(&host, s, "nf.looks = {}"));
    assert!(message.contains("nf is the API's"), "{message}");
    let missing: Value = run(&host, s, "return nf.nothing_here").unwrap();
    assert!(missing.is_nil());
}

#[test]
fn an_async_value_reaches_its_callback_at_the_tick_boundary() {
    let (host, fake) = setup();
    let s = scope(&host, "s");
    run::<()>(
        &host,
        s,
        "got = {}
nf.bots.join('Alex', vector.create(0, 64, 0), nil, function(bot, err) got.bot = tostring(bot) got.err = err end)
nf.bots.join('nobody', nil, nil, function(bot, err) got.taken = err end)",
    )
    .unwrap();
    assert_eq!(
        *fake.joins.borrow(),
        [("Alex".to_owned(), true), ("nobody".to_owned(), false)]
    );
    // Nothing is delivered inside the call.
    let before: Value = run(&host, s, "return got.bot").unwrap();
    assert!(before.is_nil());
    let lua = host.lua();
    pending::deliver(lua, |scope, f, value, err| {
        host.invoke::<()>(scope.unwrap(), 1_000_000, f, (value, err))
            .unwrap();
    })
    .unwrap();
    let after: (String, Value, String) =
        run(&host, s, "return got.bot, got.err, got.taken").unwrap();
    assert_eq!(after.0, "Bot Alex");
    assert!(after.1.is_nil());
    assert_eq!(after.2, "that name is taken");
}
