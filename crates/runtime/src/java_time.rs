//! `nf.time.format` and `nf.time.parse`'s patterns: java.time's `DateTimeFormatter.ofPattern` letters, in
//! English, resolved strictly (Kotlin's `NfTimeImpl`), so a pattern a script wrote for the Kotlin runtime writes
//! and reads the same text here.
//!
//! What's kept of Java's rules:
//! - `y` reads as `u` (the year, not the year of an era), so strict reading needn't be told the era.
//! - A letter's count picks its form as Java's does: `d` and `dd`, `M` to `MMMMM`, `X` to `XXXXX`... and a count
//!   Java refuses ("Too many pattern letters") or a letter that isn't Java's ("Unknown pattern letter") is an error.
//! - Text in single quotes is kept (`''` is a quote); any other character that isn't a letter is itself.
//! - Reading is strict: the whole text must match, a day that doesn't exist (February 30) or an hour past 23 isn't
//!   a time, a weekday must be the date's, and a variable-width number leaves the digits that fixed-width numbers
//!   right after it need (`yyyyMMdd`).
//!
//! What isn't: Java's week-based fields (`Y`, `w`, `W`, `e`, `c`, `F`), day periods (`B`), padding (`p`),
//! optional sections (`[`, `]`) and generic zone names (`v`, `zzzz`) are errors naming the letter.

use chrono::{DateTime, Datelike, NaiveDate, NaiveDateTime, NaiveTime, Timelike, Utc};

use crate::schedule::Zone;

const MONTHS: [&str; 12] = [
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
];
const DAYS: [&str; 7] = [
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
    "Sunday",
];
const QUARTERS: [&str; 4] = ["1st quarter", "2nd quarter", "3rd quarter", "4th quarter"];

/// One piece of a pattern.
#[derive(Clone, Debug, PartialEq)]
enum Item {
    Literal(String),
    Field(char, usize),
}

/// A compiled pattern.
#[derive(Clone, Debug)]
pub struct Pattern(Vec<Item>);

/// The most letters of each kind Java takes.
fn most(letter: char) -> Option<usize> {
    Some(match letter {
        'd' | 'h' | 'H' | 'k' | 'K' | 'm' | 's' => 2,
        'D' => 3,
        'M' | 'L' | 'Q' | 'q' | 'E' | 'G' | 'X' | 'x' | 'Z' => 5,
        'a' => 1,
        'V' => 2,
        'z' => 4,
        'O' => 4,
        'S' => 9,
        'u' | 'y' | 'n' | 'N' | 'A' => 19,
        _ => return None,
    })
}

const UNSUPPORTED: &str = "YwWecFBpgv[]";

impl Pattern {
    /// `pattern` read as `DateTimeFormatter.ofPattern` reads it, or Java's reason it doesn't (`Unknown pattern
    /// letter: b`).
    pub fn compile(pattern: &str) -> Result<Pattern, String> {
        let chars: Vec<char> = pattern.chars().collect();
        let mut items = Vec::new();
        let mut literal = String::new();
        let mut i = 0;
        while i < chars.len() {
            let c = chars[i];
            if c == '\'' {
                // A quoted run, `''` a quote inside it or alone.
                let mut j = i + 1;
                let mut text = String::new();
                loop {
                    if j >= chars.len() {
                        return Err(format!(
                            "Pattern ends with an incomplete string literal: {pattern}"
                        ));
                    }
                    if chars[j] == '\'' {
                        if chars.get(j + 1) == Some(&'\'') {
                            text.push('\'');
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    text.push(chars[j]);
                    j += 1;
                }
                literal.push_str(if j == i + 1 { "'" } else { &text });
                i = j + 1;
                continue;
            }
            if matches!(c, '{' | '}' | '#') {
                return Err(format!("Pattern includes reserved character: '{c}'"));
            }
            if UNSUPPORTED.contains(c) {
                return Err(format!("the pattern letter '{c}' isn't supported"));
            }
            if c.is_ascii_alphabetic() {
                let Some(max) = most(c) else {
                    return Err(format!("Unknown pattern letter: {c}"));
                };
                let mut count = 1;
                while chars.get(i + count) == Some(&c) {
                    count += 1;
                }
                if count > max || (c == 'V' && count != 2) || (c == 'O' && count != 1 && count != 4)
                {
                    return Err(format!("Too many pattern letters: {c}"));
                }
                if c == 'z' && count == 4 {
                    return Err("the pattern letter 'z' isn't supported four times (zzzz)".into());
                }
                if !literal.is_empty() {
                    items.push(Item::Literal(std::mem::take(&mut literal)));
                }
                // The year, not the year of an era.
                items.push(Item::Field(if c == 'y' { 'u' } else { c }, count));
                i += count;
                continue;
            }
            literal.push(c);
            i += 1;
        }
        if !literal.is_empty() {
            items.push(Item::Literal(literal));
        }
        Ok(Pattern(items))
    }

    /// Whether what this writes reads back with a date: a year, a month and a day (or a day of the year).
    pub fn has_date(&self) -> bool {
        let has = |letters: &str| {
            self.0
                .iter()
                .any(|item| matches!(item, Item::Field(c, _) if letters.contains(*c)))
        };
        has("u") && (has("ML") && has("d") || has("D"))
    }

    /// The moment `at` in `zone`, written.
    pub fn format(&self, at: DateTime<Utc>, zone: &Zone) -> String {
        let local = zone.local(at);
        let offset = zone.offset_seconds(at);
        let mut out = String::new();
        for item in &self.0 {
            match item {
                Item::Literal(text) => out.push_str(text),
                Item::Field(letter, count) => {
                    write_field(&mut out, *letter, *count, &local, offset, zone, at)
                }
            }
        }
        out
    }

    /// `text` read whole, as milliseconds since the epoch: `default` is the zone unless the text says its own.
    /// None when it doesn't match or isn't a moment that exists.
    pub fn parse(&self, text: &str, default: &Zone) -> Option<i64> {
        let mut fields = Fields::default();
        let mut at = 0;
        for (index, item) in self.0.iter().enumerate() {
            match item {
                Item::Literal(literal) => {
                    if !text[at..].starts_with(literal.as_str()) {
                        return None;
                    }
                    at += literal.len();
                }
                Item::Field(letter, count) => {
                    let reserved = reserved_after(&self.0[index + 1..]);
                    at = read_field(text, at, *letter, *count, reserved, &mut fields)?;
                }
            }
        }
        if at != text.len() {
            return None;
        }
        fields.resolve(default)
    }
}

/// The digits fixed-width numbers straight after a variable-width one need (Java's adjacent value parsing).
fn reserved_after(rest: &[Item]) -> usize {
    let mut total = 0;
    for item in rest {
        match item {
            Item::Field(letter, count) if fixed_width(*letter, *count).is_some() => {
                total += fixed_width(*letter, *count).unwrap_or(0);
            }
            _ => break,
        }
    }
    total
}

/// How many digits a number field always has, when it's fixed.
fn fixed_width(letter: char, count: usize) -> Option<usize> {
    match letter {
        'd' | 'h' | 'H' | 'k' | 'K' | 'm' | 's' | 'M' | 'L' | 'Q' | 'q' if count == 2 => Some(2),
        'u' if count == 2 => Some(2),
        'D' if count == 3 => Some(3),
        'S' => Some(count),
        _ => None,
    }
}

fn pad(out: &mut String, value: i64, width: usize) {
    if value < 0 {
        out.push('-');
    }
    let digits = value.unsigned_abs().to_string();
    for _ in digits.len()..width {
        out.push('0');
    }
    out.push_str(&digits);
}

fn text_form(full: &str, count: usize) -> &str {
    match count {
        4 => full,
        5 => &full[..1],
        _ => &full[..3],
    }
}

/// An offset as `+HH:MM` and friends: `colon` between the parts, `seconds` when they're there (or always).
fn write_offset(out: &mut String, offset: i32, minutes: Minutes, colon: bool, seconds: Minutes) {
    let sign = if offset < 0 { '-' } else { '+' };
    let total = offset.unsigned_abs();
    let (h, m, s) = (total / 3600, total / 60 % 60, total % 60);
    out.push(sign);
    out.push_str(&format!("{h:02}"));
    let sep = if colon { ":" } else { "" };
    if minutes == Minutes::Always || (minutes == Minutes::WhenThere && (m != 0 || s != 0)) {
        out.push_str(&format!("{sep}{m:02}"));
        if seconds == Minutes::Always || (seconds == Minutes::WhenThere && s != 0) {
            out.push_str(&format!("{sep}{s:02}"));
        }
    }
}

#[derive(Clone, Copy, PartialEq)]
enum Minutes {
    Never,
    WhenThere,
    Always,
}

/// The `X`/`x` forms by count: whether minutes and seconds are written always or only when there, and the colon.
fn iso_form(count: usize) -> (Minutes, bool, Minutes) {
    match count {
        1 => (Minutes::WhenThere, false, Minutes::Never),
        2 => (Minutes::Always, false, Minutes::Never),
        3 => (Minutes::Always, true, Minutes::Never),
        4 => (Minutes::Always, false, Minutes::WhenThere),
        _ => (Minutes::Always, true, Minutes::WhenThere),
    }
}

/// `GMT+1`, `GMT+01:00` (`long`), or `GMT` for none.
fn write_localized(out: &mut String, offset: i32, long: bool) {
    out.push_str("GMT");
    if offset == 0 {
        return;
    }
    let total = offset.unsigned_abs();
    let (h, m, s) = (total / 3600, total / 60 % 60, total % 60);
    out.push(if offset < 0 { '-' } else { '+' });
    if long {
        out.push_str(&format!("{h:02}:{m:02}"));
        if s != 0 {
            out.push_str(&format!(":{s:02}"));
        }
    } else {
        out.push_str(&h.to_string());
        if m != 0 || s != 0 {
            out.push_str(&format!(":{m:02}"));
            if s != 0 {
                out.push_str(&format!(":{s:02}"));
            }
        }
    }
}

fn write_field(
    out: &mut String,
    letter: char,
    count: usize,
    local: &NaiveDateTime,
    offset: i32,
    zone: &Zone,
    at: DateTime<Utc>,
) {
    let year = i64::from(local.year());
    let number = |out: &mut String, value: i64| pad(out, value, if count == 1 { 1 } else { count });
    match letter {
        'G' => out.push_str(match (count, year >= 1) {
            (4, true) => "Anno Domini",
            (4, false) => "Before Christ",
            (5, true) => "A",
            (5, false) => "B",
            (_, true) => "AD",
            (_, false) => "BC",
        }),
        'u' => match count {
            2 => pad(out, year.rem_euclid(100), 2),
            1 | 3 => pad(out, year, count),
            _ => {
                // Past its width, a year says its sign (EXCEEDS_PAD).
                if year >= 0 && year.to_string().len() > count {
                    out.push('+');
                }
                pad(out, year, count);
            }
        },
        'M' | 'L' => {
            let month = local.month() as usize;
            if count <= 2 {
                number(out, month as i64);
            } else {
                out.push_str(text_form(MONTHS[month - 1], count));
            }
        }
        'Q' | 'q' => {
            let quarter = (local.month() as usize - 1) / 3 + 1;
            match count {
                1 | 2 => number(out, quarter as i64),
                3 => out.push_str(&format!("Q{quarter}")),
                4 => out.push_str(QUARTERS[quarter - 1]),
                _ => out.push_str(&quarter.to_string()),
            }
        }
        'd' => number(out, i64::from(local.day())),
        'D' => number(out, i64::from(local.ordinal())),
        'E' => out.push_str(text_form(
            DAYS[local.weekday().num_days_from_monday() as usize],
            count,
        )),
        'a' => out.push_str(if local.hour() < 12 { "AM" } else { "PM" }),
        'H' => number(out, i64::from(local.hour())),
        'k' => number(
            out,
            i64::from(if local.hour() == 0 { 24 } else { local.hour() }),
        ),
        'K' => number(out, i64::from(local.hour() % 12)),
        'h' => number(
            out,
            i64::from(match local.hour() % 12 {
                0 => 12,
                h => h,
            }),
        ),
        'm' => number(out, i64::from(local.minute())),
        's' => number(out, i64::from(local.second())),
        'S' => {
            let nanos = format!("{:09}", local.nanosecond() % 1_000_000_000);
            out.push_str(&nanos[..count]);
        }
        'n' => number(out, i64::from(local.nanosecond() % 1_000_000_000)),
        'N' => number(
            out,
            i64::from(local.num_seconds_from_midnight()) * 1_000_000_000
                + i64::from(local.nanosecond() % 1_000_000_000),
        ),
        'A' => number(
            out,
            i64::from(local.num_seconds_from_midnight()) * 1000
                + i64::from(local.nanosecond() % 1_000_000_000 / 1_000_000),
        ),
        'X' | 'x' => {
            if letter == 'X' && offset == 0 {
                out.push('Z');
            } else {
                let (minutes, colon, seconds) = iso_form(count);
                write_offset(out, offset, minutes, colon, seconds);
            }
        }
        'Z' => match count {
            4 => write_localized(out, offset, true),
            5 if offset == 0 => out.push('Z'),
            5 => write_offset(out, offset, Minutes::Always, true, Minutes::WhenThere),
            _ => write_offset(out, offset, Minutes::Always, false, Minutes::Never),
        },
        'O' => write_localized(out, offset, count == 4),
        'V' => out.push_str(&zone.id()),
        'z' => out.push_str(&zone.abbreviation(at)),
        _ => {}
    }
}

/// What a text said, field by field, before it's resolved.
#[derive(Default)]
struct Fields {
    year: Option<i64>,
    era_ad: Option<bool>,
    month: Option<i64>,
    day: Option<i64>,
    day_of_year: Option<i64>,
    quarter: Option<i64>,
    weekday: Option<usize>,
    pm: Option<bool>,
    hour: Option<i64>,
    clock_hour: Option<i64>,
    hour_of_ampm: Option<i64>,
    clock_hour_of_ampm: Option<i64>,
    minute: Option<i64>,
    second: Option<i64>,
    nano: Option<i64>,
    milli_of_day: Option<i64>,
    nano_of_day: Option<i64>,
    offset: Option<i32>,
    zone: Option<Zone>,
}

/// Sets a field once: a second, different value for it is a text that disagrees with itself.
fn set<T: PartialEq>(field: &mut Option<T>, value: T) -> Option<()> {
    match field {
        Some(old) if *old != value => None,
        _ => {
            *field = Some(value);
            Some(())
        }
    }
}

impl Fields {
    fn resolve(self, default: &Zone) -> Option<i64> {
        let year = self.year?;
        if let Some(ad) = self.era_ad
            && ad != (year >= 1)
        {
            return None;
        }
        let year = i32::try_from(year).ok()?;
        let date = match (self.month, self.day, self.day_of_year) {
            (Some(month), Some(day), doy) => {
                let date = NaiveDate::from_ymd_opt(year, month as u32, day as u32)?;
                if doy.is_some_and(|d| d != i64::from(date.ordinal())) {
                    return None;
                }
                date
            }
            (month, None, Some(doy)) => {
                let date = NaiveDate::from_yo_opt(year, doy as u32)?;
                if month.is_some_and(|m| m != i64::from(date.month())) {
                    return None;
                }
                date
            }
            _ => return None,
        };
        if let Some(quarter) = self.quarter
            && quarter != i64::from((date.month() - 1) / 3 + 1)
        {
            return None;
        }
        if let Some(weekday) = self.weekday
            && weekday != date.weekday().num_days_from_monday() as usize
        {
            return None;
        }
        // The hour of the day, from whichever form said it; forms that disagree aren't a time.
        let mut hour = self.hour;
        if let Some(clock) = self.clock_hour {
            set(&mut hour, if clock == 24 { 0 } else { clock })?;
        }
        let mut of_ampm = self.hour_of_ampm;
        if let Some(clock) = self.clock_hour_of_ampm {
            set(&mut of_ampm, if clock == 12 { 0 } else { clock })?;
        }
        match (of_ampm, self.pm) {
            (Some(h), Some(pm)) => set(&mut hour, h + if pm { 12 } else { 0 })?,
            (None, Some(pm)) if hour.is_some_and(|h| (h >= 12) != pm) => return None,
            // An hour of AM or PM without saying which isn't resolved: java.time leaves it, and the time is midnight.
            _ => {}
        }
        let mut nanos_of_day = None;
        if let Some(millis) = self.milli_of_day {
            nanos_of_day = Some(millis * 1_000_000);
        }
        if let Some(nanos) = self.nano_of_day {
            set(&mut nanos_of_day, nanos)?;
        }
        let time = match hour {
            Some(hour) => {
                let time = NaiveTime::from_hms_nano_opt(
                    hour as u32,
                    self.minute.unwrap_or(0) as u32,
                    self.second.unwrap_or(0) as u32,
                    self.nano.unwrap_or(0) as u32,
                )?;
                if let Some(nanos) = nanos_of_day {
                    let own = i64::from(time.num_seconds_from_midnight()) * 1_000_000_000
                        + i64::from(time.nanosecond());
                    if own != nanos {
                        return None;
                    }
                }
                time
            }
            None => match nanos_of_day {
                Some(nanos) => NaiveTime::from_num_seconds_from_midnight_opt(
                    (nanos / 1_000_000_000) as u32,
                    (nanos % 1_000_000_000) as u32,
                )?,
                None => NaiveTime::MIN,
            },
        };
        let local = date.and_time(time);
        // A zone the text names, then an offset it says, then the default.
        let zone = match (self.zone, self.offset) {
            (Some(zone), _) => zone,
            (None, Some(offset)) => Zone::Fixed(chrono::FixedOffset::east_opt(offset)?),
            (None, None) => default.clone(),
        };
        Some(zone.instant(local)?.timestamp_millis())
    }
}

/// Reads digits at `at`: at least `min`, at most `max`, with a sign when `signed`.
fn digits(text: &str, at: usize, min: usize, max: usize, signed: bool) -> Option<(i64, usize)> {
    let bytes = text.as_bytes();
    let mut i = at;
    let mut negative = false;
    if signed && matches!(bytes.get(i), Some(b'-' | b'+')) {
        negative = bytes[i] == b'-';
        i += 1;
    }
    let start = i;
    while i < bytes.len() && i - start < max && bytes[i].is_ascii_digit() {
        i += 1;
    }
    if i - start < min.max(1) {
        return None;
    }
    let value: i64 = text[start..i].parse().ok()?;
    Some((if negative { -value } else { value }, i))
}

/// The longest of `choices` the text has at `at`, by index.
fn choice(text: &str, at: usize, choices: &[&str]) -> Option<(usize, usize)> {
    choices
        .iter()
        .enumerate()
        .filter(|(_, c)| !c.is_empty() && text[at..].starts_with(*c))
        .max_by_key(|(_, c)| c.len())
        .map(|(i, c)| (i, at + c.len()))
}

/// An offset as written at `at`: `+HH`, then `MM` and `ss` with or without colons as `form` allows.
fn read_offset(text: &str, at: usize, count: usize, z: bool) -> Option<(i32, usize)> {
    if z && text[at..].starts_with('Z') {
        return Some((0, at + 1));
    }
    let (minutes, colon, seconds) = iso_form(count);
    let bytes = text.as_bytes();
    let negative = match bytes.get(at)? {
        b'+' => false,
        b'-' => true,
        _ => return None,
    };
    let two = |i: usize| -> Option<i32> {
        let s = text.get(i..i + 2)?;
        s.bytes()
            .all(|b| b.is_ascii_digit())
            .then(|| s.parse().ok())?
    };
    let mut i = at + 1;
    let h = two(i)?;
    i += 2;
    let sep = if colon { ":" } else { "" };
    let part = |i: &mut usize, wanted: Minutes| -> Option<i32> {
        if wanted == Minutes::Never {
            return Some(0);
        }
        if text[*i..].starts_with(sep)
            && let Some(v) = two(*i + sep.len())
        {
            *i += sep.len() + 2;
            return Some(v);
        }
        (wanted == Minutes::WhenThere).then_some(0)
    };
    let m = part(&mut i, minutes)?;
    let s = if m == 0 && minutes == Minutes::WhenThere {
        0
    } else {
        part(&mut i, seconds)?
    };
    if h > 18 || m > 59 || s > 59 {
        return None;
    }
    let total = h * 3600 + m * 60 + s;
    Some((if negative { -total } else { total }, i))
}

/// `GMT`, `GMT+1`, `GMT+01:00`.
fn read_localized(text: &str, at: usize) -> Option<(i32, usize)> {
    if !text[at..].starts_with("GMT") {
        return None;
    }
    let i = at + 3;
    let negative = match text.as_bytes().get(i) {
        Some(b'+') => false,
        Some(b'-') => true,
        _ => return Some((0, i)),
    };
    let (h, mut i) = digits(text, i + 1, 1, 2, false)?;
    let mut total = h * 3600;
    for unit in [60, 1] {
        if text[i..].starts_with(':')
            && let Some((v, next)) = digits(text, i + 1, 2, 2, false)
        {
            total += v * unit;
            i = next;
        } else {
            break;
        }
    }
    let total = i32::try_from(total).ok()?;
    Some((if negative { -total } else { total }, i))
}

fn read_field(
    text: &str,
    at: usize,
    letter: char,
    count: usize,
    reserved: usize,
    fields: &mut Fields,
) -> Option<usize> {
    // A number: fixed at its width, or as wide as it goes, leaving what the fixed numbers after it need.
    let number = |min: usize, max: usize, signed: bool| -> Option<(i64, usize)> {
        let available = text.len().saturating_sub(at).saturating_sub(reserved);
        let max = if fixed_width(letter, count).is_some() {
            max
        } else {
            max.min(available.max(min))
        };
        digits(text, at, min, max, signed)
    };
    let small = || {
        if count == 2 {
            number(2, 2, false)
        } else {
            number(1, 19, false)
        }
    };
    match letter {
        'G' => {
            let (i, next) = match count {
                4 => choice(text, at, &["Anno Domini", "Before Christ"])?,
                5 => choice(text, at, &["A", "B"])?,
                _ => choice(text, at, &["AD", "BC"])?,
            };
            set(&mut fields.era_ad, i == 0)?;
            Some(next)
        }
        'u' => {
            let (value, next) = match count {
                2 => {
                    let (v, next) = number(2, 2, false)?;
                    (2000 + v, next)
                }
                1 => number(1, 19, true)?,
                _ => number(count, 19, true)?,
            };
            set(&mut fields.year, value)?;
            Some(next)
        }
        'M' | 'L' => {
            let (value, next) = if count <= 2 {
                small()?
            } else {
                let names: Vec<&str> = MONTHS.iter().map(|m| text_form(m, count)).collect();
                let (i, next) = choice(text, at, &names)?;
                (i as i64 + 1, next)
            };
            if !(1..=12).contains(&value) {
                return None;
            }
            set(&mut fields.month, value)?;
            Some(next)
        }
        'Q' | 'q' => {
            let (value, next) = match count {
                1 | 2 => small()?,
                3 => {
                    let (i, next) = choice(text, at, &["Q1", "Q2", "Q3", "Q4"])?;
                    (i as i64 + 1, next)
                }
                4 => {
                    let (i, next) = choice(text, at, &QUARTERS)?;
                    (i as i64 + 1, next)
                }
                _ => number(1, 1, false)?,
            };
            if !(1..=4).contains(&value) {
                return None;
            }
            set(&mut fields.quarter, value)?;
            Some(next)
        }
        'd' => {
            let (value, next) = small()?;
            set(&mut fields.day, value)?;
            Some(next)
        }
        'D' => {
            let (value, next) = match count {
                1 => number(1, 3, false)?,
                2 => number(2, 3, false)?,
                _ => number(3, 3, false)?,
            };
            set(&mut fields.day_of_year, value)?;
            Some(next)
        }
        'E' => {
            let names: Vec<&str> = DAYS.iter().map(|d| text_form(d, count)).collect();
            let (i, next) = choice(text, at, &names)?;
            set(&mut fields.weekday, i)?;
            Some(next)
        }
        'a' => {
            let (i, next) = choice(text, at, &["AM", "PM"])?;
            set(&mut fields.pm, i == 1)?;
            Some(next)
        }
        'H' | 'k' | 'K' | 'h' | 'm' | 's' => {
            let (value, next) = small()?;
            let (field, range) = match letter {
                'H' => (&mut fields.hour, 0..=23),
                'k' => (&mut fields.clock_hour, 1..=24),
                'K' => (&mut fields.hour_of_ampm, 0..=11),
                'h' => (&mut fields.clock_hour_of_ampm, 1..=12),
                'm' => (&mut fields.minute, 0..=59),
                _ => (&mut fields.second, 0..=59),
            };
            if !range.contains(&value) {
                return None;
            }
            set(field, value)?;
            Some(next)
        }
        'S' => {
            let (value, next) = digits(text, at, count, count, false)?;
            set(&mut fields.nano, value * 10_i64.pow(9 - count as u32))?;
            Some(next)
        }
        'n' => {
            let (value, next) = number(count, 19, false)?;
            if value >= 1_000_000_000 {
                return None;
            }
            set(&mut fields.nano, value)?;
            Some(next)
        }
        'N' => {
            let (value, next) = number(count, 19, false)?;
            set(&mut fields.nano_of_day, value)?;
            Some(next)
        }
        'A' => {
            let (value, next) = number(count, 19, false)?;
            if value >= 86_400_000 {
                return None;
            }
            set(&mut fields.milli_of_day, value)?;
            Some(next)
        }
        'X' | 'x' => {
            let (offset, next) = read_offset(text, at, count, letter == 'X')?;
            set(&mut fields.offset, offset)?;
            Some(next)
        }
        'Z' => {
            let (offset, next) = match count {
                4 => read_localized(text, at)?,
                5 => read_offset(text, at, 5, true)?,
                _ => read_offset(text, at, 2, false)?,
            };
            set(&mut fields.offset, offset)?;
            Some(next)
        }
        'O' => {
            let (offset, next) = read_localized(text, at)?;
            set(&mut fields.offset, offset)?;
            Some(next)
        }
        'V' => {
            let end = text[at..]
                .find(|c: char| !(c.is_ascii_alphanumeric() || "/_+-:~".contains(c)))
                .map_or(text.len(), |i| at + i);
            // The longest run that's a zone, as java.time's tree reads it.
            (at + 1..=end).rev().find_map(|stop| {
                let zone = Zone::parse(&text[at..stop])?;
                fields.zone = Some(zone);
                Some(stop)
            })
        }
        // A zone's abbreviation can't be read back: `BST` is three zones.
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn utc() -> Zone {
        Zone::parse("UTC").unwrap()
    }

    fn format(millis: i64, pattern: &str, zone: &str) -> String {
        Pattern::compile(pattern).unwrap().format(
            DateTime::from_timestamp_millis(millis).unwrap(),
            &Zone::parse(zone).unwrap(),
        )
    }

    fn parse(text: &str, pattern: &str) -> Option<i64> {
        Pattern::compile(pattern).unwrap().parse(text, &utc())
    }

    const MOMENT: i64 = 1_791_050_400_000; // 2026-10-03 18:00 UTC

    #[test]
    fn writes_as_java_does() {
        assert_eq!(
            format(0, "yyyy-MM-dd HH:mm:ss", "UTC"),
            "1970-01-01 00:00:00"
        );
        assert_eq!(
            format(MOMENT, "EEEE, d MMMM yyyy 'at' h a", "UTC"),
            "Saturday, 3 October 2026 at 6 PM"
        );
        assert_eq!(format(MOMENT, "HH:mm XXX", "Europe/London"), "19:00 +01:00");
        assert_eq!(format(MOMENT, "HH:mm", "-05:00"), "13:00");
        assert_eq!(
            format(MOMENT, "X x Z ZZZZ O OOOO", "+05:30"),
            "+0530 +0530 +0530 GMT+05:30 GMT+5:30 GMT+05:30"
        );
        assert_eq!(format(MOMENT, "X XXX x ZZZZZ ZZZZ", "UTC"), "Z Z +00 Z GMT");
        assert_eq!(
            format(MOMENT, "yy D DDD EEE MMM QQQ QQQQ G ''", "UTC"),
            "26 276 276 Sat Oct Q4 4th quarter AD '"
        );
        assert_eq!(
            format(MOMENT + 7, "HH:mm:ss.SSS k K", "UTC"),
            "18:00:00.007 18 6"
        );
        assert_eq!(format(MOMENT, "VV z", "Europe/London"), "Europe/London BST");
    }

    #[test]
    fn reads_strictly() {
        assert_eq!(parse("2026-10-03 18:00", "yyyy-MM-dd HH:mm"), Some(MOMENT));
        assert_eq!(
            parse("2026-10-03 20:00 +02:00", "yyyy-MM-dd HH:mm XXX"),
            Some(MOMENT)
        );
        assert_eq!(
            parse("2026-10-03", "yyyy-MM-dd"),
            Some(MOMENT - 18 * 3_600_000)
        );
        assert_eq!(parse("2026-10-03 6 PM", "yyyy-MM-dd h a"), Some(MOMENT));
        assert_eq!(parse("20261003", "yyyyMMdd"), Some(MOMENT - 18 * 3_600_000));
        assert_eq!(
            parse("03 Oct 2026 18:00", "dd MMM yyyy HH:mm"),
            Some(MOMENT)
        );
        assert_eq!(
            parse("Sat 2026-10-03", "EEE yyyy-MM-dd"),
            Some(MOMENT - 18 * 3_600_000)
        );
        assert_eq!(parse("Sun 2026-10-03", "EEE yyyy-MM-dd"), None);
        assert_eq!(
            parse("2026-10-03 19:00 Europe/London", "yyyy-MM-dd HH:mm VV"),
            Some(MOMENT)
        );
        assert_eq!(parse("tomorrow", "yyyy-MM-dd"), None);
        assert_eq!(parse("2026-02-30", "yyyy-MM-dd"), None);
        assert_eq!(parse("2026-10-03 25:00", "yyyy-MM-dd HH:mm"), None);
        assert_eq!(parse("2026-10-03 extra", "yyyy-MM-dd"), None);
    }

    #[test]
    fn refuses_what_java_refuses() {
        assert_eq!(
            Pattern::compile("yyyy-bb").unwrap_err(),
            "Unknown pattern letter: b"
        );
        assert_eq!(
            Pattern::compile("ddd").unwrap_err(),
            "Too many pattern letters: d"
        );
        assert!(Pattern::compile("'open").is_err());
        assert!(
            Pattern::compile("YYYY")
                .unwrap_err()
                .contains("isn't supported")
        );
        assert!(!Pattern::compile("HH:mm").unwrap().has_date());
        assert!(Pattern::compile("yyyy-DDD").unwrap().has_date());
    }
}
