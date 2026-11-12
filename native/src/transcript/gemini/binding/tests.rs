//! FIXTURE-ONLY: Herdr's pane and process reports, the sidecars and the
//! session files here are synthetic, in the shapes of Herdr v0.9.5 (2ecf19a,
//! `src/api/schema/panes.rs:448-518`) and gemini-cli v0.68.0 (5a5c7180), not
//! captured from a real pane. The launcher and hook tests run the shipped
//! `wrapper.js`, `hook.js` and `sidecar.js` under node with a stand-in bundle and a stand-in
//! `herdr`; no Gemini runs.

use super::*;
use std::fs;
use std::os::unix::fs::{PermissionsExt, symlink};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

const SID: &str = "6f1c2a9e-3b4d-4e5f-8a7b-9c0d1e2f3a4b";
const OTHER: &str = "0b7d3c1e-2f4a-4b5c-9d6e-7f8a9b0c1d2e";
/// The same first eight characters as [`SID`], so the same file name shape.
const TWIN: &str = "6f1c2a9e-0000-4000-8000-000000000000";
const GEN: &str = "00112233445566778899aabbccddeeff";
const NEW_GEN: &str = "ffeeddccbbaa99887766554433221100";
const PROJECT: &str = "/work/app";
/// sha256 of [`PROJECT`]: the header's projectHash (paths.ts:318-320).
const PROJECT_HASH: &str = "70467eff2e0a236497f0693901c782c674520c598f57e72b22e636e00311bd81";
const LAUNCHER: [&str; 2] = ["/usr/bin/node", "/opt/herdline/gemini"];

struct Host {
    home: tempfile::TempDir,
    env: Vec<(String, String)>,
}

impl Host {
    fn new() -> Self {
        let home = tempfile::tempdir().unwrap();
        let bin = home.path().join(".local/bin");
        fs::create_dir_all(&bin).unwrap();
        let run = home.path().join("run");
        fs::create_dir(&run).unwrap();
        fs::set_permissions(&run, fs::Permissions::from_mode(0o700)).unwrap();
        // `pane get` calls are counted: from the second one on (the check
        // after the read) the `.after.json` reports answer, and `flip.sh`
        // runs once.
        let herdr = bin.join("herdr");
        fs::write(
            &herdr,
            r#"#!/bin/sh
case "$*" in
*process-info*) f=proc ;;
*) f=pane; n=$(cat "$HOME/calls" 2>/dev/null || echo 0); n=$((n+1)); echo $n >"$HOME/calls" ;;
esac
n=$(cat "$HOME/calls")
if [ "$n" -ge 2 ] && [ -f "$HOME/$f.after.json" ]; then cat "$HOME/$f.after.json"; else cat "$HOME/$f.json"; fi
if [ "$f" = pane ] && [ "$n" = 2 ] && [ -f "$HOME/flip.sh" ]; then sh "$HOME/flip.sh"; fi
echo
"#,
        )
        .unwrap();
        fs::set_permissions(&herdr, fs::Permissions::from_mode(0o755)).unwrap();
        Self { home, env: Vec::new() }
    }

    fn path(&self, rel: &str) -> PathBuf {
        self.home.path().join(rel)
    }

    /// What Herdr reports for pane `pane`: its agent, its tokens, and one
    /// foreground process.
    fn report(&self, pane: &str, agent: Option<&str>, tokens: Value, pid: u32, argv: &[&str]) {
        self.write_report("", pane, agent, tokens, pid, argv);
    }

    /// The report the check after the read sees.
    fn report_after(&self, pane: &str, agent: Option<&str>, tokens: Value, pid: u32, argv: &[&str]) {
        self.write_report(".after", pane, agent, tokens, pid, argv);
    }

    fn write_report(&self, suffix: &str, pane: &str, agent: Option<&str>, tokens: Value, pid: u32, argv: &[&str]) {
        let report = json!({"id": "cli:pane:get", "result": {"pane": {
            "agent": agent,
            "agent_status": "idle",
            "pane_id": pane,
            "terminal_id": format!("term_{pane}"),
            "tokens": tokens,
        }, "type": "pane_info"}});
        fs::write(self.path(&format!("pane{suffix}.json")), report.to_string()).unwrap();
        let name = argv.first().map_or("node", |arg| arg.rsplit('/').next().unwrap());
        let process = json!({"result": {"process_info": {"pane_id": pane, "shell_pid": 7,
            "foreground_processes": [{"name": name, "pid": pid, "argv": argv}]}}});
        fs::write(self.path(&format!("proc{suffix}.json")), process.to_string()).unwrap();
        let _ = fs::remove_file(self.path("calls"));
    }

    /// A launched Gemini whose SessionStart reported: tokens, the launcher
    /// in the foreground, and its sidecar.
    fn bind(&self, pane: &str, sid: &str, pid: u32, generation: &str) {
        self.report(pane, Some("gemini"), tokens(sid, pid, generation), pid, &LAUNCHER);
        self.sidecar(pane, &json!({"pid": pid, "nonce": generation}).to_string());
    }

    fn sidecars(&self) -> PathBuf {
        let dir = self.path("run/herdline-gemini");
        if !dir.exists() {
            fs::create_dir(&dir).unwrap();
            fs::set_permissions(&dir, fs::Permissions::from_mode(0o700)).unwrap();
        }
        dir
    }

    fn sidecar(&self, pane: &str, body: &str) {
        let file = self.sidecars().join(format!("{pane}.json"));
        let _ = fs::remove_file(&file);
        fs::write(&file, body).unwrap();
        fs::set_permissions(&file, fs::Permissions::from_mode(0o600)).unwrap();
    }

    /// `~/.gemini/tmp/<slug>/chats/<name>` and the slug's `.project_root`.
    fn session(&self, slug: &str, name: &str, records: &[Value]) -> PathBuf {
        let dir = self.path(&format!(".gemini/tmp/{slug}"));
        fs::create_dir_all(dir.join("chats")).unwrap();
        if !dir.join(".project_root").exists() {
            fs::write(dir.join(".project_root"), PROJECT).unwrap();
        }
        let file = dir.join("chats").join(name);
        fs::write(&file, records.iter().map(|record| record.to_string() + "\n").collect::<String>()).unwrap();
        file
    }

    /// The pane's identity, as transcript.rs derives it from the report.
    fn identity(&self) -> Result<Option<Identity>> {
        let read = |name: &str| serde_json::from_str::<Value>(&fs::read_to_string(self.path(name)).unwrap()).unwrap();
        identity(&read("pane.json"), &read("proc.json"))
    }

    fn read(&self, pane: &str, expect: &Identity, stamp: u64) -> Result<Response> {
        let output = Command::new("/bin/sh")
            .arg("-c")
            .arg(script("lab", pane, expect, stamp))
            .env_clear()
            .envs(self.env.iter().cloned())
            .env("HOME", self.home.path())
            .env("XDG_RUNTIME_DIR", self.path("run"))
            .env("PATH", format!("{}:/usr/bin:/bin", self.path(".local/bin").display()))
            .output()
            .unwrap();
        if !output.status.success() {
            return Err(Error::NoTranscript);
        }
        answer(&output.stdout, expect, false)
    }
}

fn tokens(sid: &str, pid: u32, generation: &str) -> Value {
    json!({TOKEN_ID: sid, TOKEN_PID: pid.to_string(), TOKEN_GEN: generation})
}

fn name(sid: &str) -> String {
    format!("session-2026-11-12T10-00-{}.jsonl", &sid[..8])
}

fn header(sid: &str) -> Value {
    json!({"sessionId": sid, "projectHash": PROJECT_HASH, "startTime": "2026-11-12T10:00:00.000Z",
        "lastUpdated": "2026-11-12T10:00:00.000Z", "kind": "main"})
}

fn user(text: &str) -> Value {
    json!({"id": format!("m-{text}"), "timestamp": "2026-11-12T10:00:01.000Z", "type": "user", "content": [{"text": text}]})
}

fn chat(sid: &str, text: &str) -> Vec<Value> {
    vec![header(sid), user(text)]
}

fn shown(response: &Response) -> Vec<String> {
    response
        .entries
        .iter()
        .flat_map(|entry| &entry.blocks)
        .filter_map(|block| match block {
            crate::transcript::Block::Text { text, .. } => Some(text.clone()),
            _ => None,
        })
        .collect()
}

fn refused(result: Result<Response>) -> &'static str {
    match result {
        Err(Error::UnsupportedFormat(name)) => name,
        Err(error) => panic!("not a typed refusal: {error:?}"),
        Ok(response) => panic!("read: {:?}", shown(&response)),
    }
}

fn unbound(result: Result<Option<Identity>>) -> &'static str {
    match result {
        Err(Error::UnsupportedFormat(name)) => name,
        other => panic!("not a typed refusal: {other:?}"),
    }
}

#[test]
fn each_pane_reads_only_the_session_its_launcher_reported() {
    let host = Host::new();
    host.session("app", &name(SID), &chat(SID, "for one"));
    host.session("app", &name(OTHER), &chat(OTHER, "for two"));

    host.bind("w1:p1", SID, 42, GEN);
    let one = host.identity().unwrap().unwrap();
    assert_eq!((one.path.as_str(), one.pid, one.source.as_str()), (SID, Some(42), SOURCE));
    let page = host.read("w1:p1", &one, 0).unwrap();
    assert_eq!(shown(&page), ["for one"]);
    // Unchanged since that stamp: nothing is sent again.
    let again = host.read("w1:p1", &one, page.end).unwrap();
    assert!(again.entries.is_empty() && !again.reset);

    host.bind("w1:p2", OTHER, 43, NEW_GEN);
    let two = host.identity().unwrap().unwrap();
    assert_eq!(shown(&host.read("w1:p2", &two, 0).unwrap()), ["for two"]);
    // The other pane's identity is not this pane's.
    assert!(matches!(host.read("w1:p2", &one, 0), Err(Error::NoTranscript)));
}

#[test]
fn a_restart_in_the_same_pane_refuses_the_old_tokens_until_the_new_session_reports() {
    let host = Host::new();
    host.session("app", &name(SID), &chat(SID, "old"));
    host.session("app", &name(OTHER), &chat(OTHER, "new"));
    host.bind("w1:p1", SID, 42, GEN);
    let old = host.identity().unwrap().unwrap();

    // SIGKILL: Herdr keeps the tokens; the next launcher, pid 43, has written
    // its own sidecar but its SessionStart has not reported yet.
    host.report("w1:p1", Some("gemini"), tokens(SID, 42, GEN), 43, &LAUNCHER);
    host.sidecar("w1:p1", &json!({"pid": 43, "nonce": NEW_GEN}).to_string());
    assert_eq!(unbound(host.identity()), STALE);
    assert_eq!(refused(host.read("w1:p1", &old, 0)), STALE);

    host.bind("w1:p1", OTHER, 43, NEW_GEN);
    let new = host.identity().unwrap().unwrap();
    assert_eq!(shown(&host.read("w1:p1", &new, 0).unwrap()), ["new"]);
}

#[test]
fn a_stale_sidecar_and_a_reused_pid_are_refused_until_the_new_session_start() {
    let host = Host::new();
    host.session("app", &name(SID), &chat(SID, "old"));
    host.session("app", &name(OTHER), &chat(OTHER, "new"));
    host.bind("w1:p1", SID, 42, GEN);
    let old = host.identity().unwrap().unwrap();

    // SIGKILL left the sidecar; a new launcher got pid 42 again and replaced
    // it. Herdr's report looks exactly as before: only the nonce tells.
    host.sidecar("w1:p1", &json!({"pid": 42, "nonce": NEW_GEN}).to_string());
    assert_eq!(host.identity().unwrap().as_ref(), Some(&old));
    assert_eq!(refused(host.read("w1:p1", &old, 0)), STALE);

    host.report("w1:p1", Some("gemini"), tokens(OTHER, 42, NEW_GEN), 42, &LAUNCHER);
    let new = host.identity().unwrap().unwrap();
    assert_eq!(shown(&host.read("w1:p1", &new, 0).unwrap()), ["new"]);
}

#[test]
fn tokens_left_after_gemini_exits_are_refused_whatever_runs_next() {
    let host = Host::new();
    host.session("app", &name(SID), &chat(SID, "gone"));
    host.bind("w1:p1", SID, 42, GEN);
    let gone = host.identity().unwrap().unwrap();

    // A clean exit removed the sidecar; Herdr keeps the tokens and now sees
    // a shell in the pane.
    fs::remove_file(host.sidecars().join("w1:p1.json")).unwrap();
    host.report("w1:p1", None, tokens(SID, 42, GEN), 50, &["/bin/bash"]);
    assert!(host.identity().unwrap().is_none());
    assert!(matches!(host.read("w1:p1", &gone, 0), Err(Error::NoTranscript)));

    // Herdr still names Gemini a moment longer, or a SIGKILL left the
    // sidecar and pid 42 now runs something else: the pid is not the
    // launcher's either way.
    host.report("w1:p1", Some("gemini"), tokens(SID, 42, GEN), 50, &["/bin/bash"]);
    assert_eq!(unbound(host.identity()), STALE);
    host.sidecar("w1:p1", &json!({"pid": 42, "nonce": GEN}).to_string());
    host.report("w1:p1", Some("gemini"), tokens(SID, 42, GEN), 42, &["/usr/bin/vim", "notes"]);
    assert_eq!(unbound(host.identity()), STALE);
    assert_eq!(refused(host.read("w1:p1", &gone, 0)), STALE);
}

#[test]
fn a_missing_or_unsafe_sidecar_is_refused() {
    let host = Host::new();
    host.session("app", &name(SID), &chat(SID, "hi"));
    host.bind("w1:p1", SID, 42, GEN);
    let expect = host.identity().unwrap().unwrap();
    let file = host.sidecars().join("w1:p1.json");
    let body = json!({"pid": 42, "nonce": GEN}).to_string();

    fs::remove_file(&file).unwrap();
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), STALE);

    let real = host.path("elsewhere.json");
    fs::write(&real, &body).unwrap();
    fs::set_permissions(&real, fs::Permissions::from_mode(0o600)).unwrap();
    symlink(&real, &file).unwrap();
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), SIDECAR);

    host.sidecar("w1:p1", &body);
    fs::set_permissions(&file, fs::Permissions::from_mode(0o644)).unwrap();
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), SIDECAR);

    host.sidecar("w1:p1", &json!({"pid": 42, "nonce": GEN, "extra": 1}).to_string());
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), SIDECAR);

    host.sidecar("w1:p1", &body);
    fs::set_permissions(host.sidecars(), fs::Permissions::from_mode(0o755)).unwrap();
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), SIDECAR);
    fs::set_permissions(host.sidecars(), fs::Permissions::from_mode(0o700)).unwrap();

    // The runtime dir itself reached through a link.
    fs::rename(host.path("run"), host.path("run-real")).unwrap();
    symlink(host.path("run-real"), host.path("run")).unwrap();
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), SIDECAR);
    fs::remove_file(host.path("run")).unwrap();
    fs::rename(host.path("run-real"), host.path("run")).unwrap();

    assert_eq!(shown(&host.read("w1:p1", &expect, 0).unwrap()), ["hi"]);
}

#[test]
fn only_the_underscored_tokens_of_a_launched_gemini_bind() {
    let host = Host::new();
    host.session("app", &name(SID), &chat(SID, "hi"));
    host.bind("w1:p1", SID, 42, GEN);
    let expect = host.identity().unwrap().unwrap();

    // The first contract's dotted names: Herdr rejects such keys
    // (api_helpers.rs:256-262), so a report carrying them never binds.
    let dotted = json!({"hl.gemini.id": SID, "hl.gemini.pid": "42", "hl.gemini.gen": GEN});
    host.report("w1:p1", Some("gemini"), dotted, 42, &LAUNCHER);
    assert_eq!(unbound(host.identity()), UNBOUND);
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), UNBOUND);

    let upper = SID.to_uppercase();
    host.report("w1:p1", Some("gemini"), tokens(&upper, 42, GEN), 42, &LAUNCHER);
    assert_eq!(unbound(host.identity()), UNBOUND);

    // Gemini started from its own npm entry, not the owned launcher: with no
    // tokens it is unbound, with tokens left behind it is not their process.
    let unwrapped = ["/usr/bin/node", "/usr/lib/node_modules/@google/gemini-cli/dist/index.js"];
    host.report("w1:p1", Some("gemini"), json!({}), 42, &unwrapped);
    assert_eq!(unbound(host.identity()), UNBOUND);
    host.report("w1:p1", Some("gemini"), tokens(SID, 42, GEN), 42, &unwrapped);
    assert_eq!(unbound(host.identity()), STALE);
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), STALE);
}

#[test]
fn the_file_is_the_only_one_whose_header_is_the_session() {
    let host = Host::new();
    host.bind("w1:p1", SID, 42, GEN);
    let expect = host.identity().unwrap().unwrap();

    // The same short id in the name, another session in the header.
    host.session("app", &name(TWIN), &chat(TWIN, "not mine"));
    assert!(matches!(host.read("w1:p1", &expect, 0), Err(Error::NoTranscript)));

    host.session("app", &name(SID), &chat(SID, "mine"));
    assert_eq!(shown(&host.read("w1:p1", &expect, 0).unwrap()), ["mine"]);

    // A `$set` rewrote the id after the header: Gemini would load another
    // session from this file.
    let mut moved = chat(SID, "mine");
    moved.push(json!({"$set": {"sessionId": OTHER}}));
    host.session("app", &name(SID), &moved);
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), FOREIGN);

    // The project dir is owned by another root than the header's hash.
    host.session("app", &name(SID), &chat(SID, "mine"));
    fs::write(host.path(".gemini/tmp/app/.project_root"), "/work/other").unwrap();
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), FOREIGN);
    fs::write(host.path(".gemini/tmp/app/.project_root"), PROJECT).unwrap();

    // Two files claim the session: none is picked.
    host.session("app-1", "session-2026-11-12T11-30-6f1c2a9e.jsonl", &chat(SID, "copy"));
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), AMBIGUOUS);
}

#[test]
fn a_binding_that_changes_while_the_file_is_read_is_never_shown() {
    let host = Host::new();
    host.session("app", &name(SID), &chat(SID, "hi"));
    host.session("app", &name(OTHER), &chat(OTHER, "other"));
    host.bind("w1:p1", SID, 42, GEN);
    let expect = host.identity().unwrap().unwrap();

    host.report_after("w1:p1", Some("gemini"), tokens(OTHER, 42, GEN), 42, &LAUNCHER);
    assert!(matches!(host.read("w1:p1", &expect, 0), Err(Error::NoTranscript)));

    fs::remove_file(host.path("pane.after.json")).unwrap();
    fs::remove_file(host.path("proc.after.json")).unwrap();
    let file = host.sidecars().join("w1:p1.json");
    fs::write(
        host.path("flip.sh"),
        format!("printf '%s' '{}' >'{}'\n", json!({"pid": 42, "nonce": NEW_GEN}), file.display()),
    )
    .unwrap();
    let _ = fs::remove_file(host.path("calls"));
    assert_eq!(refused(host.read("w1:p1", &expect, 0)), STALE);
}

#[test]
fn the_store_stays_inside_home_without_links() {
    let mut host = Host::new();
    host.bind("w1:p1", SID, 42, GEN);
    let expect = host.identity().unwrap().unwrap();

    host.env = vec![("GEMINI_CLI_HOME".into(), "/tmp".into())];
    assert!(matches!(host.read("w1:p1", &expect, 0), Err(Error::SessionRootOutsideHome)));

    host.env.clear();
    let real = tempfile::tempdir().unwrap();
    fs::create_dir_all(real.path().join("tmp/app/chats")).unwrap();
    symlink(real.path(), host.path(".gemini")).unwrap();
    assert!(matches!(host.read("w1:p1", &expect, 0), Err(Error::TranscriptUnconfined)));
}

/// The shipped launcher in a dir of its own, named exactly `gemini`, and a
/// stand-in bundle that records what it ran with, then waits if asked.
fn launcher_dir(root: &Path) -> PathBuf {
    let dir = root.join("launcher");
    fs::create_dir(&dir).unwrap();
    fs::write(dir.join("gemini"), include_str!("../wrapper.js")).unwrap();
    fs::write(dir.join("hook.js"), include_str!("../hook.js")).unwrap();
    fs::write(dir.join("sidecar.js"), include_str!("../sidecar.js")).unwrap();
    fs::write(dir.join("package.json"), r#"{"type":"module"}"#).unwrap();
    fs::write(
        dir.join("bundle.mjs"),
        r#"import fs from 'node:fs';
const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => key.startsWith('GEMINI_CLI_')));
fs.writeFileSync(process.env.OUT + '.tmp', JSON.stringify({ pid: process.pid, argv: process.argv.slice(2), env }));
fs.renameSync(process.env.OUT + '.tmp', process.env.OUT);
if (process.argv.includes('--wait')) setInterval(() => {}, 1000);
"#,
    )
    .unwrap();
    dir
}

fn wait_for(path: &Path) -> Value {
    let start = Instant::now();
    while !path.exists() {
        assert!(start.elapsed() < Duration::from_secs(20), "{} never appeared", path.display());
        std::thread::sleep(Duration::from_millis(20));
    }
    serde_json::from_slice(&fs::read(path).unwrap()).unwrap()
}

fn launch(dir: &Path, run: &Path, out: &Path, args: &[&str]) -> Child {
    Command::new("node")
        .arg(dir.join("gemini"))
        .args(args)
        .env("HERDR_PANE_ID", "w1:p1")
        .env("HERDR_SOCKET_PATH", "/tmp/herdr.sock")
        .env("XDG_RUNTIME_DIR", run)
        .env("GEMINI_CLI_HERDLINE_BUNDLE", dir.join("bundle.mjs"))
        .env("GEMINI_CLI_HERDLINE_PANE", "w9:p9")
        .env("OUT", out)
        .stdin(Stdio::null())
        .spawn()
        .unwrap()
}

#[test]
fn the_launcher_runs_gemini_in_its_own_process_and_clears_only_its_own_sidecar() {
    let host = Host::new();
    let dir = launcher_dir(host.home.path());
    let run = host.path("run");
    let sidecar = run.join("herdline-gemini/w1:p1.json");
    let read = || serde_json::from_slice::<Value>(&fs::read(&sidecar).unwrap()).unwrap();

    let mut first = launch(&dir, &run, &host.path("a.json"), &["--wait", "-p", "x"]);
    let seen = wait_for(&host.path("a.json"));
    assert_eq!(seen["pid"], first.id());
    assert_eq!(seen["argv"], json!(["--wait", "-p", "x"]));
    assert_eq!(seen["env"]["GEMINI_CLI_NO_RELAUNCH"], "true");
    assert_eq!(seen["env"]["GEMINI_CLI_HERDLINE_PID"], first.id().to_string());
    assert_eq!(seen["env"]["GEMINI_CLI_HERDLINE_PANE"], "w1:p1");
    assert_eq!(seen["env"]["GEMINI_CLI_HERDLINE_SOCKET"], "/tmp/herdr.sock");
    let nonce = seen["env"]["GEMINI_CLI_HERDLINE_GEN"].as_str().unwrap().to_owned();
    assert!(super::nonce(&nonce));
    assert_eq!(read(), json!({"pid": first.id(), "nonce": nonce}));
    assert_eq!(fs::metadata(&sidecar).unwrap().permissions().mode() & 0o777, 0o600);
    assert_eq!(fs::metadata(sidecar.parent().unwrap()).unwrap().permissions().mode() & 0o777, 0o700);

    // A second launch in the pane takes the sidecar; the first one's exit
    // leaves it alone.
    let mut second = launch(&dir, &run, &host.path("b.json"), &["--wait"]);
    let other = wait_for(&host.path("b.json"));
    let other_nonce = other["env"]["GEMINI_CLI_HERDLINE_GEN"].as_str().unwrap().to_owned();
    assert_ne!(other_nonce, nonce);
    unsafe_kill(first.id(), "TERM");
    first.wait().unwrap();
    assert_eq!(read(), json!({"pid": second.id(), "nonce": other_nonce}));

    // SIGKILL cleans nothing: the reader's nonce check is what refuses it.
    second.kill().unwrap();
    second.wait().unwrap();
    assert_eq!(read(), json!({"pid": second.id(), "nonce": other_nonce}));

    // A run to its end removes what it wrote.
    let status = launch(&dir, &run, &host.path("c.json"), &[]).wait().unwrap();
    assert!(status.success());
    assert!(!sidecar.exists());
}

fn unsafe_kill(pid: u32, signal: &str) {
    assert!(Command::new("kill").arg(format!("-{signal}")).arg(pid.to_string()).status().unwrap().success());
}

/// A stand-in Herdr holding one pane's tokens, with Herdr's own rules for a
/// metadata report: a seq only above the source's last one is taken
/// (metadata_tokens.rs:17-23), and a report Herdr blocks after an agent's
/// exit is dropped without an error (api/panes.rs:1796-1802), here the next
/// `block` reports.
const FAKE_HERDR: &str = r#"#!/usr/bin/env node
const fs = require('fs');
const file = process.env.HOME + '/herdr.state';
const s = JSON.parse(fs.readFileSync(file, 'utf8'));
const a = process.argv.slice(2);
if (a[0] === 'pane' && a[1] === 'get') {
  process.stdout.write(JSON.stringify({ result: { pane: { pane_id: a[2], tokens: s.tokens } } }));
  process.exit(0);
}
s.reports.push([process.env.HERDR_SOCKET_PATH, ...a]);
const seq = Number(a[a.indexOf('--seq') + 1]);
if (s.block > 0) s.block--;
else if (s.seq === null || seq > s.seq) {
  s.seq = seq;
  a.forEach((arg, i) => {
    if (arg === '--token') { const [k, v] = a[i + 1].split('='); s.tokens[k] = v; }
    if (arg === '--clear-token') delete s.tokens[a[i + 1]];
  });
}
fs.writeFileSync(file, JSON.stringify(s));
"#;

fn at(ms: u32) -> String {
    format!("2026-11-12T10:00:{:02}.{:03}Z", ms / 1000, ms % 1000)
}

#[test]
fn a_late_session_end_never_clears_the_session_that_replaced_it() {
    let host = Host::new();
    let dir = launcher_dir(host.home.path());
    fs::write(host.path(".local/bin/herdr"), FAKE_HERDR).unwrap();
    let state = host.path("herdr.state");
    let set = |block: u32| {
        let mut value: Value = fs::read(&state).map_or(json!({"tokens": {}, "seq": null, "reports": []}), |raw| {
            serde_json::from_slice(&raw).unwrap()
        });
        value["block"] = json!(block);
        fs::write(&state, value.to_string()).unwrap();
    };
    let now = || serde_json::from_slice::<Value>(&fs::read(&state).unwrap()).unwrap();
    let hook = |event: &str, sid: &str, ms: u32| {
        let mut child = Command::new("node")
            .arg(dir.join("hook.js"))
            .env_clear()
            .env("HOME", host.home.path())
            .env("XDG_RUNTIME_DIR", host.path("run"))
            .env("PATH", format!("{}:/usr/bin:/bin", host.path(".local/bin").display()))
            .env("GEMINI_CLI_HERDLINE_PANE", "w1:p1")
            .env("GEMINI_CLI_HERDLINE_PID", "42")
            .env("GEMINI_CLI_HERDLINE_GEN", GEN)
            .env("GEMINI_CLI_HERDLINE_SOCKET", "/tmp/herdr.sock")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let input = json!({"hook_event_name": event, "session_id": sid, "timestamp": at(ms),
            "transcript_path": "/somewhere/else.jsonl", "cwd": PROJECT});
        std::io::Write::write_all(&mut child.stdin.take().unwrap(), input.to_string().as_bytes()).unwrap();
        assert_eq!(child.wait_with_output().unwrap().stdout, b"{}");
    };
    let bound = |sid: Option<&str>| match sid {
        Some(sid) => json!({TOKEN_ID: sid, TOKEN_PID: "42", TOKEN_GEN: GEN}),
        None => json!({}),
    };
    let reports = || now()["reports"].as_array().unwrap().len();
    set(0);
    host.sidecar("w1:p1", &json!({"pid": 42, "nonce": GEN}).to_string());

    hook("SessionStart", SID, 1000);
    assert_eq!(now()["tokens"], bound(Some(SID)));
    let first = now()["reports"][0].clone();
    assert_eq!(
        first.as_array().unwrap()[..8],
        ["/tmp/herdr.sock", "pane", "report-metadata", "w1:p1", "--agent", "gemini", "--source", SOURCE]
    );

    // `/new`: Gemini ends the old session, then starts the new one in the
    // same millisecond; the start is still the later report.
    hook("SessionEnd", SID, 2000);
    assert_eq!(now()["tokens"], bound(None));
    hook("SessionStart", OTHER, 2000);
    assert_eq!(now()["tokens"], bound(Some(OTHER)));

    // The old session's end, run late by the scheduler: whatever its time,
    // it is not the pane's session now.
    hook("SessionEnd", SID, 1500);
    hook("SessionEnd", SID, 9000);
    assert_eq!(now()["tokens"], bound(Some(OTHER)));
    // An end of the current session from before its start, run late: Herdr
    // refuses it by the event's own seq.
    hook("SessionEnd", OTHER, 1900);
    assert_eq!(now()["tokens"], bound(Some(OTHER)));

    // A newer launch took the pane's sidecar: this run's hooks report
    // nothing at all, start or end.
    let before = reports();
    host.sidecar("w1:p1", &json!({"pid": 43, "nonce": NEW_GEN}).to_string());
    hook("SessionEnd", OTHER, 10000);
    hook("SessionStart", SID, 10000);
    // A runtime dir reached through a link is not trusted either.
    host.sidecar("w1:p1", &json!({"pid": 42, "nonce": GEN}).to_string());
    fs::rename(host.path("run"), host.path("run-real")).unwrap();
    symlink(host.path("run-real"), host.path("run")).unwrap();
    hook("SessionEnd", OTHER, 10000);
    fs::remove_file(host.path("run")).unwrap();
    fs::rename(host.path("run-real"), host.path("run")).unwrap();
    // Not the strict lowercase uuid.
    hook("SessionStart", &SID.to_uppercase(), 10000);
    assert_eq!((reports(), now()["tokens"].clone()), (before, bound(Some(OTHER))));

    // The current session's own end clears it.
    hook("SessionEnd", OTHER, 11000);
    assert_eq!(now()["tokens"], bound(None));

    // Herdr drops the first starts it sees after an earlier Gemini's exit;
    // the hook reports again until the tokens show the session.
    set(2);
    let before = reports();
    hook("SessionStart", SID, 12000);
    assert_eq!(now()["tokens"], bound(Some(SID)));
    assert_eq!(reports(), before + 3);
}
