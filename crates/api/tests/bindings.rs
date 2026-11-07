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
    /// Whether a task is running (the waiting form's), and the waits it was put on.
    in_task: std::cell::Cell<bool>,
    waits: RefCell<Vec<(u64, u64)>>,
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
    fn waiting_task(&self, called: &str) -> ApiResult<u64> {
        if self.in_task.get() {
            return Ok(7);
        }
        Err(ApiError(format!("{called} only works inside a task")))
    }
    fn wait_on(&self, task: u64, wait: u64) {
        self.waits.borrow_mut().push((task, wait));
    }
    /// A server on 26.3: anything newer is gated.
    fn older_than(&self, since: &str) -> Option<String> {
        (since > "26.3").then(|| "26.3".to_owned())
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
    // Caught, it's a string at the script's line, as every binding's mistake is.
    let caught: String = run(
        &host,
        s,
        "local bar = nf.bossbars.create()\nlocal ok, err = pcall(function() bar.exists = nil end)\nreturn type(err) .. ' ' .. err",
    )
    .unwrap();
    assert!(
        caught.starts_with("string ")
            && caught.contains(":2: a BossBar handle is shared by every script"),
        "{caught}"
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
    for ready in pending::deliver(lua).unwrap() {
        let f = ready.callback.expect("a callback form");
        host.invoke::<()>(
            ready.scope.unwrap(),
            1_000_000,
            &f,
            (ready.value, ready.err),
        )
        .unwrap();
    }
    let after: (String, Value, String) =
        run(&host, s, "return got.bot, got.err, got.taken").unwrap();
    assert_eq!(after.0, "Bot Alex");
    assert!(after.1.is_nil());
    assert_eq!(after.2, "that name is taken");
}

#[test]
fn an_async_functions_waiting_form_waits_in_a_task_and_is_refused_elsewhere() {
    let (host, fake) = setup();
    let s = scope(&host, "s");
    // Outside a task, without a callback: refused before any work starts, as a string at the line.
    let message: String = run(
        &host,
        s,
        "local ok, why = pcall(nf.bots.join, 'Alex') return type(why) .. ' ' .. why",
    )
    .unwrap();
    assert_eq!(
        message,
        "string modules/t/init.lua:1: nf.bots.join only works inside a task"
    );
    assert!(fake.joins.borrow().is_empty());
    // A callback that isn't a function is a mistake, before the work too.
    let message = error_of(run(&host, s, "nf.bots.join('Alex', nil, nil, 5)"));
    assert!(
        message.contains("bad argument 'callback' (function or nil expected, got number)"),
        "{message}"
    );
    assert!(fake.joins.borrow().is_empty());

    // In a task: the work starts, the task waits on it, and is resumed with `value, err`.
    fake.in_task.set(true);
    run::<()>(
        &host,
        s,
        "co = coroutine.create(function() return nf.bots.join('Alex') end)
first = { coroutine.resume(co) }",
    )
    .unwrap();
    assert_eq!(*fake.joins.borrow(), [("Alex".to_owned(), false)]);
    let status: String = run(&host, s, "return coroutine.status(co)").unwrap();
    assert_eq!(status, "suspended", "it yields until the value comes");
    let ready = pending::deliver(host.lua()).unwrap();
    assert_eq!(ready.len(), 1);
    assert!(
        ready[0].callback.is_none(),
        "no callback: a task waits on it"
    );
    assert_eq!(*fake.waits.borrow(), [(7, ready[0].id)]);
    // What the runtime does with it: resumes the task with `value, err`.
    let co: netherforge_luau::mlua::Thread = host.env(s).unwrap().get("co").unwrap();
    let (bot, err): (Value, Value) = co
        .resume((ready[0].value.clone(), ready[0].err.clone()))
        .unwrap();
    assert_eq!(bot.to_string().unwrap(), "Bot Alex");
    assert!(err.is_nil());
}

#[test]
fn a_version_gate_names_what_needs_which_version_on_an_older_server() {
    // Nothing in the spec is gated today: the generated glue calls this for what is (bindings.test.ts).
    let (host, _) = setup();
    let lua = host.lua();
    assert_eq!(
        netherforge_api::bind::gate(lua, "Thing:poke", "27.1"),
        Err(ApiError(
            "Thing:poke needs Minecraft 27.1 (this server runs 26.3)".into()
        ))
    );
    assert_eq!(
        netherforge_api::bind::gate(lua, "Thing:poke", "26.1"),
        Ok(())
    );
}
