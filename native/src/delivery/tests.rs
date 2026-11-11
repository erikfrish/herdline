use super::*;
use herdr_client::Error as ClientError;
use std::cell::Cell;

/// Which client call fails, and how.
#[derive(Clone, Copy)]
enum Fail {
    Nothing,
    Input,
    ReceiptFull,
    ReceiptDisconnected,
}

/// A client queue that counts what it accepted.
struct FakeQueue {
    fail: Cell<Fail>,
    inputs: Cell<usize>,
    requests: Cell<usize>,
}

impl FakeQueue {
    fn new(fail: Fail) -> Self {
        Self {
            fail: Cell::new(fail),
            inputs: Cell::new(0),
            requests: Cell::new(0),
        }
    }
}

impl Queue for FakeQueue {
    fn send_input(
        &self,
        _: &str,
        _: &str,
        _: Vec<ClientPaneInputEvent>,
    ) -> herdr_client::Result<()> {
        if matches!(self.fail.get(), Fail::Input) {
            return Err(ClientError::Full);
        }
        self.inputs.set(self.inputs.get() + 1);
        Ok(())
    }

    fn request(&self, _: &str, _: Method, _: Value) -> herdr_client::Result<String> {
        match self.fail.get() {
            Fail::ReceiptFull => Err(ClientError::Full),
            Fail::ReceiptDisconnected => Err(ClientError::Disconnected),
            Fail::Nothing | Fail::Input => {
                self.requests.set(self.requests.get() + 1);
                Ok(format!("gpui-{}", self.requests.get()))
            }
        }
    }
}

const LINK: u64 = 1;
const RECEIPT: Method = Method::IntegrationList;

fn target() -> Target {
    Target {
        node: "n1".into(),
        session: "lab".into(),
        pane: "w1:p1".into(),
    }
}

fn request() -> Request {
    serde_json::from_value(json!({
        "node": "n1", "session": "lab", "boot": "boot-1", "pane": "w1:p1",
        "agent": "omp", "text": "hello", "submit": true,
    }))
    .unwrap()
}

fn steer_request() -> SteerRequest {
    SteerRequest {
        node: "n1".into(),
        session: "lab".into(),
        boot: "boot-1".into(),
        pane: "w1:p1".into(),
        agent: Some("omp".into()),
        instance: Some(instance()),
        text: "also this".into(),
        viewport: None,
        attachments: Vec::new(),
    }
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

fn staged() -> Staged {
    Staged {
        boot: "boot-1".into(),
        link: LINK,
        receipt: "gpui-7".into(),
        agent: Some("omp".into()),
        instance: instance(),
        text: "also this".into(),
    }
}

fn outcome(answer: &str) -> (String, Option<String>) {
    let answer: Value = serde_json::from_str(answer).unwrap();
    let field = |name: &str| answer.get(name).and_then(Value::as_str).map(str::to_owned);
    (field("outcome").unwrap(), field("reason"))
}

fn deliver_with(queue: &FakeQueue, holds: &mut Holds) -> Result<String> {
    deliver(
        queue,
        holds,
        &mut Steers::default(),
        LINK,
        RECEIPT,
        request(),
        |_| Ok(()),
    )
}

#[test]
fn input_that_was_not_queued_is_an_error_and_holds_nothing() {
    let queue = FakeQueue::new(Fail::Input);
    let mut holds = Holds::default();
    assert!(deliver_with(&queue, &mut holds).is_err());
    assert!(!holds.is_held(&target()));

    let mut steers = Steers::default();
    let staged_steer = stage_steer(
        &queue,
        &mut holds,
        &mut steers,
        LINK,
        RECEIPT,
        steer_request(),
        instance(),
    );
    assert!(staged_steer.is_err());
    assert!(!holds.is_held(&target()));
    assert!(!steers.takes(&target(), LINK));

    assert!(press_steer_enter(&queue, &mut holds, target(), LINK, staged(), RECEIPT).is_err());
    assert!(!holds.is_held(&target()));
    assert_eq!(queue.inputs.get(), 0);
}

#[test]
fn a_refused_omp_delivery_queues_nothing_and_holds_nothing() {
    use crate::composer::tests::{
        ASK_OVER_EMPTY, EMPTY, MULTILINE, acked, frame, registry, scroll, whole,
    };
    use crate::composer::{Surface, admit};
    use herdr_client::protocol::AgentStatus;

    let ask = frame("w1:p1", ASK_OVER_EMPTY);
    let empty = frame("w1:p1", EMPTY);
    let draft = frame("w1:p1", MULTILINE);
    let live = scroll(false, 40);
    let back = scroll(true, 40);
    let refused_on = [
        (Some(&ask), Some(&live), "editor_dialog_open"),
        (Some(&empty), Some(&back), "scrolled"),
        (Some(&empty), None, "not_visible"),
        (None, Some(&live), "not_visible"),
    ];
    for status in [
        AgentStatus::Idle,
        AgentStatus::Working,
        AgentStatus::Blocked,
    ] {
        let omp = registry(Some("omp"), Some(status));
        for (shown, metrics, reason) in refused_on {
            let surface = Surface {
                frame: shown,
                scroll: metrics,
                ..Surface::default()
            };
            for submit in [false, true] {
                let queue = FakeQueue::new(Fail::Nothing);
                let mut holds = Holds::default();
                let mut wanted = request();
                wanted.submit = submit;
                let answer = deliver(
                    &queue,
                    &mut holds,
                    &mut Steers::default(),
                    LINK,
                    RECEIPT,
                    wanted,
                    |request| admit(&omp, &surface, request),
                )
                .unwrap();
                assert_eq!(
                    outcome(&answer),
                    ("refused".to_owned(), Some(reason.to_owned())),
                    "{status:?} submit={submit}"
                );
                assert_eq!((queue.inputs.get(), queue.requests.get()), (0, 0));
                assert!(!holds.is_held(&target()));
            }
        }
    }
    // A draft read live with no dialog still takes an append.
    let grid = acked(&draft);
    let surface = Surface {
        frame: Some(&draft),
        scroll: Some(&live),
        grid: Some(&grid),
        ..Surface::default()
    };
    let working = registry(Some("omp"), Some(AgentStatus::Working));
    let queue = FakeQueue::new(Fail::Nothing);
    let mut holds = Holds::default();
    let mut append = request();
    append.submit = false;
    append.viewport = Some(whole(&draft));
    let answer = deliver(
        &queue,
        &mut holds,
        &mut Steers::default(),
        LINK,
        RECEIPT,
        append,
        |request| admit(&working, &surface, request),
    )
    .unwrap();
    assert_eq!(outcome(&answer).0, "queued");
    assert_eq!(queue.inputs.get(), 1);
}

#[test]
fn a_queued_delivery_without_its_receipt_is_uncertain_and_held_until_unlocked() {
    for fail in [Fail::ReceiptFull, Fail::ReceiptDisconnected] {
        let queue = FakeQueue::new(fail);
        let mut holds = Holds::default();
        let first = deliver_with(&queue, &mut holds).unwrap();
        assert_eq!(
            outcome(&first),
            ("uncertain".into(), Some("unconfirmed".into()))
        );
        assert!(holds.is_held(&target()));

        // Send again, from any surface: refused before anything is queued.
        queue.fail.set(Fail::Nothing);
        let again = deliver_with(&queue, &mut holds).unwrap();
        assert_eq!(
            outcome(&again),
            ("refused".into(), Some("unsettled".into()))
        );
        assert_eq!(queue.inputs.get(), 1);

        // Only the user's Unlock frees the pane; the next Send goes once.
        holds.unlock(&target());
        let after = deliver_with(&queue, &mut holds).unwrap();
        assert_eq!(outcome(&after).0, "queued");
        assert_eq!(queue.inputs.get(), 2);
    }
}

#[test]
fn a_steer_paste_without_its_receipt_is_uncertain_and_never_gets_an_enter() {
    for fail in [Fail::ReceiptFull, Fail::ReceiptDisconnected] {
        let queue = FakeQueue::new(fail);
        let mut holds = Holds::default();
        let mut steers = Steers::default();
        let answer = stage_steer(
            &queue,
            &mut holds,
            &mut steers,
            LINK,
            RECEIPT,
            steer_request(),
            instance(),
        )
        .unwrap();
        assert_eq!(
            outcome(&answer),
            ("uncertain".into(), Some("unconfirmed".into()))
        );
        assert!(holds.is_held(&target()));
        assert!(
            !steers.takes(&target(), LINK),
            "nothing is staged for an Enter"
        );
    }
}

#[test]
fn a_steer_enter_without_its_receipt_is_uncertain_and_held() {
    for fail in [Fail::ReceiptFull, Fail::ReceiptDisconnected] {
        let queue = FakeQueue::new(fail);
        let mut holds = Holds::default();
        let answer =
            press_steer_enter(&queue, &mut holds, target(), LINK, staged(), RECEIPT).unwrap();
        assert_eq!(
            outcome(&answer),
            ("uncertain".into(), Some("unconfirmed".into()))
        );
        assert!(holds.is_held(&target()));
        queue.fail.set(Fail::Nothing);
        let again = deliver_with(&queue, &mut holds).unwrap();
        assert_eq!(
            outcome(&again),
            ("refused".into(), Some("unsettled".into()))
        );
    }
}

#[test]
fn a_confirmed_steer_step_is_released_by_its_own_receipt() {
    let queue = FakeQueue::new(Fail::Nothing);
    let mut holds = Holds::default();
    let mut steers = Steers::default();
    let answer = stage_steer(
        &queue,
        &mut holds,
        &mut steers,
        LINK,
        RECEIPT,
        steer_request(),
        instance(),
    )
    .unwrap();
    assert_eq!(outcome(&answer).0, "staged");
    assert!(steers.takes(&target(), LINK));
    holds.answered(LINK, "gpui-1");
    assert!(!holds.is_held(&target()));

    let staged = steers.end(&target()).unwrap();
    let enter = press_steer_enter(&queue, &mut holds, target(), LINK, staged, RECEIPT).unwrap();
    assert_eq!(outcome(&enter).0, "queued");
    holds.answered(LINK, "gpui-2");
    assert!(!holds.is_held(&target()));
}
