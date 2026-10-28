//! The allowlist for the dev server's extra JVM arguments.
//!
//! The settings come from the webview, which renders project content, so a
//! JVM argument is a way to run code (`-javaagent:`, `-agentpath:`,
//! `-XX:OnOutOfMemoryError=<command>`), read or write files
//! (`-Xlog:...:file=`, `-XX:HeapDumpPath=`) or swap the platform
//! (`-Djava.class.path`, `-Dlog4j.configurationFile=<url>`). So the editor
//! accepts only what a server owner tunes, and refuses everything else with
//! [crate::error::ErrorCode::JvmArgument], which the UI shows by the setting:
//!
//! - **Memory and stack**: `-Xmx`, `-Xms`, `-Xmn`, `-Xss` with a size
//!   (`4G`, `512m`, `2048`).
//! - **`-XX:`**: a fixed list of garbage-collector, heap-sizing and
//!   diagnostic switches, as `+Name` / `-Name` ([BOOLEAN_FLAGS]) or
//!   `Name=number` ([NUMBER_FLAGS]; sizes also take a `k`/`m`/`g` unit,
//!   `...Percentage` ones a decimal). Anything that names a file or a
//!   command is not in it.
//! - **`-D` properties**: `-Dname=value` or `-Dname`, where the name is
//!   `[A-Za-z][A-Za-z0-9_.-]*`, isn't in a namespace that changes the JVM or
//!   its libraries ([BLOCKED_PROPERTY_PREFIXES]: `java.`, `jdk`, `sun.`,
//!   `netherforge.` (the editor sets those), `log4j`...) and the value has no
//!   control characters. Paper's and plugins' own properties pass.
//! - `-server`, `-Xshare:auto|off`, `-verbose:gc`, `-Xlog:gc` and `-Xlog:gc*`
//!   (to the console, never a file).
//!
//! The memory setting is separate (`server_memory_mb`); an `-Xmx` here comes
//! after it and wins, as it always has. `MemoryBackend` mirrors this list
//! (`memory.ts`), and the backend contract suite holds them together.

use crate::error::{Error, ErrorCode, Result};

const BOOLEAN_FLAGS: &[&str] = &[
    "UseG1GC",
    "UseZGC",
    "ZGenerational",
    "UseShenandoahGC",
    "UseParallelGC",
    "UseSerialGC",
    "AlwaysPreTouch",
    "DisableExplicitGC",
    "ParallelRefProcEnabled",
    "UseStringDeduplication",
    "PerfDisableSharedMem",
    "UseNUMA",
    "UseTransparentHugePages",
    "UseLargePages",
    "UnlockExperimentalVMOptions",
    "HeapDumpOnOutOfMemoryError",
    "ExitOnOutOfMemoryError",
    "CrashOnOutOfMemoryError",
    "OmitStackTraceInFastThrow",
    "UseCompressedOops",
    "UseCompressedClassPointers",
    "AlwaysActAsServerClassMachine",
    "UseFastUnorderedTimeStamps",
];

const NUMBER_FLAGS: &[&str] = &[
    "MaxGCPauseMillis",
    "G1NewSizePercent",
    "G1MaxNewSizePercent",
    "G1HeapRegionSize",
    "G1ReservePercent",
    "G1HeapWastePercent",
    "G1MixedGCCountTarget",
    "G1MixedGCLiveThresholdPercent",
    "G1RSetUpdatingPauseTimePercent",
    "InitiatingHeapOccupancyPercent",
    "SurvivorRatio",
    "MaxTenuringThreshold",
    "ParallelGCThreads",
    "ConcGCThreads",
    "MaxRAMPercentage",
    "MinRAMPercentage",
    "InitialRAMPercentage",
    "MaxMetaspaceSize",
    "ReservedCodeCacheSize",
    "SoftMaxHeapSize",
    "ZCollectionInterval",
    "MaxInlineLevel",
];

/// Property namespaces that change the JVM, its security or logging
/// libraries, or what the editor sets itself (lowercase prefixes).
const BLOCKED_PROPERTY_PREFIXES: &[&str] = &[
    "java.",
    "javax.",
    "jdk",
    "sun.",
    "com.sun.",
    "user.",
    "os.",
    "file.separator",
    "path.separator",
    "line.separator",
    "netherforge.",
    "log4j",
    "org.apache.logging.",
    "jna.",
    "polyglot.",
    "org.graalvm.",
];

const MAX_LENGTH: usize = 1024;

fn refuse(arg: &str, why: &str) -> Error {
    Error::new(
        ErrorCode::JvmArgument,
        format!("JVM argument {arg:?} isn't allowed: {why}"),
    )
}

/// `4G`, `512m`, `2048`: digits and at most one unit letter.
fn is_size(text: &str) -> bool {
    let digits = text
        .strip_suffix(|c: char| "kKmMgG".contains(c))
        .unwrap_or(text);
    !digits.is_empty() && digits.len() <= 12 && digits.bytes().all(|b| b.is_ascii_digit())
}

fn is_decimal(text: &str) -> bool {
    let (whole, fraction) = match text.split_once('.') {
        Some((whole, fraction)) => (whole, Some(fraction)),
        None => (text, None),
    };
    !whole.is_empty()
        && whole.len() <= 12
        && whole.bytes().all(|b| b.is_ascii_digit())
        && fraction
            .is_none_or(|f| !f.is_empty() && f.len() <= 6 && f.bytes().all(|b| b.is_ascii_digit()))
}

fn check_xx(arg: &str, flag: &str) -> Result<()> {
    if let Some(name) = flag.strip_prefix(['+', '-']) {
        if BOOLEAN_FLAGS.contains(&name) {
            return Ok(());
        }
        return Err(refuse(arg, "that -XX switch isn't on the editor's list"));
    }
    let Some((name, value)) = flag.split_once('=') else {
        return Err(refuse(arg, "that -XX option isn't on the editor's list"));
    };
    if !NUMBER_FLAGS.contains(&name) {
        return Err(refuse(arg, "that -XX option isn't on the editor's list"));
    }
    let ok = if name.ends_with("Percentage") {
        is_decimal(value)
    } else {
        is_size(value)
    };
    if ok {
        Ok(())
    } else {
        Err(refuse(
            arg,
            "the value must be a number (with k, m or g for a size)",
        ))
    }
}

fn check_property(arg: &str, property: &str) -> Result<()> {
    let (name, value) = property.split_once('=').unwrap_or((property, ""));
    let mut chars = name.chars();
    let name_ok = chars.next().is_some_and(|c| c.is_ascii_alphabetic())
        && chars.all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '-'));
    if !name_ok {
        return Err(refuse(
            arg,
            "a property name is letters, digits, `_`, `.` and `-`, starting with a letter",
        ));
    }
    let lower = name.to_ascii_lowercase();
    if BLOCKED_PROPERTY_PREFIXES
        .iter()
        .any(|p| lower.starts_with(p))
    {
        return Err(refuse(
            arg,
            "that property belongs to the JVM, its libraries or the editor",
        ));
    }
    if value.chars().any(char::is_control) {
        return Err(refuse(arg, "the value has a control character"));
    }
    Ok(())
}

/// Checks one argument against the allowlist.
pub fn check(arg: &str) -> Result<()> {
    if arg.len() > MAX_LENGTH {
        let shown: String = arg.chars().take(40).collect();
        return Err(refuse(&format!("{shown}..."), "it's too long"));
    }
    if arg.is_empty() || arg.trim() != arg {
        return Err(refuse(arg, "it's empty or has spaces around it"));
    }
    if let Some(size) = ["-Xmx", "-Xms", "-Xmn", "-Xss"]
        .iter()
        .find_map(|p| arg.strip_prefix(p))
    {
        return if is_size(size) {
            Ok(())
        } else {
            Err(refuse(arg, "give a size such as 4G, 512m or 2048"))
        };
    }
    if let Some(flag) = arg.strip_prefix("-XX:") {
        return check_xx(arg, flag);
    }
    if let Some(property) = arg.strip_prefix("-D") {
        return check_property(arg, property);
    }
    if matches!(
        arg,
        "-server" | "-Xshare:auto" | "-Xshare:off" | "-verbose:gc" | "-Xlog:gc" | "-Xlog:gc*"
    ) {
        return Ok(());
    }
    let why = if arg.starts_with("-javaagent")
        || arg.starts_with("-agent")
        || arg.starts_with("-Xrun")
        || arg.starts_with("-Xbootclasspath")
        || arg.starts_with("-cp")
        || arg.starts_with("-classpath")
        || arg.starts_with("--")
        || arg.starts_with('@')
    {
        "agents, class paths, module options and argument files can run or load code"
    } else {
        "it isn't an allowed memory, -XX or -D argument"
    };
    Err(refuse(arg, why))
}

/// Checks every argument; the first refused one is the error.
pub fn check_all(args: &[String]) -> Result<()> {
    args.iter().try_for_each(|arg| check(arg))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_what_a_server_owner_tunes() {
        for arg in [
            "-Xmx4G",
            "-Xms512m",
            "-Xss4m",
            "-Xmn256M",
            "-Xmx2048",
            "-XX:+UseG1GC",
            "-XX:-UseZGC",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:MaxGCPauseMillis=200",
            "-XX:G1HeapRegionSize=8M",
            "-XX:MaxRAMPercentage=75.0",
            "-Dpaper.playerconnection.keepalive=60",
            "-DPaper.IgnoreJavaVersion=true",
            "-Dfile.encoding=UTF-8",
            "-Dmy-plugin.flag",
            "-Xlog:gc*",
            "-verbose:gc",
            "-server",
        ] {
            assert!(check(arg).is_ok(), "{arg}: {:?}", check(arg));
        }
    }

    #[test]
    fn refuses_what_runs_code_or_touches_files() {
        for arg in [
            "-javaagent:/tmp/evil.jar",
            "-agentpath:/tmp/x.so",
            "-agentlib:jdwp=transport=dt_socket,server=y,address=5005",
            "-Xrunjdwp:transport=dt_socket",
            "-Xbootclasspath/a:/tmp/x.jar",
            "-cp",
            "-classpath",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--patch-module",
            "@/tmp/args",
            "-jar",
            "-XX:OnOutOfMemoryError=rm -rf ~",
            "-XX:OnError=sh",
            "-XX:HeapDumpPath=/etc/cron.d",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+UseG1GC=1",
            "-XX:MaxGCPauseMillis=abc",
            "-XX:MaxGCPauseMillis=",
            "-XX:+Nope",
            "-XX:Flags=.hotspotrc",
            "-Xlog:gc:file=/tmp/x",
            "-Xlog:all=debug",
            "-Xmx",
            "-Xmx4GB",
            "-Xmx-1",
            "-Djava.class.path=/tmp/x",
            "-Djava.library.path=/tmp",
            "-DJAVA.io.tmpdir=/tmp",
            "-Djdk.module.path=x",
            "-Dlog4j.configurationFile=http://evil/x.xml",
            "-Dlog4j2.formatMsgNoLookups=false",
            "-Dnetherforge.bridge.port=1",
            "-Dnetherforge.project=/x",
            "-Duser.home=/tmp",
            "-Dsun.boot.library.path=x",
            "-D",
            "-D=x",
            "-D1abc=x",
            "-Da b=c",
            "-Dname=line\nbreak",
            "-Dname=\u{0}",
            " -Xmx1G",
            "",
            "-Xmx1G ",
            "-version",
            "-XshowSettings",
        ] {
            let err = check(arg).expect_err(arg);
            assert_eq!(err.code(), ErrorCode::JvmArgument, "{arg}");
        }
        assert!(check(&format!("-Dx={}", "a".repeat(2000))).is_err());
    }
}
