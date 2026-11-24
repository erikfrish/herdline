//! The Kotlin app's methods on the pump: phone-private focus and zoom,
//! local scrollback, interactive attaches, input, and document edits.

use super::{
    doc::{self, Axis, Edit, Refusal},
    keys,
    pump::{Flow, Pending, Pump},
    wire::Kind,
};
use crate::{
    Result,
    input::{self, Touch},
    session::UiEvent,
};
use herdr_client::protocol::ClientPaneInputEvent;
use libghostty_vt::terminal::ScrollViewport;
use serde_json::{Value, json};

/// A fresh document ID, or `None` (logged) when no randomness is available.
fn fresh(prefix: &str) -> Option<String> {
    doc::new_id(prefix)
        .inspect_err(|error| tracing::warn!(%error, "no document id"))
        .ok()
}

fn word(params: &Value, key: &str) -> String {
    params[key].as_str().unwrap_or_default().to_owned()
}

/// A core template or terminal name: 1-32 of a-z 0-9 - _ . as core takes.
fn core_name(name: &str) -> bool {
    (1..=32).contains(&name.len())
        && name
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'-' | b'_' | b'.'))
}

/// What Kotlin's `parseCreated` / `parseCreatedWorkspace` read from a
/// create's answer. A workspace also answers its stored label and whether
/// it was focused, as Herdr's `WorkspaceInfo` does.
fn created_reply(edit: &Edit, focus: bool) -> Value {
    match edit {
        Edit::CreateSpace {
            space,
            name,
            tab,
            pane,
            terminal,
            ..
        } => json!({
            "workspace": {"workspace_id": space, "label": name, "focused": focus},
            "tab": {"tab_id": tab},
            "root_pane": {"pane_id": pane, "terminal_id": terminal},
        }),
        Edit::CreateTab {
            tab, pane, terminal, ..
        } => json!({
            "tab": {"tab_id": tab},
            "root_pane": {"pane_id": pane, "terminal_id": terminal},
        }),
        Edit::Split {
            new_pane, terminal, ..
        } => json!({"pane": {"pane_id": new_pane, "terminal_id": terminal}}),
        Edit::Launch { pane, terminal, .. } => {
            json!({"started": true, "pane_id": pane, "terminal_id": terminal})
        }
        _ => json!({}),
    }
}

/// What `workspace.create` asks beyond fresh IDs.
#[derive(Debug, PartialEq)]
struct SpaceAsk {
    name: String,
    folder: String,
    env: Vec<(String, String)>,
}

/// Reads `workspace.create` as Herdr 0.9.5 does (`app/api/workspaces.rs:44-67`):
/// a `cwd` wins and the source is then not even looked up; otherwise a named
/// source must exist; then the env is checked. `folder_of` answers a space's
/// folder. No source and no `cwd` leaves the folder to the new terminal.
/// An error is a definite refusal, `(kind, message)`, before any terminal.
fn space_ask(
    params: &Value,
    folder_of: impl Fn(&str) -> Option<String>,
) -> std::result::Result<SpaceAsk, (String, String)> {
    let folder = match (params["cwd"].as_str(), params["source_workspace_id"].as_str()) {
        (Some(cwd), _) => cwd.to_owned(),
        (None, Some(source)) => folder_of(source).ok_or_else(|| {
            ("workspace_not_found".to_owned(), format!("workspace {source} not found"))
        })?,
        (None, None) => String::new(),
    };
    Ok(SpaceAsk {
        name: word(params, "label"),
        folder,
        env: launch_env(&params["env"])?,
    })
}

/// The launch env as Herdr's `normalize_launch_env` takes it
/// (`app/api/env.rs:3-33`): same rejections, codes and messages, sorted by key.
fn launch_env(env: &Value) -> std::result::Result<Vec<(String, String)>, (String, String)> {
    let invalid = |message: String| Err(("invalid_env".to_owned(), message));
    if env.is_null() {
        return Ok(Vec::new());
    }
    let Some(map) = env.as_object() else {
        return Err(("invalid".to_owned(), "invalid_params".to_owned()));
    };
    let mut normalized = Vec::with_capacity(map.len());
    for (key, value) in map {
        let Some(value) = value.as_str() else {
            return Err(("invalid".to_owned(), "invalid_params".to_owned()));
        };
        if key.is_empty() {
            return invalid("env key must not be empty".to_owned());
        }
        if key.contains('=') {
            return invalid(format!("env key {key} must not contain '='"));
        }
        if key.contains('\0') {
            return invalid("env key must not contain NUL bytes".to_owned());
        }
        if value.contains('\0') {
            return invalid(format!("env value for {key} must not contain NUL bytes"));
        }
        normalized.push((key.clone(), value.to_owned()));
    }
    normalized.sort_by(|left, right| left.0.cmp(&right.0));
    Ok(normalized)
}

/// A create's `terminal.run`. The launch env goes on over the base env, so
/// it wins on a shared key, as in Herdr 0.9.5, whose pane spawn applies its
/// terminal env and then the launch env (`pane.rs:2393-2394`, `:105-107`,
/// `:199-201`).
fn run_params(space: &str, tab: &str, cwd: Option<&str>, env: &[(String, String)]) -> Value {
    let mut params = json!({
        "argv": [],
        "keep": true,
        "env": {"TERM": "xterm-256color", "COLORTERM": "truecolor", "TERM_PROGRAM": "herdr"},
        "labels": {"herdr.space": space, "herdr.tab": tab, "herdr.seen": "0"},
    });
    for (key, value) in env {
        params["env"][key] = json!(value);
    }
    if let Some(cwd) = cwd {
        params["cwd"] = json!(cwd);
    }
    params
}

impl Pump {
    fn find_space(&self, space: &str) -> Option<&Value> {
        doc::space_list(&self.doc)
            .iter()
            .find(|candidate| doc::id(candidate) == space)
    }

    /// The space holding tab `tab`.
    fn space_of_tab(&self, tab: &str) -> Option<String> {
        doc::space_list(&self.doc)
            .iter()
            .find(|space| doc::tabs(space).iter().any(|candidate| doc::id(candidate) == tab))
            .map(|space| doc::id(space).to_owned())
    }

    /// The space and tab IDs holding pane `pane`.
    fn place_of(&self, pane: &str) -> Option<(String, String)> {
        let (s, t) = doc::tab_of(&self.doc, pane)?;
        let space = doc::space_list(&self.doc).get(s)?;
        let tab = doc::tabs(space).get(t)?;
        Some((doc::id(space).to_owned(), doc::id(tab).to_owned()))
    }

    fn cwd_of(&self, terminal: &str) -> Option<String> {
        let record = self.records.get(terminal)?;
        record["foreground"]["cwd"]
            .as_str()
            .or_else(|| record["last_cwd"].as_str())
            .or_else(|| record["cwd"].as_str())
            .filter(|cwd| !cwd.is_empty())
            .map(str::to_owned)
    }

    fn moved(&mut self) {
        self.dirty_view = true;
        self.dirty_frame = true;
        self.dirty_sync = true;
    }

    fn focus_pane(&mut self, space: String, tab: String, pane: String) {
        self.focus.tabs.insert(space.clone(), tab.clone());
        self.focus.space = Some(space);
        self.focus.panes.insert(tab, pane);
        self.moved();
    }

    pub(super) fn request(&mut self, id: String, method: &str, params: &Value) -> Result<()> {
        let missing = |pump: &Self, id: &str| pump.refuse(id, "missing", "missing");
        match method {
            "workspace.focus" => {
                let space = word(params, "workspace_id");
                if self.find_space(&space).is_none() {
                    missing(self, &id);
                    return Ok(());
                }
                self.focus.space = Some(space);
                self.moved();
                self.answer(&id, json!({}));
            }
            "tab.focus" => {
                let tab = word(params, "tab_id");
                let Some(space) = self.space_of_tab(&tab) else {
                    missing(self, &id);
                    return Ok(());
                };
                self.focus.tabs.insert(space.clone(), tab);
                self.focus.space = Some(space);
                self.moved();
                self.answer(&id, json!({}));
            }
            "pane.focus" => {
                let pane = word(params, "pane_id");
                let Some((space, tab)) = self.place_of(&pane) else {
                    missing(self, &id);
                    return Ok(());
                };
                // A pane hidden behind another's zoom shows once focused.
                if self.focus.zoomed.get(&tab).is_some_and(|zoomed| *zoomed != pane) {
                    self.focus.zoomed.remove(&tab);
                }
                self.focus_pane(space, tab, pane);
                self.answer(&id, json!({}));
            }
            "pane.zoom" => {
                let pane = word(params, "pane_id");
                let Some((space, tab)) = self.place_of(&pane) else {
                    missing(self, &id);
                    return Ok(());
                };
                let zoomed = self.focus.zoomed.get(&tab) == Some(&pane);
                let zoom = match params["mode"].as_str() {
                    Some("on" | "zoom" | "zoomed") => true,
                    Some("off" | "unzoom" | "unzoomed") => false,
                    _ => !zoomed,
                };
                if zoom {
                    self.focus.zoomed.insert(tab.clone(), pane.clone());
                } else {
                    self.focus.zoomed.remove(&tab);
                }
                self.focus_pane(space, tab, pane);
                self.answer(&id, json!({"zoomed": zoom}));
            }
            "pane.scroll" => {
                let pane = word(params, "pane_id");
                let offset = params["offset_from_bottom"]
                    .as_u64()
                    .and_then(|offset| usize::try_from(offset).ok())
                    .unwrap_or(0);
                let Some(terminal) = self.terminal_of.get(&pane).cloned() else {
                    missing(self, &id);
                    return Ok(());
                };
                self.scroll_to(&terminal, offset);
                self.answer(&id, json!({}));
            }
            "terminal.interact" => {
                let pane = params["pane"].as_str().filter(|pane| !pane.is_empty());
                if self.visible_terminal.as_deref() != pane {
                    self.visible_terminal = pane.map(str::to_owned);
                    self.attempted_terminal = None;
                    self.dirty_sync = true;
                }
                self.answer(&id, json!({}));
            }
            "pane.rename" => self.refuse(&id, "unsupported", "unsupported_backend"),
            "workspace.rename" => {
                let edit = Edit::RenameSpace {
                    space: word(params, "workspace_id"),
                    name: word(params, "label"),
                };
                return self.start(id, edit, false);
            }
            "tab.rename" => {
                let edit = Edit::RenameTab {
                    tab: word(params, "tab_id"),
                    name: word(params, "label"),
                };
                return self.start(id, edit, false);
            }
            "workspace.close" => {
                let edit = Edit::CloseSpace {
                    space: word(params, "workspace_id"),
                };
                return self.start(id, edit, false);
            }
            "tab.close" => {
                let edit = Edit::CloseTab {
                    tab: word(params, "tab_id"),
                };
                return self.start(id, edit, false);
            }
            "pane.close" => {
                let edit = Edit::ClosePane {
                    pane: word(params, "pane_id"),
                };
                return self.start(id, edit, false);
            }
            "pane.split" => {
                let pane = word(params, "target_pane_id");
                let Some(axis) = params["direction"].as_str().and_then(Axis::from_direction)
                else {
                    self.refuse(&id, "invalid", "direction");
                    return Ok(());
                };
                let (Some(new_pane), Some(container)) = (fresh("p"), fresh("n")) else {
                    self.refuse(&id, "error", "random_unavailable");
                    return Ok(());
                };
                let edit = Edit::Split {
                    pane,
                    axis,
                    new_pane,
                    container,
                    terminal: String::new(),
                };
                return self.start(id, edit, params["focus"].as_bool().unwrap_or(true));
            }
            "tab.create" => {
                let space = word(params, "workspace_id");
                let (Some(tab), Some(pane)) = (fresh("t"), fresh("p")) else {
                    self.refuse(&id, "error", "random_unavailable");
                    return Ok(());
                };
                let edit = Edit::CreateTab {
                    space,
                    tab,
                    name: word(params, "label"),
                    pane,
                    terminal: String::new(),
                };
                return self.start(id, edit, params["focus"].as_bool().unwrap_or(true));
            }
            "workspace.create" => {
                let ask = match space_ask(params, |source| {
                    self.find_space(source)
                        .map(|space| doc::text(space, "folder").to_owned())
                }) {
                    Ok(ask) => ask,
                    Err((kind, message)) => {
                        self.refuse(&id, &kind, &message);
                        return Ok(());
                    }
                };
                let (Some(space), Some(tab), Some(pane)) = (fresh("s"), fresh("t"), fresh("p"))
                else {
                    self.refuse(&id, "error", "random_unavailable");
                    return Ok(());
                };
                let edit = Edit::CreateSpace {
                    space,
                    name: ask.name,
                    folder: ask.folder,
                    tab,
                    pane,
                    terminal: String::new(),
                };
                let focus = params["focus"].as_bool().unwrap_or(true);
                return self.start_with_env(id, edit, focus, &ask.env);
            }
            "agent.start" => return self.launch(id, params),
            _ => self.refuse(&id, "unknown", "unknown_method"),
        }
        Ok(())
    }

    /// Begin an edit: a create first gets its terminal from the core.
    fn start(&mut self, ui: String, edit: Edit, focus: bool) -> Result<()> {
        self.start_with_env(ui, edit, focus, &[])
    }

    /// [`Self::start`], with `env` over a created terminal's base env.
    fn start_with_env(
        &mut self,
        ui: String,
        edit: Edit,
        focus: bool,
        env: &[(String, String)],
    ) -> Result<()> {
        let (space, tab, cwd) = match &edit {
            Edit::CreateSpace {
                space, tab, folder, ..
            } => (
                space.clone(),
                tab.clone(),
                Some(folder.clone()).filter(|folder| !folder.is_empty()),
            ),
            Edit::CreateTab { space, tab, .. } => {
                let Some(found) = self.find_space(space) else {
                    self.refuse(&ui, "missing", "missing");
                    return Ok(());
                };
                let folder = doc::text(found, "folder");
                (
                    space.clone(),
                    tab.clone(),
                    Some(folder.to_owned()).filter(|folder| !folder.is_empty()),
                )
            }
            Edit::Split { pane, .. } => {
                let Some((space, tab)) = self.place_of(pane) else {
                    self.refuse(&ui, "missing", "missing");
                    return Ok(());
                };
                let cwd = self
                    .terminal_of
                    .get(pane)
                    .and_then(|terminal| self.cwd_of(terminal));
                (space, tab, cwd)
            }
            _ => {
                return self.commit(Flow {
                    ui,
                    edit,
                    created: None,
                    target: None,
                    retried: false,
                    refusal: None,
                    focus,
                });
            }
        };
        let params = run_params(&space, &tab, cwd.as_deref(), env);
        let flow = Flow {
            ui,
            edit,
            created: None,
            target: None,
            retried: false,
            refusal: None,
            focus,
        };
        self.call("terminal.run", params, Pending::Run(flow))
    }

    /// Start template `kind` for the fresh shell pane the UI just made, only
    /// while that pane still shows exactly that shell with nothing running
    /// in it: the agent gets a terminal of its own, and the pane's block
    /// moves to it once the document write is confirmed. The shell is
    /// killed only after that; a refused write kills the agent instead.
    fn launch(&mut self, ui: String, params: &Value) -> Result<()> {
        let (pane, tab, shell) = (word(params, "pane"), word(params, "tab"), word(params, "terminal"));
        let (kind, name) = (word(params, "kind"), word(params, "name"));
        if !core_name(&kind) || !name.is_empty() && !core_name(&name) {
            self.refuse(&ui, "invalid", "invalid_params");
            return Ok(());
        }
        let edit = Edit::Launch {
            pane: pane.clone(),
            shell: shell.clone(),
            terminal: String::new(),
        };
        let placed = self.place_of(&pane);
        if doc::target(&self.doc, &edit).is_none()
            || self.terminal_of.get(&pane) != Some(&shell)
            || placed.as_ref().map(|(_, at)| at) != Some(&tab)
        {
            self.refuse(&ui, "pane_changed", "pane_changed");
            return Ok(());
        }
        let Some((space, tab)) = placed else {
            self.refuse(&ui, "pane_changed", "pane_changed");
            return Ok(());
        };
        let Some(record) = self.records.get(&shell) else {
            self.refuse(&ui, "missing", "terminal_lost");
            return Ok(());
        };
        if record["state"].as_str() != Some("live") || record["pid"].is_null() {
            self.refuse(&ui, "missing", "terminal_lost");
            return Ok(());
        }
        // An agent, or any program, already in the shell: never replaced.
        let foreground = &record["foreground"]["pid"];
        if self.claims.current.contains_key(&shell)
            || !foreground.is_null() && *foreground != record["pid"]
        {
            self.refuse(&ui, "pane_busy", "pane_busy");
            return Ok(());
        }
        let mut run = json!({
            "argv": [kind],
            "keep": true,
            "env": {"TERM": "xterm-256color", "COLORTERM": "truecolor", "TERM_PROGRAM": "herdr"},
            "labels": {"herdr.space": space, "herdr.tab": tab, "herdr.seen": "0"},
        });
        if kind == "herdline-omp" {
            let spec = &params["managed_omp"];
            let path = |key: &str| spec[key].as_str().filter(|path| path.starts_with('/') && !path.contains('\0'));
            let (Some(exe), Some(entry), Some(ingress)) = (path("exe"), path("entry"), path("ingress")) else {
                self.refuse(&ui, "invalid", "invalid_params");
                return Ok(());
            };
            // An absolute executable bypasses core templates: their argv tail is a prompt.
            run["argv"] = json!([exe, "-e", entry, "-e", ingress]);
            if let Some(runtime) = path("runtime") {
                run["env"]["XDG_RUNTIME_DIR"] = json!(runtime);
            }
        }
        if !name.is_empty() {
            run["name"] = json!(name);
        }
        if let Some(cwd) = self.cwd_of(&shell) {
            run["cwd"] = json!(cwd);
        }
        let flow = Flow {
            ui,
            edit,
            created: None,
            target: None,
            retried: false,
            refusal: None,
            focus: false,
        };
        self.call("terminal.run", run, Pending::Run(flow))
    }

    /// The created terminal's answer.
    pub(super) fn ran(
        &mut self,
        mut flow: Flow,
        reply: std::result::Result<Value, (String, String)>,
    ) -> Result<()> {
        let result = match reply {
            Ok(result) => result,
            Err((kind, message)) => {
                self.refuse(&flow.ui, &kind, &message);
                return Ok(());
            }
        };
        let Some(address) = result["address"].as_str().filter(|address| !address.is_empty())
        else {
            self.refuse(&flow.ui, "error", "no_terminal");
            return Ok(());
        };
        let address = address.to_owned();
        match &mut flow.edit {
            Edit::CreateSpace {
                terminal, folder, ..
            } => {
                *terminal = address.clone();
                if folder.is_empty() {
                    *folder = result["cwd"].as_str().unwrap_or_default().to_owned();
                }
            }
            Edit::CreateTab { terminal, .. }
            | Edit::Split { terminal, .. }
            | Edit::Launch { terminal, .. } => {
                *terminal = address.clone();
            }
            _ => {}
        }
        self.records.insert(address.clone(), result);
        flow.created = Some(address);
        self.commit(flow)
    }

    /// Apply the edit to the document this pump holds and write it.
    pub(super) fn commit(&mut self, mut flow: Flow) -> Result<()> {
        let Some(target) = doc::target(&self.doc, &flow.edit) else {
            return self.fail(flow, "missing");
        };
        if flow.target.is_none() {
            flow.target = Some(target);
        }
        let mut value = self.doc.clone();
        match doc::apply(&mut value, &flow.edit) {
            Ok(removed) => {
                let params = json!({"name": doc::NAME, "value": value, "version": self.version});
                self.call(
                    "doc.set",
                    params,
                    Pending::Set {
                        flow,
                        value,
                        removed,
                    },
                )
            }
            Err(Refusal::Missing) => self.fail(flow, "missing"),
            Err(Refusal::Malformed) => self.fail(flow, "malformed"),
        }
    }

    /// The edit does not apply locally. With no terminal made, nothing is
    /// left over; with one, a fresh document decides its cleanup.
    fn fail(&mut self, mut flow: Flow, kind: &str) -> Result<()> {
        if flow.created.is_none() {
            self.refuse(&flow.ui, kind, kind);
            return Ok(());
        }
        flow.refusal = Some((kind.to_owned(), kind.to_owned()));
        self.fetch_doc(Some(flow))
    }

    /// `doc.set` answered.
    pub(super) fn set(
        &mut self,
        mut flow: Flow,
        value: Value,
        removed: Vec<String>,
        reply: std::result::Result<Value, (String, String)>,
    ) -> Result<()> {
        match reply {
            Ok(result) => {
                self.doc = value;
                self.version = result["version"].as_u64().unwrap_or(self.version + 1);
                self.document_changed();
                self.answer(&flow.ui, created_reply(&flow.edit, flow.focus));
                for terminal in removed {
                    if !self.pane_of.contains_key(&terminal) {
                        self.kill(&terminal)?;
                    }
                }
                if flow.focus {
                    self.focus_created(&flow.edit);
                }
                Ok(())
            }
            Err(refusal) => {
                flow.refusal = Some(refusal);
                self.fetch_doc(Some(flow))
            }
        }
    }

    /// A refused edit, with the document fetched fresh after the refusal.
    pub(super) fn continue_flow(&mut self, mut flow: Flow) -> Result<()> {
        let Some((kind, message)) = flow.refusal.take() else {
            return Ok(());
        };
        if kind == "conflict"
            && !flow.retried
            && doc::target(&self.doc, &flow.edit) == flow.target
        {
            flow.retried = true;
            return self.commit(flow);
        }
        self.refuse(&flow.ui, &kind, &message);
        if let Some(created) = &flow.created
            && !self.pane_of.contains_key(created)
        {
            self.kill(created)?;
        }
        Ok(())
    }

    fn focus_created(&mut self, edit: &Edit) {
        match edit {
            Edit::CreateSpace {
                space, tab, pane, ..
            }
            | Edit::CreateTab {
                space, tab, pane, ..
            } => self.focus_pane(space.clone(), tab.clone(), pane.clone()),
            Edit::Split { new_pane, .. } => {
                if let Some((space, tab)) = self.place_of(new_pane) {
                    self.focus_pane(space, tab, new_pane.clone());
                }
            }
            _ => {}
        }
    }


    /// The pane input goes to: the one named, else the focused one.
    fn target_pane(&self, pane: Option<String>) -> Option<String> {
        pane.or_else(|| {
            let tab = self.tab()?;
            self.focus
                .panes
                .get(doc::id(tab))
                .cloned()
                .or_else(|| self.placed().first().map(|placed| placed.pane.clone()))
        })
    }

    /// Bytes to the program in `pane`, which this phone must hold.
    fn type_into(&mut self, pane: &str, events: &[ClientPaneInputEvent]) -> Result<bool> {
        let Some(terminal) = self.interactive.get(pane).cloned() else {
            return Ok(false);
        };
        let Some(term) = self.terms.get_mut(&terminal) else {
            return Ok(false);
        };
        let Some(channel) = term.channel.filter(|_| !term.observe) else {
            return Ok(false);
        };
        let modes = term.tracker.modes();
        let mut bytes = Vec::new();
        for event in events {
            keys::encode(event, modes, &mut bytes);
        }
        let typed = events
            .iter()
            .any(|event| !matches!(event, ClientPaneInputEvent::Mouse { .. }));
        if typed && term.offset > 0 {
            term.offset = 0;
            term.vt.scroll_viewport(ScrollViewport::Bottom);
            self.dirty_frame = true;
        }
        if !bytes.is_empty() {
            self.link.frame(channel, Kind::Data, &bytes)?;
        }
        Ok(true)
    }

    /// Composer text (or a steer's Enter) as DATA on this phone's own
    /// interactive stream of `pane`, answered under `receipt` once the core
    /// answers a call sent after it. That proves only that the core took
    /// and queued the bytes, never that the program read them: Insert stays
    /// Handed, and a steer's Enter waits on its own screen check. When the
    /// pane is no longer held interactively nothing is written: the hold,
    /// and a steer staged on this paste, end with a refusal.
    pub(super) fn typed(
        &mut self,
        receipt: String,
        target: &crate::hold::Target,
        pane: &str,
        events: &[ClientPaneInputEvent],
    ) -> Result<()> {
        if !self.type_into(pane, events)? {
            let link = self.shared.link;
            let mut holds = crate::hold::holds();
            let mut steers = crate::steer::steers();
            if steers
                .get(target)
                .is_some_and(|staged| staged.link == link && staged.receipt == receipt)
            {
                steers.end(target);
            }
            holds.answered(link, &receipt);
            drop(steers);
            drop(holds);
            self.refuse(&receipt, "takes_size", "takes_size");
            return Ok(());
        }
        self.call("server.status", json!({}), Pending::Typed { receipt })
    }

    pub(super) fn input(
        &mut self,
        pane: Option<String>,
        events: &[ClientPaneInputEvent],
    ) -> Result<()> {
        let Some(pane) = self.target_pane(pane) else {
            return Ok(());
        };
        if !self.type_into(&pane, events)? {
            self.emit(UiEvent::Rejected {
                request_id: None,
                reason: "takes_size".to_owned(),
            });
        }
        Ok(())
    }

    /// A tap or wheel: to the program when it tracks the mouse in a pane this
    /// phone holds, else a local scroll of the pane's viewport.
    pub(super) fn touch(
        &mut self,
        pane: &str,
        kind: Touch,
        column: u16,
        row: u16,
        lines: u16,
    ) -> Result<()> {
        let Some(terminal) = self.terminal_of.get(pane).cloned() else {
            return Ok(());
        };
        let Some(term) = self.terms.get(&terminal) else {
            return Ok(());
        };
        let mouse = !term.observe && term.tracker.modes().mouse();
        let offset = term.offset;
        if mouse && self.interactive.get(pane) == Some(&terminal) {
            let events = input::touch(kind, column, row, lines);
            self.type_into(pane, &events)?;
            return Ok(());
        }
        let lines = usize::from(lines.max(1));
        match kind {
            Touch::Tap => {}
            Touch::ScrollUp => self.scroll_to(&terminal, offset.saturating_add(lines)),
            Touch::ScrollDown => self.scroll_to(&terminal, offset.saturating_sub(lines)),
        }
        Ok(())
    }

    /// Show `terminal` `offset` rows back from its live bottom.
    fn scroll_to(&mut self, terminal: &str, offset: usize) {
        let Some(term) = self.terms.get_mut(terminal) else {
            return;
        };
        let offset = offset.min(term.vt.scrollback_rows().unwrap_or(0));
        if offset == term.offset {
            return;
        }
        term.offset = offset;
        term.vt.scroll_viewport(ScrollViewport::Bottom);
        if offset > 0 {
            term.vt
                .scroll_viewport(ScrollViewport::Delta(-isize::try_from(offset).unwrap_or(isize::MAX)));
        }
        self.dirty_frame = true;
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
