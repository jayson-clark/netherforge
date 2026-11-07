//! The runtime's store when its file goes wrong (Kotlin's `DatabaseTest`'s): a file that isn't a database is moved
//! aside and a new store started, and a batch the database can't commit (another connection holding its lock) is
//! written with the next commit, said once and again when it writes again.

use netherforge_runtime::store::Store;

fn folder(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("nf-store-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

#[test]
fn a_file_that_isnt_a_database_is_moved_aside_and_a_new_one_started() {
    let dir = folder("unreadable");
    let file = dir.join("netherforge.db");
    std::fs::write(
        &file,
        "this was never a database, and it's long enough to have a header's worth of bytes in it",
    )
    .unwrap();
    let store = Store::open(&file).expect("a new store");
    let said = store.take_failures();
    assert_eq!(said.len(), 1, "{said:?}");
    assert!(said[0].contains("isn't a database"), "{said:?}");
    assert!(dir.join("netherforge.db.unreadable").exists());
    store.schedule_ran("shop", "daily", 7);
    store.flush();
    assert_eq!(store.schedule_runs("shop").get("daily"), Some(&7));
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn a_batch_the_database_cant_commit_is_written_with_the_next_newer_writes_winning() {
    let dir = folder("busy");
    let file = dir.join("netherforge.db");
    let store = Store::open(&file).unwrap();
    // A person's sqlite3 holding the write lock.
    let other = rusqlite::Connection::open(&file).unwrap();
    other.execute_batch("BEGIN IMMEDIATE").unwrap();
    store.schedule_ran("shop", "daily", 1);
    store.schedule_ran("shop", "weekly", 1);
    store.flush();
    let said = store.take_failures();
    assert_eq!(said.len(), 1, "{said:?}");
    assert!(said[0].contains("written with the next commit"), "{said:?}");
    // Failing again isn't said again.
    store.schedule_ran("shop", "daily", 2);
    store.flush();
    assert!(store.take_failures().is_empty());
    other.execute_batch("COMMIT").unwrap();
    drop(other);
    store.schedule_ran("shop", "hourly", 3);
    store.flush();
    assert_eq!(store.take_failures(), ["The store is writing again"]);
    let runs = store.schedule_runs("shop");
    assert_eq!(
        (runs.get("daily"), runs.get("weekly"), runs.get("hourly")),
        (Some(&2), Some(&1), Some(&3))
    );
    let _ = std::fs::remove_dir_all(&dir);
}
