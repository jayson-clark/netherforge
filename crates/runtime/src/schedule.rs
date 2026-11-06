//! `nf.schedule` and `Schedule` (Kotlin's `Schedules`, `Recurrence` and `ScheduleImpls`): functions that run at
//! a time of day, a weekday or a cron expression (Unix, five fields), in the server owner's time zone.
//!
//! Each tick compares the wall clock with every schedule's next run and calls those that are due, as their
//! script's code. A schedule that is due runs once however late the tick is: the runs it slept through are
//! skipped, and its next is the first one after now.
//!
//! A schedule with an id remembers when it last ran, per package, in the store (`schedule_runs`). With
//! `catch_up`, one that finds its next run after that already past when its script starts runs once at once
//! (the server was off at its time). A schedule seen for the first time counts as run when it's made.
//!
//! **Daylight saving is java.time's**, as Kotlin's was: a wall-clock time the clocks skip is shifted by the
//! gap (`02:30` on the night clocks jump from 02:00 to 03:00 is 03:30), and one that happens twice is the first.
//!
//! Not yet: the zone from `config.yml` (`schedules.time-zone`; the session is given one with
//! `Session::set_time_zone`), and the test servers' scaled clock.

use std::collections::{BTreeMap, HashMap};
use std::rc::Rc;
use std::str::FromStr;

use chrono::{
    DateTime, Datelike, Duration, FixedOffset, Local, LocalResult, NaiveDate, NaiveDateTime,
    NaiveTime, Offset, TimeZone, Utc, Weekday,
};
use mlua::Function;
use netherforge_api::api::{NfScheduleApi, ScheduleApi};
use netherforge_api::{ApiError, ApiResult, Caller, handles, shapes};
use netherforge_luau::ScopeId;
use netherforge_platform::Platform;

use crate::session::Service;
use crate::shared::Shared;
use crate::{Room, Scripts};

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// The zone schedules' times are in: the server's own, a named one (`Europe/Paris`), or an offset (`+02:00`).
#[derive(Clone, Debug, Default)]
pub enum Zone {
    #[default]
    Local,
    Named(chrono_tz::Tz),
    Fixed(FixedOffset),
}

impl Zone {
    /// `name` as a zone: an IANA name, `UTC`, or an offset like `+02:00`; none for anything else.
    pub fn parse(name: &str) -> Option<Zone> {
        if let Ok(tz) = chrono_tz::Tz::from_str(name) {
            return Some(Zone::Named(tz));
        }
        let (sign, rest) = match name.as_bytes().first()? {
            b'+' => (1, &name[1..]),
            b'-' => (-1, &name[1..]),
            _ => return None,
        };
        let (h, m) = rest.split_once(':').unwrap_or((rest, "0"));
        let seconds = h.parse::<i32>().ok()? * 3600 + m.parse::<i32>().ok()? * 60;
        FixedOffset::east_opt(sign * seconds).map(Zone::Fixed)
    }

    /// The wall-clock time `at` is here.
    pub fn local(&self, at: DateTime<Utc>) -> NaiveDateTime {
        match self {
            Zone::Local => at.with_timezone(&Local).naive_local(),
            Zone::Named(tz) => at.with_timezone(tz).naive_local(),
            Zone::Fixed(offset) => at.with_timezone(offset).naive_local(),
        }
    }

    /// The instant wall-clock time `local` is here: the first of two, and past a gap shifted by the gap.
    pub fn instant(&self, local: NaiveDateTime) -> Option<DateTime<Utc>> {
        fn resolve<Tz: TimeZone>(tz: &Tz, local: NaiveDateTime) -> Option<DateTime<Utc>> {
            match tz.from_local_datetime(&local) {
                LocalResult::Single(at) | LocalResult::Ambiguous(at, _) => {
                    Some(at.with_timezone(&Utc))
                }
                // In a gap: with the offset before it, which lands as far past the gap as the time was into it.
                LocalResult::None => {
                    let before = tz
                        .from_local_datetime(&(local - Duration::hours(3)))
                        .earliest()?;
                    let offset = before.offset().fix();
                    Some((local - Duration::seconds(i64::from(offset.local_minus_utc()))).and_utc())
                }
            }
        }
        match self {
            Zone::Local => resolve(&Local, local),
            Zone::Named(tz) => resolve(tz, local),
            Zone::Fixed(offset) => resolve(offset, local),
        }
    }

    /// How far ahead of UTC the clocks are here at `at`, in seconds.
    pub fn offset_seconds(&self, at: DateTime<Utc>) -> i32 {
        match self {
            Zone::Local => at.with_timezone(&Local).offset().local_minus_utc(),
            Zone::Named(tz) => at.with_timezone(tz).offset().fix().local_minus_utc(),
            Zone::Fixed(offset) => offset.local_minus_utc(),
        }
    }

    /// The zone's id as java.time spells it: `Europe/London`, `Z`, `+02:00`.
    pub fn id(&self) -> String {
        match self {
            Zone::Local => iana_time_zone::get_timezone().unwrap_or_else(|_| "UTC".into()),
            Zone::Named(tz) => tz.name().to_owned(),
            Zone::Fixed(offset) if offset.local_minus_utc() == 0 => "Z".into(),
            Zone::Fixed(offset) => offset.to_string(),
        }
    }

    /// The zone's short name at `at` (`BST`), or its id when it has none.
    pub fn abbreviation(&self, at: DateTime<Utc>) -> String {
        let named = match self {
            Zone::Named(tz) => Some(*tz),
            Zone::Local => iana_time_zone::get_timezone()
                .ok()
                .and_then(|name| chrono_tz::Tz::from_str(&name).ok()),
            Zone::Fixed(_) => None,
        };
        named
            .and_then(|tz| {
                use chrono_tz::OffsetName;
                at.with_timezone(&tz)
                    .offset()
                    .abbreviation()
                    .map(str::to_owned)
            })
            .unwrap_or_else(|| self.id())
    }

    /// The first time `cron` matches strictly after `after`, here.
    fn next_cron(&self, cron: &croner::Cron, after: DateTime<Utc>) -> Option<DateTime<Utc>> {
        match self {
            Zone::Local => cron
                .find_next_occurrence(&after.with_timezone(&Local), false)
                .ok()
                .map(|t| t.with_timezone(&Utc)),
            Zone::Named(tz) => cron
                .find_next_occurrence(&after.with_timezone(tz), false)
                .ok()
                .map(|t| t.with_timezone(&Utc)),
            Zone::Fixed(offset) => cron
                .find_next_occurrence(&after.with_timezone(offset), false)
                .ok()
                .map(|t| t.with_timezone(&Utc)),
        }
    }
}

/// When a schedule runs, as a rule: the first time after any instant, in a zone.
#[derive(Clone, Debug)]
pub enum Recurrence {
    Daily(NaiveTime),
    Weekly(Weekday, NaiveTime),
    Cron(String, Box<croner::Cron>),
}

impl Recurrence {
    /// A time of day written `18:00` or `6:30`, or none when it isn't one.
    pub fn time(text: &str) -> Option<NaiveTime> {
        if !(4..=5).contains(&text.len()) {
            return None;
        }
        let (h, m) = text.split_once(':')?;
        if h.is_empty()
            || h.len() > 2
            || m.len() != 2
            || !h.chars().chain(m.chars()).all(|c| c.is_ascii_digit())
        {
            return None;
        }
        NaiveTime::from_hms_opt(h.parse().ok()?, m.parse().ok()?, 0)
    }

    /// A weekday written `sat` or `saturday`, in any case.
    pub fn day(text: &str) -> Option<Weekday> {
        let text = text.to_lowercase();
        [
            ("monday", Weekday::Mon),
            ("tuesday", Weekday::Tue),
            ("wednesday", Weekday::Wed),
            ("thursday", Weekday::Thu),
            ("friday", Weekday::Fri),
            ("saturday", Weekday::Sat),
            ("sunday", Weekday::Sun),
        ]
        .into_iter()
        .find(|(name, _)| text == *name || text == name[..3])
        .map(|(_, day)| day)
    }

    /// The cron expression `text` (five fields, Unix), or its problem in words.
    pub fn cron(text: &str) -> Result<Recurrence, String> {
        let parser = croner::parser::CronParser::builder()
            .seconds(croner::parser::Seconds::Disallowed)
            .build();
        let cron = parser.parse(text).map_err(|e| e.to_string())?;
        Ok(Recurrence::Cron(text.to_owned(), Box::new(cron)))
    }

    /// The first run strictly after `after`, or none when there never is one.
    pub fn next(&self, after: DateTime<Utc>, zone: &Zone) -> Option<DateTime<Utc>> {
        match self {
            Recurrence::Daily(time) => first_day(after, zone, *time, |_| true),
            Recurrence::Weekly(day, time) => first_day(after, zone, *time, |d| d.weekday() == *day),
            Recurrence::Cron(_, cron) => zone.next_cron(cron, after),
        }
    }

    /// The rule in words, as `/nf schedules` shows it.
    pub fn describe(&self) -> String {
        match self {
            Recurrence::Daily(time) => format!("daily {}", time.format("%H:%M")),
            Recurrence::Weekly(day, time) => {
                format!(
                    "weekly {} {}",
                    day.to_string().to_lowercase(),
                    time.format("%H:%M")
                )
            }
            Recurrence::Cron(text, _) => format!("cron {text}"),
        }
    }
}

/// The first of `time` on a day `accepts` whose moment is after `after`.
fn first_day(
    after: DateTime<Utc>,
    zone: &Zone,
    time: NaiveTime,
    accepts: impl Fn(NaiveDate) -> bool,
) -> Option<DateTime<Utc>> {
    // One day back, in case a gap pushed yesterday's time past midnight; a week and a day forward is enough.
    let mut date = zone.local(after).date() - Duration::days(1);
    for _ in 0..9 {
        if accepts(date)
            && let Some(at) = zone.instant(date.and_time(time))
            && at > after
        {
            return Some(at);
        }
        date += Duration::days(1);
    }
    None
}

/// One schedule: `next` is when it runs next.
pub struct Entry {
    pub scope: ScopeId,
    pub namespace: String,
    pub id: Option<String>,
    pub recurrence: Recurrence,
    callback: Function,
    pub next: Option<DateTime<Utc>>,
}

/// Every live schedule, and when each package's last ran.
#[derive(Default)]
pub struct Schedules {
    pub entries: BTreeMap<i64, Entry>,
    counter: i64,
    pub zone: Zone,
    /// When each package's schedules with an id last ran (Unix milliseconds), read the first time it makes one.
    last_runs: HashMap<String, BTreeMap<String, i64>>,
    /// The wall clock, in Unix milliseconds, when a test sets one.
    pub now: Option<i64>,
}

impl Schedules {
    pub fn now(&self) -> DateTime<Utc> {
        match self.now {
            Some(millis) => DateTime::from_timestamp_millis(millis).unwrap_or_default(),
            None => Utc::now(),
        }
    }
}

/// `nf.schedule` and `Schedule`, for one room.
#[derive(Clone)]
pub struct ScheduleContext {
    pub shared: Rc<Shared>,
    pub scripts: Scripts,
}

impl ScheduleContext {
    fn record(&self, namespace: &str, id: &str, at: DateTime<Utc>) {
        let millis = at.timestamp_millis();
        let mut schedules = self.shared.schedules.borrow_mut();
        let runs = schedules
            .last_runs
            .entry(namespace.to_owned())
            .or_insert_with(|| self.shared.store.schedule_runs(namespace));
        runs.insert(id.to_owned(), millis);
        self.shared.store.schedule_ran(namespace, id, millis);
    }

    fn start(
        &self,
        caller: &Caller,
        recurrence: Recurrence,
        callback: Function,
        options: Option<shapes::ScheduleOptions>,
    ) -> ApiResult<handles::Schedule> {
        let (id, catch_up) = options.map_or((None, false), |o| (o.id, o.catch_up == Some(true)));
        let namespace = self
            .scripts
            .namespace(caller.scope)
            .unwrap_or_else(|| self.shared.centities.borrow().home().to_owned());
        if let Some(id) = &id
            && !netherforge_format::names::is_id(id)
        {
            return Err(err(format!(
                "options.id: \"{id}\" can't name a schedule ({})",
                netherforge_format::names::ID_RULE
            )));
        }
        if catch_up && id.is_none() {
            return Err(err(
                "options.catch_up needs an options.id: that's what the schedule's last run is kept under",
            ));
        }
        if let Some(id) = &id
            && self
                .shared
                .schedules
                .borrow()
                .entries
                .values()
                .any(|e| e.id.as_ref() == Some(id) && e.namespace == namespace)
        {
            return Err(err(format!(
                "there's already a schedule with the id \"{id}\" in this package (cancel it first, or give this one another)"
            )));
        }
        let (now, zone) = {
            let schedules = self.shared.schedules.borrow();
            (schedules.now(), schedules.zone.clone())
        };
        let last = id.as_ref().and_then(|id| {
            let mut schedules = self.shared.schedules.borrow_mut();
            let runs = schedules
                .last_runs
                .entry(namespace.clone())
                .or_insert_with(|| self.shared.store.schedule_runs(&namespace));
            runs.get(id).copied()
        });
        if let Some(id) = &id
            && last.is_none()
        {
            self.record(&namespace, id, now);
        }
        let missed = catch_up
            && last.is_some_and(|last| {
                DateTime::from_timestamp_millis(last)
                    .and_then(|last| recurrence.next(last, &zone))
                    .is_some_and(|next| next <= now)
            });
        let next = if missed {
            Some(now)
        } else {
            recurrence.next(now, &zone)
        };
        let mut schedules = self.shared.schedules.borrow_mut();
        schedules.counter += 1;
        let number = schedules.counter;
        schedules.entries.insert(
            number,
            Entry {
                scope: caller.scope,
                namespace,
                id,
                recurrence,
                callback,
                next,
            },
        );
        Ok(handles::Schedule { number })
    }
}

fn bad_time(time: &str) -> ApiError {
    err(format!(
        "bad argument 'time' (\"{time}\" isn't a time of day: \"HH:mm\" on a 24-hour clock, like \"18:00\")"
    ))
}

impl NfScheduleApi for ScheduleContext {
    fn daily(
        &self,
        caller: &Caller,
        time: String,
        callback: Function,
        options: Option<shapes::ScheduleOptions>,
    ) -> ApiResult<handles::Schedule> {
        let at = Recurrence::time(&time).ok_or_else(|| bad_time(&time))?;
        self.start(caller, Recurrence::Daily(at), callback, options)
    }

    fn weekly(
        &self,
        caller: &Caller,
        day: String,
        time: String,
        callback: Function,
        options: Option<shapes::ScheduleOptions>,
    ) -> ApiResult<handles::Schedule> {
        let weekday = Recurrence::day(&day).ok_or_else(|| {
            err(format!(
                "bad argument 'day' (\"{day}\" isn't a weekday: \"mon\", \"tue\", \"wed\", \"thu\", \"fri\", \"sat\" or \"sun\")"
            ))
        })?;
        let at = Recurrence::time(&time).ok_or_else(|| bad_time(&time))?;
        self.start(caller, Recurrence::Weekly(weekday, at), callback, options)
    }

    fn cron(
        &self,
        caller: &Caller,
        expression: String,
        callback: Function,
        options: Option<shapes::ScheduleOptions>,
    ) -> ApiResult<handles::Schedule> {
        let cron = Recurrence::cron(&expression).map_err(|why| {
            err(format!(
                "bad argument 'expression' (\"{expression}\" isn't a cron expression: {why})"
            ))
        })?;
        self.start(caller, cron, callback, options)
    }
}

impl ScheduleApi for ScheduleContext {
    fn cancel(&self, this: &handles::Schedule) -> ApiResult<()> {
        self.shared
            .schedules
            .borrow_mut()
            .entries
            .remove(&this.number);
        Ok(())
    }

    fn is_active(&self, this: &handles::Schedule) -> ApiResult<bool> {
        Ok(self
            .shared
            .schedules
            .borrow()
            .entries
            .contains_key(&this.number))
    }

    fn next_run(&self, this: &handles::Schedule) -> ApiResult<Option<i64>> {
        Ok(self
            .shared
            .schedules
            .borrow()
            .entries
            .get(&this.number)
            .and_then(|e| e.next)
            .map(|t| t.timestamp_millis()))
    }
}

/// Schedules as a session's service: each tick, those that are due run.
pub struct SchedulesService {
    pub shared: Rc<Shared>,
}

impl Service for SchedulesService {
    fn name(&self) -> &'static str {
        "schedules"
    }

    fn tick(&self, room: &Room, _platform: &dyn Platform) {
        let scripts = room.scripts();
        let (now, zone, due) = {
            let mut schedules = self.shared.schedules.borrow_mut();
            // A schedule whose script stopped goes with it.
            schedules.entries.retain(|_, e| scripts.live(e.scope));
            let now = schedules.now();
            let due: Vec<i64> = schedules
                .entries
                .iter()
                .filter(|(_, e)| e.next.is_some_and(|n| n <= now))
                .map(|(n, _)| *n)
                .collect();
            (now, schedules.zone.clone(), due)
        };
        let context = ScheduleContext {
            shared: self.shared.clone(),
            scripts: scripts.clone(),
        };
        for number in due {
            // One an earlier one this tick cancelled isn't run.
            let run = {
                let mut schedules = self.shared.schedules.borrow_mut();
                let Some(entry) = schedules.entries.get_mut(&number) else {
                    continue;
                };
                entry.next = entry.recurrence.next(now, &zone);
                (
                    entry.scope,
                    entry.namespace.clone(),
                    entry.id.clone(),
                    entry.callback.clone(),
                )
            };
            let (scope, namespace, id, callback) = run;
            if let Some(id) = &id {
                context.record(&namespace, id, now);
            }
            if let Err(failure) = scripts.call_kept(scope, &callback, ()) {
                scripts.handler_failed(scope, "schedule", failure);
            }
        }
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        let mut schedules = self.shared.schedules.borrow_mut();
        schedules.entries.clear();
        schedules.last_runs.clear();
    }
}
