#![allow(clippy::unwrap_used)]

use super::*;
use std::{
    io::Write as _,
    os::unix::fs::{PermissionsExt, symlink},
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

const HELLO_SHA: &str = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

/// A fresh, empty `$HOME` for one test.
fn home(test: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("herdline-attach-{test}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn root(home: &Path) -> PathBuf {
    home.join(".cache/herdline/attachments")
}

/// Run `script` as the host would, with `input` on its stdin.
fn host(home: &Path, script: &str, input: &[u8]) -> Output {
    let mut child = Command::new("/bin/sh")
        .arg("-c")
        .arg(script)
        .env("HOME", home)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(input).unwrap();
    let out = child.wait_with_output().unwrap();
    Output {
        exit_status: out.status.code().and_then(|code| u32::try_from(code).ok()),
        tail: out.stdout,
    }
}

fn mode(path: &Path) -> u32 {
    std::fs::metadata(path).unwrap().permissions().mode() & 0o777
}

fn instance() -> Identity {
    Identity {
        pane_id: "w1:p1".into(),
        terminal_id: "term_1".into(),
        pid: Some(42),
        path: "/home/u/.omp/agent/sessions/x/a.jsonl".into(),
        ..Identity::default()
    }
}

fn attachment(kind: Kind, name: &str) -> Attachment {
    Attachment {
        kind,
        name: name.into(),
        path: format!("/home/u/.cache/herdline/attachments/AbC123xyz0/{name}"),
        size: 5,
        sha256: HELLO_SHA.into(),
        node: "n1".into(),
        session: "lab".into(),
        boot: "boot-1".into(),
        pane: "w1:p1".into(),
        instance: Some(instance()),
    }
}

fn bound(instance: Option<&Identity>) -> Bound<'_> {
    Bound {
        node: "n1",
        session: "lab",
        boot: "boot-1",
        pane: "w1:p1",
        instance,
    }
}

fn pasted(events: &[ClientPaneInputEvent]) -> Vec<&str> {
    events
        .iter()
        .map(|event| match event {
            ClientPaneInputEvent::Paste(text) => text.as_str(),
            other => panic!("not a paste: {other:?}"),
        })
        .collect()
}

#[test]
fn names_that_could_escape_quote_or_hide_are_refused() {
    for name in [
        "../x.png",
        "a/b.png",
        ".hidden.png",
        "a b.png",
        "x\".png",
        "",
        "é.png",
    ] {
        assert!(!valid_name(name, Kind::Image), "{name}");
        assert!(!valid_name(name, Kind::File), "{name}");
    }
    assert!(!valid_name(&"a".repeat(NAME_MAX + 1), Kind::File));
    assert!(valid_name(&"a".repeat(NAME_MAX), Kind::File));
}

#[test]
fn an_image_needs_an_extension_omp_reads_as_an_image() {
    for name in ["shot.png", "IMG_1.jpg", "a.jpeg", "b.gif", "c.webp"] {
        assert!(valid_name(name, Kind::Image), "{name}");
    }
    for name in ["photo.heic", "png", ".png", "x.PNG.txt", "notes"] {
        assert!(!valid_name(name, Kind::Image), "{name}");
    }
    assert!(valid_name("Makefile", Kind::File));
    assert!(valid_name("photo.heic", Kind::File));
}

#[test]
fn only_absolute_plain_paths_are_pasted() {
    assert!(valid_path("/home/u/.cache/herdline/attachments/AbC/x.png"));
    for path in [
        "home/u/x",
        "/home/../etc/x",
        "/a/./b",
        "//a",
        "/a//b",
        "/a/",
        "/a b",
        "/a'b",
        "/a\nb",
    ] {
        assert!(!valid_path(path), "{path:?}");
    }
}

#[test]
fn attachments_go_only_to_the_target_they_were_staged_for() {
    let it = instance();
    let image = attachment(Kind::Image, "shot.png");
    assert_eq!(
        admit(std::slice::from_ref(&image), bound(Some(&it))),
        Ok(())
    );
    assert_eq!(admit(&[], bound(None)), Ok(()));
    assert_eq!(
        admit(std::slice::from_ref(&image), bound(None)),
        Err(SendRefusal::AttachmentNeedsAgent)
    );
    let other = Identity {
        pid: Some(43),
        ..instance()
    };
    assert_eq!(
        admit(std::slice::from_ref(&image), bound(Some(&other))),
        Err(SendRefusal::AttachmentTarget)
    );
    let changes: [fn(&mut Attachment); 6] = [
        |a| a.node = "n2".into(),
        |a| a.session = "other".into(),
        |a| a.boot = "boot-2".into(),
        |a| a.pane = "w1:p2".into(),
        |a| a.path = "/home/u/x/../shot.png".into(),
        |a| a.path = "/home/u/.cache/herdline/attachments/AbC123xyz0/other.png".into(),
    ];
    for change in changes {
        let mut moved = image.clone();
        change(&mut moved);
        assert_eq!(
            admit(&[moved.clone()], bound(Some(&it))),
            Err(SendRefusal::AttachmentTarget),
            "{moved:?}"
        );
    }
}

#[test]
fn each_image_is_its_own_paste_ahead_of_the_text_and_files_are_mentioned() {
    let image = attachment(Kind::Image, "shot.png");
    let file = attachment(Kind::File, "notes.txt");
    let events = pastes("look at this", &[file.clone(), image.clone()]);
    assert_eq!(
        pasted(&events),
        [
            image.path.as_str(),
            &format!("look at this @\"{}\"", file.path),
        ]
    );
    assert_eq!(
        pasted(&pastes("", std::slice::from_ref(&image))),
        [image.path.as_str()]
    );
    assert_eq!(
        pasted(&pastes("", std::slice::from_ref(&file))),
        [format!("@\"{}\"", file.path).as_str()]
    );
    assert_eq!(pasted(&pastes("hi", &[])), ["hi"]);
}

#[test]
fn an_upload_lands_in_a_private_directory_and_reports_what_it_wrote() {
    let home = home("upload");
    let output = host(&home, &begin_script("shot.png", IMAGE_LIMIT), b"hello");
    assert_eq!(output.exit_status, Some(0));
    let part = parse_part(&output);
    let dir = part.dir.clone().unwrap();
    assert_eq!(part.written, Some((5, HELLO_SHA.to_owned())));
    assert!(Path::new(&dir).starts_with(root(&home)));
    assert_eq!(mode(&root(&home)), 0o700);
    assert_eq!(mode(Path::new(&dir)), 0o700);
    assert_eq!(mode(&Path::new(&dir).join("shot.png.part")), 0o600);
    assert_eq!(
        verdict(Kind::Image, 5, HELLO_SHA, &(5, HELLO_SHA.to_owned())),
        Ok(())
    );

    let committed = host(&home, &commit_script(&dir, "shot.png", 5), b"");
    assert_eq!(committed.exit_status, Some(0));
    let text = String::from_utf8(committed.tail).unwrap();
    let path = text.trim().strip_prefix("@@staged ").unwrap();
    assert!(staged_in(path, &dir, "shot.png"));
    assert_eq!(std::fs::read(path).unwrap(), b"hello");
    assert!(!Path::new(&dir).join("shot.png.part").exists());
}

#[test]
fn a_symlinked_staging_root_is_refused_and_nothing_is_written_through_it() {
    let home = home("symlink");
    let elsewhere = home.join("elsewhere");
    std::fs::create_dir_all(&elsewhere).unwrap();
    std::fs::create_dir_all(home.join(".cache/herdline")).unwrap();
    symlink(&elsewhere, root(&home)).unwrap();
    let output = host(&home, &begin_script("shot.png", IMAGE_LIMIT), b"hello");
    assert_eq!(refusal(output.exit_status), Refusal::StagingUnsafe);
    assert_eq!(parse_part(&output).dir, None);
    assert_eq!(std::fs::read_dir(&elsewhere).unwrap().count(), 0);
}

#[test]
fn an_upload_past_its_limit_or_unlike_what_the_phone_sent_is_not_kept() {
    let home = home("limit");
    let output = host(&home, &begin_script("notes.txt", 4), b"hello");
    let part = parse_part(&output);
    // The host stops reading one byte past the limit.
    let written = part.written.unwrap();
    assert_eq!(written.0, 5);
    assert_eq!(
        verdict(
            Kind::File,
            5,
            HELLO_SHA,
            &(FILE_LIMIT + 1, HELLO_SHA.into())
        ),
        Err(Refusal::TooLarge)
    );
    assert_eq!(
        verdict(Kind::Image, IMAGE_LIMIT + 1, HELLO_SHA, &written),
        Err(Refusal::TooLarge)
    );
    assert_eq!(
        verdict(Kind::File, 4, HELLO_SHA, &written),
        Err(Refusal::Mismatch)
    );
    assert_eq!(
        verdict(Kind::File, 5, &"0".repeat(64), &written),
        Err(Refusal::Mismatch)
    );
    assert_eq!(
        verdict(Kind::File, 5, "HELLO", &written),
        Err(Refusal::Mismatch)
    );

    // A size the host does not hold removes the upload.
    let dir = part.dir.unwrap();
    let committed = host(&home, &commit_script(&dir, "notes.txt", 4), b"");
    assert_eq!(refusal(committed.exit_status), Refusal::Mismatch);
    assert!(!Path::new(&dir).exists());
}

#[test]
fn commit_and_discard_touch_nothing_outside_one_staging_directory() {
    let home = home("outside");
    let victim = home.join("victim");
    std::fs::create_dir_all(&victim).unwrap();
    std::fs::write(victim.join("keep.png.part"), b"hello").unwrap();
    let root = root(&home);
    std::fs::create_dir_all(&root).unwrap();
    let escapes = [
        victim.display().to_string(),
        format!("{}/../../../victim", root.display()),
        root.display().to_string(),
        format!("{}/a/b", root.display()),
    ];
    for dir in &escapes {
        let committed = host(&home, &commit_script(dir, "keep.png", 5), b"");
        assert_eq!(
            refusal(committed.exit_status),
            Refusal::StagingUnsafe,
            "{dir}"
        );
        let discarded = host(&home, &discard_script(dir), b"");
        assert_eq!(
            refusal(discarded.exit_status),
            Refusal::StagingUnsafe,
            "{dir}"
        );
    }
    assert!(victim.join("keep.png.part").exists());
    assert!(root.exists());
}

#[test]
fn a_cancelled_upload_is_removed() {
    let home = home("cancel");
    // Stdin closed early: what the host holds is less than was meant.
    let output = host(&home, &begin_script("notes.txt", FILE_LIMIT), b"hel");
    let dir = parse_part(&output).dir.unwrap();
    assert!(Path::new(&dir).exists());
    let discarded = host(&home, &discard_script(&dir), b"");
    assert_eq!(discarded.exit_status, Some(0));
    assert!(!Path::new(&dir).exists());
    assert!(root(&home).exists());
}

#[test]
fn a_reply_that_names_an_unsafe_directory_is_not_believed() {
    let output = |text: &str| Output {
        exit_status: Some(0),
        tail: text.as_bytes().to_vec(),
    };
    assert_eq!(parse_part(&output("@@dir relative/x\n")).dir, None);
    assert_eq!(parse_part(&output("@@dir /a/../b\n")).dir, None);
    assert_eq!(
        parse_part(&output(&format!("@@part 5 {}\n", "G".repeat(64)))).written,
        None
    );
    assert_eq!(parse_part(&output("@@part five x\n")).written, None);
}
