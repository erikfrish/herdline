use super::*;

fn doc() -> Value {
    json!({
        "spaces": [{
            "id": "sp1", "name": "", "folder": "/home/u/proj", "theme": "kept",
            "tabs": [{
                "id": "tb1", "name": "", "pinned": true,
                "root": {"id": "tb1", "type": "row", "children": [
                    {"id": "p1", "type": "block", "content": {"terminal": "t1"}, "size": {"weight": 1.0}},
                    {"id": "p2", "type": "block", "content": {"mod": "files", "props": {}}, "size": {"weight": 1.0}},
                ]},
            }],
        }],
        "future": {"x": 1},
    })
}

#[test]
fn a_rename_keeps_every_field_it_does_not_own() {
    let mut value = doc();
    apply(&mut value, &Edit::RenameTab { tab: "tb1".into(), name: "build".into() }).unwrap();
    let mut expected = doc();
    expected["spaces"][0]["tabs"][0]["name"] = json!("build");
    assert_eq!(value, expected);
}

#[test]
fn a_split_along_the_row_halves_the_target_and_inserts_after_it() {
    let mut value = doc();
    let edit = Edit::Split {
        pane: "p1".into(),
        axis: Axis::Row,
        new_pane: "p3".into(),
        container: "s1".into(),
        terminal: "t3".into(),
    };
    apply(&mut value, &edit).unwrap();
    let children = &value["spaces"][0]["tabs"][0]["root"]["children"];
    assert_eq!(children[0]["size"], json!({"weight": 0.5}));
    assert_eq!(children[1]["id"], "p3");
    assert_eq!(children[1]["content"], json!({"terminal": "t3"}));
    assert_eq!(children[2]["id"], "p2");
}

#[test]
fn a_split_across_the_row_wraps_the_target_in_a_new_container() {
    let mut value = doc();
    let edit = Edit::Split {
        pane: "p1".into(),
        axis: Axis::Column,
        new_pane: "p3".into(),
        container: "s1".into(),
        terminal: "t3".into(),
    };
    apply(&mut value, &edit).unwrap();
    let wrap = &value["spaces"][0]["tabs"][0]["root"]["children"][0];
    assert_eq!(wrap["id"], "s1");
    assert_eq!(wrap["type"], "column");
    assert_eq!(wrap["size"], json!({"weight": 1.0}));
    assert_eq!(wrap["children"][0]["id"], "p1");
    assert_eq!(wrap["children"][1]["id"], "p3");
}

#[test]
fn closing_a_pane_gives_its_weight_to_the_sibling_and_names_its_terminal() {
    let mut value = doc();
    let killed = apply(&mut value, &Edit::ClosePane { pane: "p1".into() }).unwrap();
    assert_eq!(killed, vec!["t1".to_owned()]);
    let root = &value["spaces"][0]["tabs"][0]["root"];
    assert_eq!(root["id"], "tb1");
    assert_eq!(root["children"][0]["id"], "p2");
    assert_eq!(root["children"][0]["size"], json!({"weight": 2.0}));
}

#[test]
fn closing_the_last_pane_closes_its_tab_and_then_its_space() {
    let mut value = doc();
    apply(&mut value, &Edit::ClosePane { pane: "p2".into() }).unwrap();
    let killed = apply(&mut value, &Edit::ClosePane { pane: "p1".into() }).unwrap();
    assert_eq!(killed, vec!["t1".to_owned()]);
    assert_eq!(value["spaces"], json!([]));
    assert_eq!(value["future"], json!({"x": 1}));
}

#[test]
fn an_edit_on_a_missing_target_is_refused() {
    let mut value = doc();
    assert_eq!(
        apply(&mut value, &Edit::ClosePane { pane: "nope".into() }),
        Err(Refusal::Missing)
    );
    assert_eq!(value, doc());
}

#[test]
fn a_space_is_created_in_an_unwritten_document() {
    let mut value = Value::Null;
    let edit = Edit::CreateSpace {
        space: "sp2".into(),
        name: " qa ".into(),
        folder: "/w".into(),
        tab: "tb2".into(),
        pane: "p9".into(),
        terminal: "t9".into(),
    };
    apply(&mut value, &edit).unwrap();
    assert_eq!(value["spaces"][0]["tabs"][0]["root"]["id"], "tb2");
    assert_eq!(value["spaces"][0]["name"], " qa ");
    assert_eq!(value["spaces"][0]["folder"], "/w");
    assert_eq!(terminals(&value["spaces"][0]["tabs"][0]["root"]), vec!["t9".to_owned()]);
}

#[test]
fn the_target_changes_when_its_tab_changes() {
    let before = target(&doc(), &Edit::ClosePane { pane: "p1".into() }).unwrap();
    let mut value = doc();
    apply(&mut value, &Edit::RenameTab { tab: "tb1".into(), name: "x".into() }).unwrap();
    let after = target(&value, &Edit::ClosePane { pane: "p1".into() }).unwrap();
    assert_ne!(before, after);
}

#[test]
fn ids_carry_their_prefix_and_32_hex_digits() {
    let id = new_id("p").unwrap();
    assert_eq!(id.len(), 33);
    assert!(id[1..].chars().all(|c| c.is_ascii_hexdigit()));
}
