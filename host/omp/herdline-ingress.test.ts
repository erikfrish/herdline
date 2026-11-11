import { afterEach, describe, expect, test } from "bun:test";
import * as fs from "node:fs";
import * as net from "node:net";
import * as os from "node:os";
import * as path from "node:path";
import type { ExtensionAPI, ExtensionContext } from "@oh-my-pi/pi-coding-agent";
import { CUSTOM_TYPE, DIR_NAME, type Ingress, install } from "./herdline-ingress";

const PID = 4242;
const ID = "0f8e7c1a-2b3d-4e5f-8a9b-0c1d2e3f4a5b";
const OTHER = "1a2b3c4d-5e6f-4a8b-9c0d-1e2f3a4b5c6d";

class PromptDroppedError extends Error {
	override name = "PromptDroppedError";
}

type Entry = { type: string; customType?: string; data?: unknown };
type QueueEntry = { text: string; requestId?: string };
type Sent = { content: unknown; options: { deliverAs?: string; requestId?: string } };

/** An OMP session as the extension sees it through the public API, recording what it is asked. */
function fakeOmp(options: { hooks?: boolean; entries?: Entry[] } = {}) {
	const handlers = new Map<string, ((event: unknown, ctx: unknown) => unknown)[]>();
	const bus = new Map<string, Set<(data: unknown) => void>>();
	const events = {
		on(channel: string, listener: (data: unknown) => void) {
			const listeners = bus.get(channel) ?? new Set<(data: unknown) => void>();
			bus.set(channel, listeners);
			listeners.add(listener);
			return () => { listeners.delete(listener); };
		},
		emit(channel: string, data: unknown) { for (const listener of bus.get(channel) ?? []) listener(data); },
	};
	const log: string[] = [];
	const entries: Entry[] = [...(options.entries ?? [])];
	const sent: Sent[] = [];
	const queue = { steering: [] as QueueEntry[], followUp: [] as QueueEntry[], liveSteered: 0 };
	const omp = {
		idle: false,
		dialogOpen: false,
		collab: false as boolean | "throw" | "missing",
		dialogs: [] as unknown[],
		submits: [] as { dialogId: string; answer: unknown }[],
		// Runs inside each flush, after it logged: a test can fail it or change the world meanwhile.
		onFlush: (): void => {},
		// What sendUserMessage does with a call; by default OMP queues it and reports the queue.
		onSend: (call: Sent): Promise<void> => {
			const entry = { text: String(call.content), requestId: call.options.requestId };
			if (call.options.deliverAs === "followUp") queue.followUp.push(entry);
			else queue.steering.push(entry);
			fire("queue_update", { ...queue });
			return Promise.resolve();
		},
	};
	const fire = (name: string, event: unknown) => {
		for (const handler of handlers.get(name) ?? []) void handler(event, ctx);
	};
	const pi = {
		events,
		on: (name: string, handler: (event: unknown, ctx: unknown) => unknown) => {
			handlers.set(name, [...(handlers.get(name) ?? []), handler]);
		},
		appendEntry: (customType: string, data: unknown) => {
			log.push(`append:${(data as { outcome: string }).outcome}`);
			entries.push({ type: "custom", customType, data });
		},
		sendUserMessage: (content: unknown, options: Sent["options"]) => {
			log.push("send");
			const call = { content, options };
			sent.push(call);
			return omp.onSend(call);
		},
		logger: { warn: () => {}, debug: () => {} },
	};
	const ui: { [key: string]: unknown } = { hasOpenDialog: () => omp.dialogOpen };
	const sessionManager: { [key: string]: unknown } = {
		getSessionId: () => "s1",
		getSessionFile: () => "/home/u/.omp/sessions/s1.jsonl",
		getEntries: () => entries,
		ensureOnDisk: async () => {
			log.push("disk");
		},
	};
	const ctx: { [key: string]: unknown } = {
		sessionManager,
		isIdle: () => omp.idle,
		ui,
	};
	if (options.hooks !== false) {
		ctx.getQueuedMessages = () => ({ ...queue });
		ctx.withdrawQueuedMessage = (requestId: string) => {
			const before = queue.steering.length;
			queue.steering = queue.steering.filter(entry => entry.requestId !== requestId);
			return queue.steering.length < before;
		};
		ui.getOpenDialogs = () => omp.dialogs;
		ui.submitDialog = (dialogId: string, answer: unknown) => {
			omp.submits.push({ dialogId, answer });
			return dialogId === "d1" ? "submitted" : "not_open";
		};
		sessionManager.flush = async () => {
			log.push("flush");
			omp.onFlush();
		};
	}
	Object.defineProperty(ctx, "isCollabSession", {
		get: () => (omp.collab === "missing" || options.hooks === false ? undefined : () => {
			if (omp.collab === "throw") throw new Error("CollabStateUnknown");
			return omp.collab;
		}),
		enumerable: true,
		configurable: true,
	});
	return {
		omp,
		pi: pi as unknown as ExtensionAPI,
		ctx: ctx as unknown as ExtensionContext,
		log,
		entries,
		sent,
		queue,
		fire,
	};
}

const cleanup: (() => Promise<void>)[] = [];
afterEach(async () => {
	for (const done of cleanup.splice(0)) await done();
});

async function setup(options: { hooks?: boolean; entries?: Entry[] } = {}) {
	const root = fs.mkdtempSync(path.join(os.tmpdir(), "hi-"));
	const runtimeDir = path.join(root, "run");
	const stagingRoot = path.join(root, "staging");
	fs.mkdirSync(runtimeDir, { mode: 0o700 });
	fs.mkdirSync(stagingRoot, { recursive: true });
	const fake = fakeOmp(options);
	const ingress = install(fake.pi, { runtimeDir, stagingRoot, pid: PID, observeMs: 50, admitMs: 200 });
	await ingress.publish(fake.ctx);
	cleanup.push(async () => {
		await ingress.unpublish();
		fs.rmSync(root, { recursive: true, force: true });
	});
	const sidecar = ingress.sidecar ? JSON.parse(fs.readFileSync(ingress.sidecar, "utf8")) : undefined;
	const instance = sidecar && {
		sessionId: sidecar.sessionId,
		sessionFile: sidecar.sessionFile,
		pid: sidecar.pid,
		nonce: sidecar.nonce,
	};
	const send = (fields: { [key: string]: unknown } = {}) =>
		ingress.handle({ v: 1, id: ID, instance, text: "hello", deliverAs: "auto", ...fields }) as Promise<{
			[key: string]: unknown;
		}>;
	return { ...fake, ingress, root, runtimeDir, stagingRoot, sidecar, instance, send };
}

function recorded(entries: Entry[]) {
	return entries.filter(entry => entry.customType === CUSTOM_TYPE).map(entry => entry.data as { [key: string]: unknown });
}

describe("the refuse order", () => {
	test("a changed instance is refused before anything, even a known id", async () => {
		const s = await setup();
		expect((await s.send()).outcome).toBe("accepted");
		const reply = await s.send({ instance: { ...s.instance, nonce: "other" } });
		expect(reply).toMatchObject({ outcome: "refused", reason: "instance_changed" });
		expect(s.sent).toHaveLength(1);
	});

	test("an open dialog refuses before collab, and nothing is recorded or sent", async () => {
		const s = await setup();
		s.omp.dialogOpen = true;
		s.omp.collab = true;
		expect(await s.send()).toMatchObject({ outcome: "refused", reason: "dialog_open" });
		s.omp.dialogOpen = false;
		expect(await s.send()).toMatchObject({ outcome: "refused", reason: "collab" });
		expect(s.sent).toHaveLength(0);
		expect(recorded(s.entries)).toHaveLength(0);
	});

	test("a collab state OMP cannot tell refuses each request, never the whole endpoint", async () => {
		const s = await setup();
		s.omp.collab = "throw";
		expect(await s.send()).toMatchObject({ outcome: "refused", reason: "collab_state_unknown" });
		s.omp.collab = "missing";
		expect(await s.send()).toMatchObject({ outcome: "refused", reason: "collab_state_unknown" });
		expect(s.sidecar.unsupported).toBeUndefined();
		s.omp.collab = false;
		expect((await s.send()).outcome).toBe("accepted");
	});

	test("files that are not staged images, or nothing at all, are refused", async () => {
		const s = await setup();
		const outside = path.join(s.root, "outside.png");
		fs.writeFileSync(outside, "png");
		const link = path.join(s.stagingRoot, "link.png");
		fs.symlinkSync(outside, link);
		const pdf = path.join(s.stagingRoot, "a.pdf");
		fs.writeFileSync(pdf, "pdf");
		expect(await s.send({ attachments: [{ path: link, mimeType: "image/png" }] })).toMatchObject({
			outcome: "refused",
			reason: "unsupported_type",
		});
		expect(await s.send({ attachments: [{ path: pdf, mimeType: "application/pdf" }] })).toMatchObject({
			reason: "unsupported_type",
		});
		expect(await s.send({ text: "  " })).toMatchObject({ outcome: "refused", reason: "empty" });
		expect(s.sent).toHaveLength(0);
	});
});

describe("a send", () => {
	test("is recorded on disk before the call and confirmed only once OMP's queue shows it", async () => {
		const s = await setup();
		const reply = await s.send();
		expect(reply.outcome).toBe("accepted");
		expect(s.log).toEqual(["append:pending", "disk", "flush", "send", "append:accepted", "disk", "flush"]);
		// A working agent takes auto as a steer, under the request's id.
		expect(s.sent[0].options).toEqual({ deliverAs: "steer", requestId: ID });
		expect(reply.state).toMatchObject({ idle: false, queued: [{ requestId: ID, text: "hello", queue: "steering" }] });
	});

	test("a call that resolves without the queue ever showing it is uncertain", async () => {
		const s = await setup();
		s.omp.onSend = () => Promise.resolve();
		expect((await s.send()).outcome).toBe("uncertain");
		expect(recorded(s.entries).map(r => r.outcome)).toEqual(["pending"]);
		expect((await s.ingress.handle({ v: 1, id: ID, op: "status" }) as { outcome: string }).outcome).toBe(
			"uncertain",
		);
	});

	test("an accepted send whose record cannot be written stays uncertain, also when asked again", async () => {
		const s = await setup();
		s.omp.onFlush = () => {
			if (s.log.includes("send")) throw new Error("disk full");
		};
		expect((await s.send()).outcome).toBe("uncertain");
		const status = (await s.ingress.handle({ v: 1, id: ID, op: "status" })) as { outcome: string };
		expect(status.outcome).toBe("uncertain");
		expect((await s.send()).outcome).toBe("uncertain");
		expect(s.sent).toHaveLength(1);
	});

	test("a dialog opening while the pending record is written refuses before the call", async () => {
		const s = await setup();
		s.omp.onFlush = () => {
			s.omp.dialogOpen = true;
		};
		expect(await s.send()).toMatchObject({ outcome: "refused", reason: "dialog_open" });
		expect(s.sent).toHaveLength(0);
	});

	test("a duplicate arriving while the first is in flight is never sent a second time", async () => {
		const s = await setup();
		const [first, second] = await Promise.all([s.send(), s.send()]);
		expect([first.outcome, second.outcome].sort()).toEqual(["accepted", "uncertain"]);
		expect((await s.send()).outcome).toBe("replayed");
		expect(s.sent).toHaveLength(1);
	});

	test("to an idle agent starts the turn and is admitted at its message_end, not at the turn's end", async () => {
		const s = await setup();
		s.omp.idle = true;
		s.omp.onSend = call => {
			queueMicrotask(() => s.fire("message_end", { message: { role: "user", requestId: call.options.requestId } }));
			return new Promise(() => {});
		};
		expect((await s.send()).outcome).toBe("admitted");
		expect(s.sent[0].options).toEqual({ requestId: ID });
	});

	test("dropped by OMP is refused, and stays refused when asked again", async () => {
		const s = await setup();
		s.omp.onSend = () => Promise.reject(new PromptDroppedError());
		expect(await s.send()).toMatchObject({ outcome: "refused", reason: "dropped" });
		expect(await s.send()).toMatchObject({ outcome: "refused", reason: "dropped" });
		expect(s.sent).toHaveLength(1);
	});

	test("any other failure of the call is uncertain", async () => {
		const s = await setup();
		s.omp.onSend = () => Promise.reject(new Error("boom"));
		expect((await s.send()).outcome).toBe("uncertain");
	});

	test("a photo with no text goes as images alone", async () => {
		const s = await setup();
		const photo = path.join(s.stagingRoot, "a.jpg");
		fs.writeFileSync(photo, "jpeg-bytes");
		await s.send({ text: "", attachments: [{ path: photo, mimeType: "image/jpeg" }] });
		expect(s.sent[0].content).toEqual([
			{ type: "image", data: Buffer.from("jpeg-bytes").toString("base64"), mimeType: "image/jpeg" },
		]);
	});
});

describe("a repeated id", () => {
	test("is answered from its record and never sent again", async () => {
		const s = await setup();
		await s.send();
		expect((await s.send()).outcome).toBe("replayed");
		// The same id with other content is not the same request.
		expect((await s.send({ text: "other" })).outcome).toBe("uncertain");
		expect(s.sent).toHaveLength(1);
	});

	test("after a restart, answers what the session file holds", async () => {
		const first = await setup();
		await first.send();
		const records = first.entries.filter(entry => entry.customType === CUSTOM_TYPE);
		const pending = { ...(records[0].data as object), id: OTHER };
		const s = await setup({
			entries: [...records, { type: "custom", customType: CUSTOM_TYPE, data: pending }],
		});
		expect((await s.send()).outcome).toBe("replayed");
		expect((await s.send({ id: OTHER })).outcome).toBe("uncertain");
		expect(s.sent).toHaveLength(0);
	});

	test("a status check answers durable outcomes and never sends", async () => {
		const s = await setup();
		await s.send();
		expect(await s.ingress.handle({ v: 1, id: ID, op: "status" })).toMatchObject({ id: ID, outcome: "accepted" });
		expect(await s.ingress.handle({ v: 1, id: OTHER, op: "status" })).toMatchObject({
			id: OTHER,
			outcome: "uncertain",
		});
		expect(s.sent).toHaveLength(1);
		expect(recorded(s.entries)).toHaveLength(2);
	});
});

test("withdraw takes the entry out of OMP's queue and says whether it was there", async () => {
	const s = await setup();
	await s.send();
	expect(await s.ingress.handle({ v: 1, id: ID, op: "withdraw" })).toMatchObject({ withdrawn: true });
	expect(await s.ingress.handle({ v: 1, id: ID, op: "withdraw" })).toMatchObject({ withdrawn: false });
});

describe("dialogs", () => {
	test("the snapshot passes through and a submit answers only the named dialog", async () => {
		const s = await setup();
		s.omp.dialogs = [{ dialogId: "d1", kind: "form", source: "ask", form: { fields: [] }, createdAt: 1 }];
		expect(await s.ingress.handle({ v: 1, id: ID, op: "dialogs" })).toEqual({ id: ID, dialogs: s.omp.dialogs });
		const answer = { data: { name: "x" } };
		expect(await s.ingress.handle({ v: 1, id: ID, op: "dialog_submit", dialogId: "d1", answer })).toEqual({
			id: ID,
			dialogId: "d1",
			result: "submitted",
		});
		expect(await s.ingress.handle({ v: 1, id: OTHER, op: "dialog_submit", dialogId: "d2", answer })).toMatchObject({
			result: "not_open",
		});
		// No dialog named: nothing is answered at all.
		expect(await s.ingress.handle({ v: 1, id: OTHER, op: "dialog_submit", answer })).toBeUndefined();
		expect(s.omp.submits).toEqual([
			{ dialogId: "d1", answer },
			{ dialogId: "d2", answer },
		]);
		expect(s.sent).toHaveLength(0);
	});
});

describe("publishing", () => {
	test("a private socket and sidecar name the session", async () => {
		const s = await setup();
		const dir = path.join(s.runtimeDir, DIR_NAME);
		expect(fs.statSync(dir).mode & 0o777).toBe(0o700);
		expect(s.ingress.sidecar).toBe(path.join(dir, `s1-${PID}.json`));
		expect(fs.statSync(s.ingress.sidecar as string).mode & 0o777).toBe(0o600);
		expect(fs.statSync(s.ingress.socket as string).mode & 0o777).toBe(0o600);
		expect(s.sidecar).toMatchObject({ v: 1, sessionId: "s1", sessionFile: "/home/u/.omp/sessions/s1.jsonl", pid: PID });
	});

	test("an OMP without the hooks gets a sidecar saying so and no socket", async () => {
		const s = await setup({ hooks: false });
		expect(s.sidecar.unsupported).toEqual([
			"getQueuedMessages",
			"withdrawQueuedMessage",
			"getOpenDialogs",
			"submitDialog",
			"flush",
		]);
		expect(s.ingress.socket).toBeUndefined();
	});

	test("a shared endpoint directory publishes nothing", async () => {
		const root = fs.mkdtempSync(path.join(os.tmpdir(), "hi-"));
		cleanup.push(async () => fs.rmSync(root, { recursive: true, force: true }));
		fs.mkdirSync(path.join(root, DIR_NAME), { mode: 0o755 });
		fs.chmodSync(path.join(root, DIR_NAME), 0o755);
		const fake = fakeOmp();
		const ingress = install(fake.pi, { runtimeDir: root, stagingRoot: root, pid: PID });
		await ingress.publish(fake.ctx);
		expect(ingress.sidecar).toBeUndefined();
	});

	test("shutdown takes the sidecar and socket down", async () => {
		const s = await setup();
		const sidecar = s.ingress.sidecar as string;
		const socket = s.ingress.socket as string;
		await s.ingress.unpublish();
		expect(fs.existsSync(sidecar)).toBe(false);
		expect(fs.existsSync(socket)).toBe(false);
	});
});

test("over the socket: an answer line per request, and queue and dialog pushes", async () => {
	const s = await setup();
	const client = net.createConnection(s.ingress.socket as string);
	cleanup.unshift(async () => {
		client.destroy();
	});
	const lines: { [key: string]: unknown }[] = [];
	let buffer = "";
	const { promise: answered, resolve } = Promise.withResolvers<void>();
	const { promise: dialogPushed, resolve: dialogSeen } = Promise.withResolvers<void>();
	client.setEncoding("utf8");
	client.on("data", (chunk: string) => {
		buffer += chunk;
		for (let at = buffer.indexOf("\n"); at >= 0; at = buffer.indexOf("\n")) {
			const line = buffer.slice(0, at);
			buffer = buffer.slice(at + 1);
			lines.push(JSON.parse(line));
			if (line.includes(`"id":"${ID}"`)) resolve();
			if (line.startsWith(`{"dialogs":`)) dialogSeen();
		}
	});
	client.write(`${JSON.stringify({ v: 1, id: ID, instance: s.instance, text: "hi", deliverAs: "steer" })}\n`);
	await answered;
	s.omp.dialogs = [{ dialogId: "d1", kind: "approve", source: "tool", createdAt: 2 }];
	s.fire("ui_dialog_open", {});
	await dialogPushed;
	expect(lines).toContainEqual({ queue: [{ requestId: ID, text: "hi", queue: "steering" }], liveSteered: 0 });
	expect(lines.find(line => line.id === ID)).toMatchObject({ outcome: "accepted" });
	expect(lines).toContainEqual({ dialogs: s.omp.dialogs });
});

test("live children keep exact call identity and close only from lifecycle, then clear on session publication", async () => {
	const s = await setup();
	s.pi.events.emit("task:subagent:progress", { parentToolCallId: "call-1", progress: {
		id: "Parser", agent: "scout", status: "running", stage: "reading parser", requests: 3, recentOutput: ["latest", "earlier"],
	} });
	let reply = await s.send({ op: "status" });
	expect(reply.state).toMatchObject({ subagents: [{ id: "Parser", parentCallId: "call-1", agent: "Parser", type: "scout", status: "running", stage: "reading parser", output: "earlier\nlatest", requests: 3 }] });
	s.pi.events.emit("task:subagent:lifecycle", { id: "Parser", parentToolCallId: "call-1", agent: "scout", status: "failed" });
	s.pi.events.emit("task:subagent:progress", { parentToolCallId: "call-1", progress: { id: "Parser", status: "running" } });
	reply = await s.send({ op: "status" });
	expect(reply.state).toMatchObject({ subagents: [{ id: "Parser", status: "failed" }] });
	expect(s.sent).toHaveLength(0);
	await s.ingress.publish(s.ctx);
	reply = await s.send({ op: "status" });
	expect(reply.state).toMatchObject({ subagents: [] });
});
