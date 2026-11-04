use std::time::Duration;

/// What the host holds scripts to, beyond each call's budget. The defaults
/// and why they're those numbers are in the rust-runtime skill ("The host").
#[derive(Clone, Debug, PartialEq)]
pub struct Limits {
    /// How long one call into a script may run, nested calls in included,
    /// before it's stopped.
    pub deadline: Duration,
    /// Interrupts between two looks at the clock. Luau interrupts on every
    /// call and every loop's backward jump, so they come far more often than
    /// Lua 5.4's count hook every 1000 instructions did.
    pub clock_every: u32,
    /// How much memory the state may use, every scope in it together. It's
    /// the allocator's limit, so it's exact: an allocation past it fails.
    pub memory_bytes: usize,
    /// The longest string a library function makes for a script
    /// (`string.rep`, `string.format`, `table.concat`...).
    pub max_string_bytes: usize,
    /// The most work one library call may do, in steps of about a nanosecond
    /// (a pattern's worst case, a sort): the caps' model.
    pub max_work: f64,
    /// The most a call's budget may be, whatever its script asks for; `None`:
    /// any.
    pub max_budget: Option<u64>,
}

impl Default for Limits {
    fn default() -> Self {
        Limits {
            deadline: Duration::from_millis(1000),
            clock_every: 256,
            memory_bytes: 512 * 1024 * 1024,
            max_string_bytes: 16 * 1024 * 1024,
            max_work: 1e8,
            max_budget: None,
        }
    }
}
