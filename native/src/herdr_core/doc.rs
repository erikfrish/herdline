//! herdr-app's shared layout document, `"herdr-app"`, held and edited as raw
//! JSON. Every field this module does not own, at every level, is written
//! back exactly as it was read, so a newer herdr-app's data survives a phone
//! edit. Shapes follow herdr-app 8e1208de `src/mirror.rs` and
//! `src/layout.rs`: `{"spaces":[{id,name,folder,tabs:[{id,name,root}]}]}`,
//! nodes `{id,type:"row"|"column"|"stack"|"block",children|content,size}`.

use crate::{Error, Result};
use serde_json::{Map, Value, json};

/// The document's name in the core.
pub const NAME: &str = "herdr-app";

/// A fresh id: `prefix` and 32 hex digits, as herdr-app makes them.
pub fn new_id(prefix: &str) -> Result<String> {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).map_err(Error::Random)?;
    let mut id = String::with_capacity(prefix.len() + 32);
    id.push_str(prefix);
    for byte in bytes {
        id.push_str(&format!("{byte:02x}"));
    }
    Ok(id)
}

/// Which way a split lays its panes out.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Axis {
    /// Side by side.
    Row,
    /// One above the other.
    Column,
}

impl Axis {
    pub fn word(self) -> &'static str {
        match self {
            Self::Row => "row",
            Self::Column => "column",
        }
    }

    /// Herdr's split direction as the UI names it.
    pub fn from_direction(direction: &str) -> Option<Self> {
        match direction {
            "right" | "left" | "horizontal" => Some(Self::Row),
            "down" | "up" | "vertical" => Some(Self::Column),
            _ => None,
        }
    }
}

/// One edit the phone makes, re-appliable to a fresher document.
#[derive(Debug, Clone, PartialEq)]
pub enum Edit {
    CreateSpace {
        space: String,
        folder: String,
        tab: String,
        pane: String,
        terminal: String,
    },
    CreateTab {
        space: String,
        tab: String,
        name: String,
        pane: String,
        terminal: String,
    },
    RenameSpace {
        space: String,
        name: String,
    },
    RenameTab {
        tab: String,
        name: String,
    },
    CloseSpace {
        space: String,
    },
    CloseTab {
        tab: String,
    },
    ClosePane {
        pane: String,
    },
    Split {
        pane: String,
        axis: Axis,
        new_pane: String,
        container: String,
        terminal: String,
    },
    /// An agent started into a pane this phone just made: the pane's block
    /// moves from its fresh shell `shell` to the agent's `terminal`.
    Launch {
        pane: String,
        shell: String,
        terminal: String,
    },
}

/// Why an edit does not apply to a document.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refusal {
    /// The space, tab or pane it names is not in the document.
    Missing,
    /// The document is not shaped as herdr-app writes it.
    Malformed,
}

fn spaces(doc: &Value) -> &[Value] {
    doc.get("spaces")
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or_default()
}

fn spaces_mut(doc: &mut Value) -> std::result::Result<&mut Vec<Value>, Refusal> {
    if doc.is_null() {
        *doc = json!({});
    }
    let root = doc.as_object_mut().ok_or(Refusal::Malformed)?;
    root.entry("spaces")
        .or_insert_with(|| Value::Array(Vec::new()))
        .as_array_mut()
        .ok_or(Refusal::Malformed)
}

pub fn id(value: &Value) -> &str {
    value.get("id").and_then(Value::as_str).unwrap_or_default()
}

pub fn text<'a>(value: &'a Value, key: &str) -> &'a str {
    value.get(key).and_then(Value::as_str).unwrap_or_default()
}

pub fn kind(node: &Value) -> &str {
    text(node, "type")
}

pub fn children(node: &Value) -> &[Value] {
    node.get("children")
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or_default()
}

/// The spaces in document order.
pub fn space_list(doc: &Value) -> &[Value] {
    spaces(doc)
}

pub fn tabs(space: &Value) -> &[Value] {
    space
        .get("tabs")
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or_default()
}

/// What a block shows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Content<'a> {
    Terminal(&'a str),
    /// A desktop JS panel, or a view this phone cannot draw (`tab`,
    /// `context`, `agent` content).
    Unsupported,
}

pub fn content(block: &Value) -> Content<'_> {
    match block
        .get("content")
        .and_then(|content| content.get("terminal"))
        .and_then(Value::as_str)
    {
        Some(terminal) if !terminal.is_empty() => Content::Terminal(terminal),
        _ => Content::Unsupported,
    }
}

/// Every block under `node`, hidden ones included, in document order.
pub fn blocks(node: &Value) -> Vec<&Value> {
    let mut out = Vec::new();
    collect_blocks(node, &mut out);
    out
}

fn collect_blocks<'a>(node: &'a Value, out: &mut Vec<&'a Value>) {
    if kind(node) == "block" {
        out.push(node);
    }
    for child in children(node) {
        collect_blocks(child, out);
    }
}

/// The terminals every block under `node` shows.
pub fn terminals(node: &Value) -> Vec<String> {
    blocks(node)
        .into_iter()
        .filter_map(|block| match content(block) {
            Content::Terminal(terminal) => Some(terminal.to_owned()),
            Content::Unsupported => None,
        })
        .collect()
}

fn find_space(doc: &Value, space: &str) -> Option<usize> {
    spaces(doc).iter().position(|value| id(value) == space)
}

fn find_tab(doc: &Value, tab: &str) -> Option<(usize, usize)> {
    spaces(doc).iter().enumerate().find_map(|(s, space)| {
        tabs(space)
            .iter()
            .position(|value| id(value) == tab)
            .map(|t| (s, t))
    })
}

/// The tab holding block `pane`.
pub fn tab_of(doc: &Value, pane: &str) -> Option<(usize, usize)> {
    spaces(doc).iter().enumerate().find_map(|(s, space)| {
        tabs(space).iter().position(|tab| {
            tab.get("root")
                .is_some_and(|root| blocks(root).iter().any(|block| id(block) == pane))
        })
        .map(|t| (s, t))
    })
}

/// What an edit acts on, as it is now: an edit re-applied after a conflict
/// goes only if this is unchanged.
pub fn target(doc: &Value, edit: &Edit) -> Option<Value> {
    let space_at = |space: &str| find_space(doc, space).map(|s| spaces(doc)[s].clone());
    let tab_at = |tab: &str| find_tab(doc, tab).map(|(s, t)| tabs(&spaces(doc)[s])[t].clone());
    match edit {
        // A new space needs nothing to exist.
        Edit::CreateSpace { .. } => Some(Value::Null),
        // A new tab only needs its space: other tabs may change meanwhile.
        Edit::CreateTab { space, .. } => find_space(doc, space).map(|_| Value::Null),
        Edit::RenameSpace { space, .. } | Edit::CloseSpace { space } => space_at(space),
        Edit::RenameTab { tab, .. } | Edit::CloseTab { tab } => tab_at(tab),
        Edit::ClosePane { pane } | Edit::Split { pane, .. } => {
            tab_of(doc, pane).map(|(s, t)| tabs(&spaces(doc)[s])[t].clone())
        }
        // Only the very block, still showing that very shell.
        Edit::Launch { pane, shell, .. } => find_block(doc, pane)
            .filter(|block| content(block) == Content::Terminal(shell))
            .cloned(),
    }
}

/// Block `pane`, wherever it is.
fn find_block<'a>(doc: &'a Value, pane: &str) -> Option<&'a Value> {
    spaces(doc)
        .iter()
        .flat_map(tabs)
        .filter_map(|tab| tab.get("root"))
        .find_map(|root| blocks(root).into_iter().find(|block| id(block) == pane))
}

/// Block `pane` under `node`, mutably.
fn block_mut<'a>(node: &'a mut Value, pane: &str) -> Option<&'a mut Value> {
    if kind(node) == "block" && id(node) == pane {
        return Some(node);
    }
    children_mut(node)?
        .iter_mut()
        .find_map(|child| block_mut(child, pane))
}

fn block(pane: &str, terminal: &str) -> Value {
    json!({"id": pane, "type": "block", "content": {"terminal": terminal}})
}

fn new_tab(tab: &str, name: &str, pane: &str, terminal: &str) -> Value {
    // The root keeps the tab's id and never collapses, as herdr-app's does.
    json!({
        "id": tab,
        "name": name,
        "root": {"id": tab, "type": "row", "children": [block(pane, terminal)]},
    })
}

/// Apply `edit` to `doc`. Answers the terminals the edit removed from the
/// document, which the caller kills once the write is confirmed.
pub fn apply(doc: &mut Value, edit: &Edit) -> std::result::Result<Vec<String>, Refusal> {
    match edit {
        Edit::CreateSpace {
            space,
            folder,
            tab,
            pane,
            terminal,
        } => {
            spaces_mut(doc)?.push(json!({
                "id": space,
                "name": "",
                "folder": folder,
                "tabs": [new_tab(tab, "", pane, terminal)],
            }));
            Ok(Vec::new())
        }
        Edit::CreateTab {
            space,
            tab,
            name,
            pane,
            terminal,
        } => {
            let s = find_space(doc, space).ok_or(Refusal::Missing)?;
            let space = &mut spaces_mut(doc)?[s];
            let object = space.as_object_mut().ok_or(Refusal::Malformed)?;
            object
                .entry("tabs")
                .or_insert_with(|| Value::Array(Vec::new()))
                .as_array_mut()
                .ok_or(Refusal::Malformed)?
                .push(new_tab(tab, name, pane, terminal));
            Ok(Vec::new())
        }
        Edit::RenameSpace { space, name } => {
            let s = find_space(doc, space).ok_or(Refusal::Missing)?;
            set_name(&mut spaces_mut(doc)?[s], name)?;
            Ok(Vec::new())
        }
        Edit::RenameTab { tab, name } => {
            let (s, t) = find_tab(doc, tab).ok_or(Refusal::Missing)?;
            set_name(tab_mut(doc, s, t)?, name)?;
            Ok(Vec::new())
        }
        Edit::CloseSpace { space } => {
            let s = find_space(doc, space).ok_or(Refusal::Missing)?;
            let removed = spaces_mut(doc)?.remove(s);
            Ok(tabs(&removed)
                .iter()
                .filter_map(|tab| tab.get("root"))
                .flat_map(terminals)
                .collect())
        }
        Edit::CloseTab { tab } => {
            let (s, t) = find_tab(doc, tab).ok_or(Refusal::Missing)?;
            Ok(close_tab(doc, s, t)?)
        }
        Edit::ClosePane { pane } => {
            let (s, t) = tab_of(doc, pane).ok_or(Refusal::Missing)?;
            let root = tab_mut(doc, s, t)?
                .get_mut("root")
                .ok_or(Refusal::Malformed)?;
            // A tab whose root is that very block closes with it.
            if id(root) == pane {
                return close_tab(doc, s, t);
            }
            let removed = remove_block(root, pane)?.ok_or(Refusal::Missing)?;
            let killed = terminals(&removed);
            // The last pane closes the tab, and the last tab the space.
            let root = tab_mut(doc, s, t)?.get("root").ok_or(Refusal::Malformed)?;
            if blocks(root).is_empty() {
                let mut more = close_tab(doc, s, t)?;
                more.extend(killed);
                return Ok(more);
            }
            Ok(killed)
        }
        Edit::Split {
            pane,
            axis,
            new_pane,
            container,
            terminal,
        } => {
            let (s, t) = tab_of(doc, pane).ok_or(Refusal::Missing)?;
            let root = tab_mut(doc, s, t)?
                .get_mut("root")
                .ok_or(Refusal::Malformed)?;
            if id(root) == pane {
                // The root block itself splits: it moves into a new root
                // container, and the root keeps its place.
                let mut old = root.take();
                let mut fresh = block(new_pane, terminal);
                set_weight(&mut old, 1.0);
                set_weight(&mut fresh, 1.0);
                *root = json!({"id": container, "type": axis.word(), "children": [old, fresh]});
                return Ok(Vec::new());
            }
            if split(root, pane, *axis, block(new_pane, terminal), container)? {
                Ok(Vec::new())
            } else {
                Err(Refusal::Missing)
            }
        }
        Edit::Launch {
            pane,
            shell,
            terminal,
        } => {
            let (s, t) = tab_of(doc, pane).ok_or(Refusal::Missing)?;
            let root = tab_mut(doc, s, t)?
                .get_mut("root")
                .ok_or(Refusal::Malformed)?;
            let block = block_mut(root, pane).ok_or(Refusal::Missing)?;
            if content(block) != Content::Terminal(shell) {
                return Err(Refusal::Missing);
            }
            // Only the terminal changes; the block's other keys stay.
            block
                .get_mut("content")
                .and_then(Value::as_object_mut)
                .ok_or(Refusal::Malformed)?
                .insert("terminal".into(), Value::String(terminal.clone()));
            Ok(vec![shell.clone()])
        }
    }
}

fn set_name(value: &mut Value, name: &str) -> std::result::Result<(), Refusal> {
    value
        .as_object_mut()
        .ok_or(Refusal::Malformed)?
        .insert("name".into(), Value::String(name.to_owned()));
    Ok(())
}

fn tab_mut(doc: &mut Value, s: usize, t: usize) -> std::result::Result<&mut Value, Refusal> {
    spaces_mut(doc)?
        .get_mut(s)
        .and_then(|space| space.get_mut("tabs"))
        .and_then(Value::as_array_mut)
        .and_then(|tabs| tabs.get_mut(t))
        .ok_or(Refusal::Malformed)
}

/// Remove tab `t` of space `s`, and the space with its last tab.
fn close_tab(doc: &mut Value, s: usize, t: usize) -> std::result::Result<Vec<String>, Refusal> {
    let spaces = spaces_mut(doc)?;
    let space = spaces.get_mut(s).ok_or(Refusal::Malformed)?;
    let list = space
        .get_mut("tabs")
        .and_then(Value::as_array_mut)
        .ok_or(Refusal::Malformed)?;
    if t >= list.len() {
        return Err(Refusal::Malformed);
    }
    let removed = list.remove(t);
    if list.is_empty() {
        spaces.remove(s);
    }
    Ok(removed.get("root").map(terminals).unwrap_or_default())
}

fn children_mut(node: &mut Value) -> Option<&mut Vec<Value>> {
    node.get_mut("children").and_then(Value::as_array_mut)
}

fn weight(node: &Value) -> Option<f64> {
    node.get("size")
        .and_then(|size| size.get("weight"))
        .and_then(Value::as_f64)
}

fn set_weight(node: &mut Value, weight: f64) {
    if let Some(object) = node.as_object_mut() {
        object.insert("size".into(), json!({"weight": weight}));
    }
}

/// Remove block `pane` under `node`; its weight goes to the sibling before
/// it, else after it, and a container left with one child is replaced by
/// it. `node` itself, the tab root, never collapses.
fn remove_block(node: &mut Value, pane: &str) -> std::result::Result<Option<Value>, Refusal> {
    let Some(list) = children_mut(node) else {
        return Ok(None);
    };
    if let Some(at) = list.iter().position(|child| id(child) == pane) {
        let removed = list.remove(at);
        if let Some(gone) = weight(&removed) {
            let heir = if at > 0 { at - 1 } else { 0 };
            if let Some(heir) = list.get_mut(heir)
                && let Some(own) = weight(heir)
            {
                set_weight(heir, own + gone);
            }
        }
        return Ok(Some(removed));
    }
    for index in 0..list.len() {
        let child = &mut list[index];
        if let Some(removed) = remove_block(child, pane)? {
            let child = &mut list[index];
            let left = children(child).len();
            if left == 0 && kind(child) != "block" {
                list.remove(index);
            } else if left == 1 && matches!(kind(child), "row" | "column") {
                let size = child.get("size").cloned();
                let mut only = children_mut(child)
                    .and_then(|children| children.pop())
                    .ok_or(Refusal::Malformed)?;
                if let (Some(size), Some(object)) = (size, only.as_object_mut()) {
                    object.insert("size".into(), size);
                }
                list[index] = only;
            }
            return Ok(Some(removed));
        }
    }
    Ok(None)
}

/// Put `fresh` next to block `pane`: after it in the same container when
/// that runs along `axis`, halving its weight; else both wrapped in a new
/// container that takes the old block's size.
fn split(
    node: &mut Value,
    pane: &str,
    axis: Axis,
    mut fresh: Value,
    container: &str,
) -> std::result::Result<bool, Refusal> {
    let along = kind(node) == axis.word();
    let Some(list) = children_mut(node) else {
        return Ok(false);
    };
    if let Some(at) = list.iter().position(|child| id(child) == pane) {
        if along {
            let half = weight(&list[at]).unwrap_or(1.0) / 2.0;
            set_weight(&mut list[at], half);
            set_weight(&mut fresh, half);
            list.insert(at + 1, fresh);
        } else {
            let mut old = list[at].take();
            let size = old.as_object_mut().and_then(|object| object.remove("size"));
            set_weight(&mut old, 1.0);
            set_weight(&mut fresh, 1.0);
            let mut wrap = Map::new();
            wrap.insert("id".into(), Value::String(container.to_owned()));
            wrap.insert("type".into(), Value::String(axis.word().to_owned()));
            wrap.insert("children".into(), Value::Array(vec![old, fresh]));
            if let Some(size) = size {
                wrap.insert("size".into(), size);
            }
            list[at] = Value::Object(wrap);
        }
        return Ok(true);
    }
    for child in list.iter_mut() {
        if split(child, pane, axis, fresh.clone(), container)? {
            return Ok(true);
        }
    }
    Ok(false)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
