use super::{Action, command, reply};
use serde_json::Value;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::Path;
use std::process::Command;

/// The exact host command, as the SSH account with [home] and no OMP overrides.
fn host(home: &Path, action: Action) -> Value {
    let output = Command::new("sh")
        .arg("-c")
        .arg(command(action))
        .env("HOME", home)
        .env_remove("PI_CODING_AGENT_DIR")
        .env_remove("PI_CONFIG_DIR")
        .output()
        .unwrap();
    serde_json::from_slice(
        &reply(output.status.code().map(|c| c as u32), &output.stdout).into_bytes(),
    )
    .unwrap()
}

fn omp_home() -> tempfile::TempDir {
    let home = tempfile::tempdir().unwrap();
    std::fs::create_dir_all(home.path().join(".omp/agent")).unwrap();
    home
}

fn target(home: &Path) -> std::path::PathBuf {
    home.join(".omp/agent/extensions/herdline-chat")
}

fn mode(path: &Path) -> u32 {
    std::fs::metadata(path).unwrap().permissions().mode() & 0o777
}

#[test]
fn install_is_private_idempotent_and_reported() {
    let home = omp_home();
    assert_eq!(host(home.path(), Action::Status)["state"], "absent");
    let first = host(home.path(), Action::Install);
    assert_eq!(first["state"], "installed", "{first}");
    let folder = target(&std::fs::canonicalize(home.path()).unwrap());
    assert_eq!(first["path"], folder.to_str().unwrap());
    assert_eq!(
        first["files"],
        serde_json::json!([
            "herdline-core.ts",
            "herdline-ingress.ts",
            "live-subagents.ts",
            "package.json"
        ])
    );
    assert_eq!(mode(&folder), 0o700);
    assert_eq!(mode(folder.parent().unwrap()), 0o700);
    for entry in std::fs::read_dir(&folder).unwrap() {
        assert_eq!(mode(&entry.unwrap().path()), 0o600);
    }
    let manifest: Value =
        serde_json::from_slice(&std::fs::read(folder.join("package.json")).unwrap()).unwrap();
    assert_eq!(
        manifest["omp"]["extensions"],
        serde_json::json!(["./herdline-core.ts", "./herdline-ingress.ts"])
    );
    assert_eq!(
        std::fs::read(folder.join("herdline-ingress.ts")).unwrap(),
        include_bytes!("../../../../host/omp/herdline-ingress.ts")
    );

    // A second install leaves the same folder in place, inode and all.
    let inode = std::fs::metadata(&folder).unwrap().ino();
    assert_eq!(host(home.path(), Action::Install)["state"], "installed");
    assert_eq!(std::fs::metadata(&folder).unwrap().ino(), inode);
    assert_eq!(host(home.path(), Action::Status)["state"], "installed");
    // Staging stays outside OMP's discovery and keeps nothing behind.
    assert_eq!(
        std::fs::read_dir(home.path().join(".herdline-chat"))
            .unwrap()
            .count(),
        0
    );
}

#[test]
fn an_older_install_is_outdated_until_installed_again() {
    let home = omp_home();
    assert_eq!(host(home.path(), Action::Install)["state"], "installed");
    let folder = target(home.path());
    // What an older app version wrote: other contents, its own record.
    let old = "export default function () {}\n";
    std::fs::write(folder.join("herdline-core.ts"), old).unwrap();
    let record_path = folder.join(".herdline-install.json");
    let mut record: Value = serde_json::from_slice(&std::fs::read(&record_path).unwrap()).unwrap();
    let sum = Command::new("python3")
        .args([
            "-c",
            "import hashlib,sys; print(hashlib.sha256(sys.argv[1].encode()).hexdigest())",
            old,
        ])
        .output()
        .unwrap();
    record["files"]["herdline-core.ts"] =
        Value::String(String::from_utf8(sum.stdout).unwrap().trim().to_owned());
    std::fs::write(&record_path, record.to_string()).unwrap();

    assert_eq!(host(home.path(), Action::Status)["state"], "outdated");
    assert_eq!(host(home.path(), Action::Install)["state"], "installed");
    assert_eq!(
        std::fs::read(folder.join("herdline-core.ts")).unwrap(),
        include_bytes!("../../../../host/omp/herdline-core.ts")
    );
}

#[test]
fn a_folder_herdline_did_not_write_is_never_replaced_or_removed() {
    let home = omp_home();
    let folder = target(home.path());
    std::fs::create_dir_all(&folder).unwrap();
    std::fs::write(folder.join("index.ts"), "mine").unwrap();
    for (action, tail) in [
        (Action::Status, ""),
        (Action::Install, " It was not overwritten."),
        (Action::Uninstall, " It was not removed."),
    ] {
        let answer = host(home.path(), action);
        assert_eq!(answer["state"], "error");
        let expected = format!(
            "{} was not written by Herdline or was changed since.{tail}",
            folder.display()
        );
        assert_eq!(answer["message"], expected.as_str());
    }
    assert_eq!(
        std::fs::read_to_string(folder.join("index.ts")).unwrap(),
        "mine"
    );
}

#[test]
fn a_changed_install_is_kept_as_the_user_left_it() {
    let home = omp_home();
    assert_eq!(host(home.path(), Action::Install)["state"], "installed");
    let folder = target(home.path());
    std::fs::write(folder.join("herdline-core.ts"), "edited").unwrap();
    assert_eq!(host(home.path(), Action::Install)["state"], "error");
    assert_eq!(host(home.path(), Action::Uninstall)["state"], "error");
    assert_eq!(
        std::fs::read_to_string(folder.join("herdline-core.ts")).unwrap(),
        "edited"
    );
}

#[test]
fn uninstall_removes_only_the_integration_and_repeats_cleanly() {
    let home = omp_home();
    let other = home.path().join(".omp/agent/extensions/someone-else");
    std::fs::create_dir_all(&other).unwrap();
    assert_eq!(host(home.path(), Action::Install)["state"], "installed");
    let answer = host(home.path(), Action::Uninstall);
    assert_eq!(answer["state"], "absent", "{answer}");
    assert_eq!(answer["files"], serde_json::json!([]));
    assert!(!target(home.path()).exists());
    assert!(other.is_dir());
    assert_eq!(host(home.path(), Action::Uninstall)["state"], "absent");
    assert_eq!(host(home.path(), Action::Status)["state"], "absent");
}

#[test]
fn without_omp_set_up_nothing_is_created() {
    let home = tempfile::tempdir().unwrap();
    let answer = host(home.path(), Action::Install);
    assert_eq!(answer["state"], "error");
    assert!(
        answer["message"]
            .as_str()
            .unwrap()
            .contains("OMP has not been set up for this account")
    );
    assert!(!home.path().join(".omp").exists());
}

#[test]
fn an_override_agent_folder_is_refused() {
    let home = omp_home();
    let output = Command::new("sh")
        .arg("-c")
        .arg(command(Action::Install))
        .env("HOME", home.path())
        .env("PI_CODING_AGENT_DIR", home.path().join("elsewhere"))
        .output()
        .unwrap();
    let answer: Value = serde_json::from_str(&reply(Some(0), &output.stdout)).unwrap();
    assert_eq!(answer["state"], "error");
    assert_eq!(
        answer["message"],
        "PI_CODING_AGENT_DIR is set; only the default OMP agent folder is supported."
    );
    assert!(!target(home.path()).exists());
}

#[test]
fn a_host_failure_is_an_error_with_the_host_text() {
    let answer: Value =
        serde_json::from_str(&reply(Some(127), b"sh: python3: not found\n")).unwrap();
    assert_eq!(answer["state"], "error");
    assert_eq!(answer["message"], "sh: python3: not found");
    let silent: Value = serde_json::from_str(&reply(None, b"")).unwrap();
    assert_eq!(silent["state"], "error");
}

#[test]
fn only_the_three_actions_are_accepted() {
    assert_eq!(Action::parse("install").unwrap(), Action::Install);
    assert!(Action::parse("install; rm -rf ~").is_err());
    assert!(command(Action::Uninstall).ends_with("' uninstall"));
}
