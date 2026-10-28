//! What the backend's own tests share: the fake server they run as a child
//! process, and waiting on conditions instead of sleeping.

use std::path::PathBuf;
use std::time::{Duration, Instant};

/// How long a test waits for something another process or thread does: far
/// past what it takes, so a slow CI machine (macOS and Windows runners are)
/// never fails a test that would pass. A wait ends as soon as its condition
/// holds, so a generous deadline costs nothing when all is well.
pub const PATIENCE: Duration = Duration::from_secs(30);

/// How often a wait checks its condition.
const POLL: Duration = Duration::from_millis(10);

/// The `fake_server` example (`examples/fake_server.rs`): a stand-in for Java
/// running Paper or the test runner, and for lua-language-server, on every OS.
/// Cargo puts examples beside the test executables' folder
/// (`target/<profile>/examples/`), and `cargo test` builds them before it
/// runs any test.
pub fn fake_server() -> PathBuf {
    let exe = std::env::current_exe().expect("the test knows where it runs from");
    let target = exe
        .parent()
        .and_then(|deps| deps.parent())
        .expect("tests run from target/<profile>/deps");
    let path = target
        .join("examples")
        .join(format!("fake_server{}", std::env::consts::EXE_SUFFIX));
    assert!(
        path.is_file(),
        "{} isn't built: run the tests with `cargo test` (which builds the examples), or `cargo build --example fake_server` first",
        path.display()
    );
    path
}

/// Waits until [what] holds, or panics after [PATIENCE] saying [waiting_for].
pub async fn until(waiting_for: &str, what: impl Fn() -> bool) {
    let deadline = Instant::now() + PATIENCE;
    while !what() {
        assert!(
            Instant::now() < deadline,
            "timed out waiting for {waiting_for}"
        );
        tokio::time::sleep(POLL).await;
    }
}
