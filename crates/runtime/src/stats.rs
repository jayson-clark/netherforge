//! Stats, modifiers and buffs (`buffs/<id>.json`) on players and centities (Kotlin's `StatSheets`): each
//! holder's modifiers by source (what scripts set) and its buffs with the ticks they have left, counted down
//! each tick, and a stat the sum of every buff's and every source's number for it, worked out once per
//! change. Runtime state: none of it is saved. Raising `buff_add` and `buff_end` is the caller's: what changes
//! a sheet says which buffs ended.

use std::collections::{BTreeMap, HashMap};

use indexmap::IndexMap;
use netherforge_format::kind::buff::{BuffFile, BuffStacking};
use netherforge_platform::Uuid;

/// Whose stats.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Holder {
    Player(Uuid),
    Centity(Uuid),
}

/// A buff someone has, with the ticks it has left.
#[derive(Clone, Debug, PartialEq)]
pub struct Active {
    pub id: String,
    pub ticks: i64,
}

/// A buff that ended, and why (`expired`, `removed`, `cured`).
#[derive(Clone, Debug, PartialEq)]
pub struct Ended {
    pub holder: Holder,
    pub buff: String,
    pub reason: &'static str,
}

#[derive(Default)]
struct Sheet {
    modifiers: IndexMap<String, BTreeMap<String, f64>>,
    buffs: IndexMap<String, Active>,
    sums: Option<BTreeMap<String, f64>>,
}

#[derive(Default)]
pub struct StatSheets {
    files: BTreeMap<String, BuffFile>,
    sheets: HashMap<Holder, Sheet>,
}

/// Lowercase letters, digits and `_`.
pub fn is_stat_name(name: &str) -> bool {
    !name.is_empty()
        && name
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_')
}

impl StatSheets {
    /// The project's buffs as they read now. A changed buff changes what it adds for everyone who has it; a
    /// deleted one goes from them (said in what this returns).
    pub fn define(&mut self, files: BTreeMap<String, BuffFile>) -> Vec<Ended> {
        self.files = files;
        let mut ended = Vec::new();
        for (holder, sheet) in &mut self.sheets {
            sheet.sums = None;
            let gone: Vec<String> = sheet
                .buffs
                .keys()
                .filter(|b| !self.files.contains_key(*b))
                .cloned()
                .collect();
            for buff in gone {
                sheet.buffs.shift_remove(&buff);
                ended.push(Ended {
                    holder: *holder,
                    buff,
                    reason: "removed",
                });
            }
        }
        ended
    }

    pub fn has(&self, buff: &str) -> bool {
        self.files.contains_key(buff)
    }

    pub fn file(&self, buff: &str) -> Option<&BuffFile> {
        self.files.get(buff)
    }

    pub fn buff_ids(&self) -> Vec<String> {
        self.files.keys().cloned().collect()
    }

    pub fn stat(&mut self, holder: Holder, name: &str) -> f64 {
        self.sums(holder).get(name).copied().unwrap_or(0.0)
    }

    pub fn stats(&mut self, holder: Holder) -> BTreeMap<String, f64> {
        self.sums(holder)
            .into_iter()
            .filter(|(_, v)| *v != 0.0)
            .collect()
    }

    fn sums(&mut self, holder: Holder) -> BTreeMap<String, f64> {
        let Some(sheet) = self.sheets.get_mut(&holder) else {
            return BTreeMap::new();
        };
        if let Some(sums) = &sheet.sums {
            return sums.clone();
        }
        let mut sums = BTreeMap::new();
        for active in sheet.buffs.values() {
            let stats = self.files.get(&active.id).and_then(|f| f.stats.as_ref());
            for (stat, value) in stats.into_iter().flat_map(|m| m.iter()) {
                *sums.entry(stat.0.clone()).or_insert(0.0) += *value;
            }
        }
        for modifiers in sheet.modifiers.values() {
            for (stat, value) in modifiers {
                *sums.entry(stat.clone()).or_insert(0.0) += *value;
            }
        }
        sheet.sums = Some(sums.clone());
        sums
    }

    pub fn modifiers(&self, holder: Holder, source: &str) -> Option<BTreeMap<String, f64>> {
        self.sheets.get(&holder)?.modifiers.get(source).cloned()
    }

    pub fn set_modifiers(
        &mut self,
        holder: Holder,
        source: &str,
        modifiers: Option<BTreeMap<String, f64>>,
    ) -> Result<(), String> {
        for (stat, value) in modifiers.iter().flatten() {
            if !is_stat_name(stat) {
                return Err(format!(
                    "bad argument 'modifiers' (\"{stat}\" isn't a stat name: lowercase letters, digits and _)"
                ));
            }
            if !value.is_finite() {
                return Err(format!(
                    "bad argument 'modifiers.{stat}' (a finite number expected)"
                ));
            }
        }
        match modifiers.filter(|m| !m.is_empty()) {
            None => {
                if let Some(sheet) = self.sheets.get_mut(&holder) {
                    sheet.modifiers.shift_remove(source);
                    sheet.sums = None;
                }
            }
            Some(modifiers) => {
                let sheet = self.sheets.entry(holder).or_default();
                sheet.modifiers.insert(source.to_owned(), modifiers);
                sheet.sums = None;
            }
        }
        Ok(())
    }

    /// Whether `holder` may take `buff` now: none of what blocks it is on them.
    pub fn blocked(&self, holder: Holder, buff: &str) -> bool {
        let Some(sheet) = self.sheets.get(&holder) else {
            return false;
        };
        self.files
            .get(buff)
            .and_then(|f| f.blocked_by.as_ref())
            .is_some_and(|blockers| blockers.iter().any(|b| sheet.buffs.contains_key(b)))
    }

    /// Gives `holder` `buff` for `ticks` (its `buff_add` handlers have had their say): what it cured.
    pub fn add(&mut self, holder: Holder, buff: &str, ticks: i64) -> Vec<Ended> {
        let Some(file) = self.files.get(buff).cloned() else {
            return Vec::new();
        };
        let given = ticks.max(1);
        let mut ended = Vec::new();
        let sheet = self.sheets.entry(holder).or_default();
        for cured in file.cures.iter().flatten() {
            if cured != buff && sheet.buffs.shift_remove(cured).is_some() {
                ended.push(Ended {
                    holder,
                    buff: cured.clone(),
                    reason: "cured",
                });
            }
        }
        let next = match sheet.buffs.get(buff) {
            None => given,
            Some(active) => match file.stacking.unwrap_or(BuffStacking::Longest) {
                BuffStacking::Add => active.ticks + given,
                BuffStacking::Replace => given,
                BuffStacking::Longest => active.ticks.max(given),
            },
        };
        let next = file.max_ticks.map_or(next, |m| next.min(i64::from(m)));
        match sheet.buffs.get_mut(buff) {
            Some(active) => active.ticks = next,
            None => {
                sheet.buffs.insert(
                    buff.to_owned(),
                    Active {
                        id: buff.to_owned(),
                        ticks: next,
                    },
                );
            }
        }
        sheet.sums = None;
        ended
    }

    /// Takes `buff` from `holder`: whether they had it.
    pub fn remove(&mut self, holder: Holder, buff: &str) -> bool {
        let Some(sheet) = self.sheets.get_mut(&holder) else {
            return false;
        };
        let had = sheet.buffs.shift_remove(buff).is_some();
        if had {
            sheet.sums = None;
        }
        had
    }

    pub fn ticks(&self, holder: Holder, buff: &str) -> Option<i64> {
        Some(self.sheets.get(&holder)?.buffs.get(buff)?.ticks)
    }

    /// Every buff `holder` has, the first given first.
    pub fn buffs(&self, holder: Holder) -> Vec<Active> {
        self.sheets
            .get(&holder)
            .map(|s| s.buffs.values().cloned().collect())
            .unwrap_or_default()
    }

    /// One tick: each present holder's buffs count down; a holder that's gone is forgotten. What expired.
    pub fn tick(&mut self, present: &dyn Fn(Holder) -> bool) -> Vec<Ended> {
        let mut ended = Vec::new();
        self.sheets.retain(|holder, _| present(*holder));
        for (holder, sheet) in &mut self.sheets {
            let mut expired = Vec::new();
            for active in sheet.buffs.values_mut() {
                active.ticks -= 1;
                if active.ticks <= 0 {
                    expired.push(active.id.clone());
                }
            }
            for buff in expired {
                sheet.buffs.shift_remove(&buff);
                sheet.sums = None;
                ended.push(Ended {
                    holder: *holder,
                    buff,
                    reason: "expired",
                });
            }
        }
        ended
    }

    pub fn clear(&mut self) {
        self.sheets.clear();
    }
}
