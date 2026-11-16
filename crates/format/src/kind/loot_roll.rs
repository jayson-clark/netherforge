//! Rolling loot tables (format's `LootRoller`): the one place their meaning is
//! written down, shared by the server (which rolls what scripts ask for) and
//! the editor. Everything random comes from the `Random` a roll is given
//! (Kotlin's, bit for bit), so a seed gives the same drops on every side.
//!
//! It follows vanilla's rules: each pool, if its conditions pass, makes
//! `rolls + floor(bonusRolls * luck)` picks; each pick weighs the entries
//! whose conditions pass by `floor(weight + quality * luck)` (an entry at 0 or
//! below can't be picked), and takes the only one without a draw. A table
//! entry rolls that table whole, with the same random; a game loot table is
//! handed back as a `LootDrop::Vanilla` for the server to roll.

use crate::item::ItemDef;
use crate::kind::loot::{LootCondition, LootEntry, LootPool, LootRange, LootTableFile};
use crate::kind::recipe::Ingredient;
use crate::random::Random;

/// How deep tables may include each other in one roll: a cycle the project couldn't see (it's checked) stops here.
pub const MOST_DEPTH: usize = 16;

/// The most picks one roll makes, across every table it includes.
pub const MOST_PICKS: usize = 10_000;

/// What a roll knows about why it's rolled, for its tables' conditions: the
/// server answers it from a script's roll context, the editor's preview with
/// nothing (`NoContext`).
pub trait LootContext {
    /// The roll's luck: more bonus rolls, and each entry's weight shifted by its quality.
    fn luck(&self) -> f64;

    /// Whether a player is behind the roll.
    fn has_player(&self) -> bool;

    /// Whether the roll's tool is `tool`; false when it has none.
    fn tool_is(&self, tool: &Ingredient) -> bool;

    /// The level of `enchantment` (namespaced) on the roll's tool, 0 when it has none.
    fn enchantment_level(&self, enchantment: &str) -> i32;
}

/// No luck, no player, no tool.
pub struct NoContext;

impl LootContext for NoContext {
    fn luck(&self) -> f64 {
        0.0
    }
    fn has_player(&self) -> bool {
        false
    }
    fn tool_is(&self, _: &Ingredient) -> bool {
        false
    }
    fn enchantment_level(&self, _: &str) -> i32 {
        0
    }
}

/// One thing a roll gives, in the order it was picked.
#[derive(Clone, Debug, PartialEq)]
pub enum LootDrop {
    /// `count` of `item` (`item` has no count of its own), however many stacks that makes.
    Stack { item: Box<ItemDef>, count: i32 },
    /// One of the game's loot tables, to roll on the server with `seed` (so it's as deterministic as the rest).
    Vanilla { table: String, seed: i64 },
}

/// What one roll of `file` gives, in order. `table` finds the loot table a
/// table entry names, as it's written in the table being rolled. A table it
/// can't find, tables nested past `MOST_DEPTH` or more than `MOST_PICKS`
/// picks are an error.
pub fn roll(
    file: &LootTableFile,
    table: &dyn Fn(&str) -> Option<LootTableFile>,
    context: &dyn LootContext,
    random: &mut Random,
) -> Result<Vec<LootDrop>, String> {
    let mut roll = Roll {
        find: table,
        context,
        random,
        drops: Vec::new(),
        picks: 0,
    };
    roll.table(file, 0)?;
    Ok(roll.drops)
}

struct Roll<'a> {
    find: &'a dyn Fn(&str) -> Option<LootTableFile>,
    context: &'a dyn LootContext,
    random: &'a mut Random,
    drops: Vec<LootDrop>,
    picks: usize,
}

const ONE: LootRange = LootRange { min: 1, max: 1 };

/// A whole number of Kotlin's `Int`, as a range's ends are.
fn int(n: i64) -> i32 {
    n.clamp(i64::from(i32::MIN), i64::from(i32::MAX)) as i32
}

impl Roll<'_> {
    fn table(&mut self, file: &LootTableFile, depth: usize) -> Result<(), String> {
        if depth > MOST_DEPTH {
            return Err(format!(
                "loot tables include each other more than {MOST_DEPTH} deep"
            ));
        }
        let mut pools: Vec<(&String, &LootPool)> =
            file.pools.iter().map(|(k, v)| (&k.0, v)).collect();
        pools.sort_by(|a, b| a.0.cmp(b.0));
        for (_, pool) in pools {
            self.pool(pool, depth)?;
        }
        Ok(())
    }

    fn pool(&mut self, pool: &LootPool, depth: usize) -> Result<(), String> {
        if !self.passes(pool.conditions.as_deref()) {
            return Ok(());
        }
        let bonus = (pool.bonus_rolls.unwrap_or(0.0) * self.context.luck()).floor() as i32;
        let rolls = self.range(pool.rolls.unwrap_or(ONE)).saturating_add(bonus);
        for _ in 0..rolls.max(0) {
            self.pick(&pool.entries, depth)?;
        }
        Ok(())
    }

    fn pick(&mut self, entries: &[LootEntry], depth: usize) -> Result<(), String> {
        self.picks += 1;
        if self.picks > MOST_PICKS {
            return Err(format!("a roll made more than {MOST_PICKS} picks"));
        }
        let luck = self.context.luck();
        let mut weighed: Vec<(&LootEntry, i64)> = Vec::new();
        for entry in entries {
            if !self.passes(entry.conditions().map(Vec::as_slice)) {
                continue;
            }
            let (weight, quality) = weighting(entry);
            let weight = (f64::from(weight.unwrap_or(1)) + f64::from(quality.unwrap_or(0)) * luck)
                .floor() as i64;
            if weight > 0 {
                weighed.push((entry, weight));
            }
        }
        let chosen = match weighed.len() {
            0 => return Ok(()),
            1 => weighed[0].0,
            _ => {
                let total = weighed.iter().map(|(_, w)| w).sum();
                let mut left = self.random.next_long_below(total);
                let mut chosen = weighed[0].0;
                for (entry, weight) in &weighed {
                    if left < *weight {
                        chosen = entry;
                        break;
                    }
                    left -= weight;
                }
                chosen
            }
        };
        self.give(chosen, depth)
    }

    fn give(&mut self, entry: &LootEntry, depth: usize) -> Result<(), String> {
        match entry {
            LootEntry::Item { item, count, .. } => {
                let count = self.range(count.unwrap_or(ONE));
                if count > 0 {
                    self.drops.push(LootDrop::Stack {
                        item: item.clone(),
                        count,
                    });
                }
            }
            LootEntry::Table { table, .. } => {
                let next = (self.find)(table)
                    .ok_or_else(|| format!("there's no loot table \"{table}\""))?;
                self.table(&next, depth + 1)?;
            }
            LootEntry::Vanilla { table, .. } => self.drops.push(LootDrop::Vanilla {
                table: table.clone(),
                seed: self.random.next_long(),
            }),
            LootEntry::Empty { .. } => {}
        }
        Ok(())
    }

    fn passes(&mut self, conditions: Option<&[LootCondition]>) -> bool {
        conditions.unwrap_or_default().iter().all(|condition| {
            let (holds, invert) = match condition {
                LootCondition::Chance { chance, invert } => {
                    (self.random.next_double() < *chance, invert)
                }
                LootCondition::Player { invert } => (self.context.has_player(), invert),
                LootCondition::Tool { tool, invert } => (self.context.tool_is(tool), invert),
                LootCondition::Enchantment {
                    enchantment,
                    level,
                    invert,
                } => (
                    self.context
                        .enchantment_level(&crate::game::normalize(enchantment))
                        >= level.unwrap_or(1),
                    invert,
                ),
            };
            holds != (*invert == Some(true))
        })
    }

    fn range(&mut self, range: LootRange) -> i32 {
        let (min, max) = (int(range.min), int(range.max));
        if min >= max {
            min
        } else {
            self.random.next_int_in(min, max.saturating_add(1))
        }
    }
}

fn weighting(entry: &LootEntry) -> (Option<i32>, Option<i32>) {
    match entry {
        LootEntry::Item {
            weight, quality, ..
        }
        | LootEntry::Table {
            weight, quality, ..
        }
        | LootEntry::Vanilla {
            weight, quality, ..
        }
        | LootEntry::Empty {
            weight, quality, ..
        } => (*weight, *quality),
    }
}
