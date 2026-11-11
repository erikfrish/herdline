/**
 * herdline-ingress: Herdline Chat's direct line into this OMP session.
 *
 * One per-session unix socket, `$XDG_RUNTIME_DIR/herdline-ingress/<sessionId>-<pid>.sock`,
 * named by a sidecar `<sessionId>-<pid>.json` that Herdline finds from the pane's
 * process (docs/decisions.md, "Chat Send through the OMP endpoint"). It speaks
 * newline-delimited JSON and uses only OMP's public extension API: requests
 * go in through `pi.sendUserMessage`, each with its id as `requestId`, and
 * every outcome Herdline may act on is first a durable `herdline-ingress`
 * session entry, so a repeated id is answered from that record and never sent
 * again.
 *
 * An OMP without the hooks this needs gets a sidecar that says which are
 * missing and no socket: Herdline then shows that, and never types into the
 * pane instead. A repository asset: installed into an OMP only on the user's
 * own decision.
 */
import { createHash, randomBytes } from "node:crypto";
import * as fs from "node:fs";
import * as net from "node:net";
import * as os from "node:os";
import * as path from "node:path";
import type { ExtensionAPI, ExtensionContext } from "@oh-my-pi/pi-coding-agent";
import { LiveSubagents, type ChildSnapshot } from "./live-subagents";

export const CUSTOM_TYPE = "herdline-ingress";
export const DIR_NAME = "herdline-ingress";
/** Herdline's own request lines stay under 256 KiB; this only bounds a broken client. */
export const LINE_MAX = 1024 * 1024;
/** Herdline's staging cap for one image (native/src/attach.rs IMAGE_LIMIT). */
export const IMAGE_LIMIT = 20 * 1024 * 1024;
const IMAGE_TYPES: Record<string, true> = { "image/png": true, "image/jpeg": true, "image/gif": true, "image/webp": true };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const TOKEN = /^[A-Za-z0-9_-]{1,128}$/;

type Outcome = "accepted" | "admitted" | "replayed" | "refused" | "uncertain";
type Reason =
	| "instance_changed"
	| "dialog_open"
	| "collab"
	| "collab_state_unknown"
	| "too_large"
	| "unsupported_type"
	| "empty"
	| "dropped";
type Stored = "pending" | "accepted" | "admitted" | "refused";

/** One durable record of a request, as appended under {@link CUSTOM_TYPE}. */
interface RequestRecord {
	id: string;
	digest: string;
	outcome: Stored;
	reason?: Reason;
	at: number;
}

interface Queued {
	requestId: string | null;
	text: string;
	queue: "steering" | "followUp";
}

interface State {
	idle: boolean;
	queued: Queued[];
	subagents: ChildSnapshot[];
}

type Content = Parameters<ExtensionAPI["sendUserMessage"]>[0];
type Deliver = "steer" | "followUp" | undefined;

export interface IngressOptions {
	/** The agent's `XDG_RUNTIME_DIR`; without one no endpoint is published. */
	runtimeDir: string | undefined;
	/** Herdline's staging root; images are read only from below it. */
	stagingRoot: string;
	pid: number;
	/** How long a resolved queued send may take to show in the queue. */
	observeMs?: number;
	/** How long an idle send may take to reach `message_end`: under Herdline's 20 s socket idle. */
	admitMs?: number;
}

/**
 * The public hooks the endpoint needs, missing from [ctx]'s OMP: the queue
 * and dialog-depth hooks of Slice O, the dialog snapshot and submit and the
 * session flush of O2. Collab is not among them: `ctx.isCollabSession()`
 * (O2) is asked per request, and a request is refused while it is missing
 * ({@link collabRefusal}).
 */
export function missingHooks(ctx: ExtensionContext): string[] {
	const missing: string[] = [];
	if (typeof ctx.getQueuedMessages !== "function") missing.push("getQueuedMessages");
	if (typeof ctx.withdrawQueuedMessage !== "function") missing.push("withdrawQueuedMessage");
	if (typeof ctx.ui?.hasOpenDialog !== "function") missing.push("hasOpenDialog");
	if (typeof ctx.ui?.getOpenDialogs !== "function") missing.push("getOpenDialogs");
	if (typeof ctx.ui?.submitDialog !== "function") missing.push("submitDialog");
	if (typeof ctx.sessionManager.flush !== "function") missing.push("flush");
	return missing;
}

/**
 * Why a send must not go into [ctx]'s session because of collab, or
 * undefined when OMP says it is not shared. Only a plain `false` lets it
 * go: a hook this OMP lacks, a throw (`CollabStateUnknown`) or any other
 * answer leaves the state unknown, and the request is refused.
 */
export function collabRefusal(ctx: ExtensionContext): Reason | undefined {
	if (!("isCollabSession" in ctx) || typeof ctx.isCollabSession !== "function") return "collab_state_unknown";
	let shared: unknown;
	try {
		shared = ctx.isCollabSession();
	} catch {
		return "collab_state_unknown";
	}
	if (shared === false) return undefined;
	return shared === true ? "collab" : "collab_state_unknown";
}

function digestOf(text: string, attachments: unknown): string {
	return createHash("sha256")
		.update(JSON.stringify({ text, attachments: attachments ?? [] }))
		.digest("hex");
}

function isDropped(error: unknown): boolean {
	return error instanceof Error && error.name === "PromptDroppedError";
}

/** The session this endpoint serves and how Herdline names it. */
interface Published {
	ctx: ExtensionContext;
	sessionId: string;
	sessionFile: string;
	nonce: string;
	sidecar: string;
	socket?: string;
	server?: net.Server;
}

export class Ingress {
	readonly #pi: ExtensionAPI;
	readonly #options: IngressOptions;
	#published: Published | undefined;
	readonly #records = new Map<string, RequestRecord>();
	readonly #clients = new Set<net.Socket>();
	// Request ids seen in OMP's queue or at their message_end, for the sends waiting on them.
	readonly #queuedSeen = new Set<string>();
	readonly #endedSeen = new Set<string>();
	readonly #waiters = new Set<() => void>();
	readonly #subagents = new LiveSubagents();
	#stopSubagents: (() => void)[] = [];

	constructor(pi: ExtensionAPI, options: IngressOptions) {
		this.#pi = pi;
		this.#options = options;
	}

	/** The sidecar's path while one is published. */
	get sidecar(): string | undefined {
		return this.#published?.sidecar;
	}

	/** The socket's path while one listens. */
	get socket(): string | undefined {
		return this.#published?.socket;
	}

	/**
	 * Publishes the endpoint for [ctx]'s session: the socket first, then the
	 * sidecar naming it, so a sidecar never names a socket not yet there.
	 * A session with no file has nowhere durable to record a request: it
	 * publishes nothing.
	 */
	async publish(ctx: ExtensionContext): Promise<void> {
		await this.unpublish();
		const runtime = this.#options.runtimeDir;
		const sessionId = ctx.sessionManager.getSessionId();
		const sessionFile = ctx.sessionManager.getSessionFile();
		if (!runtime || !path.isAbsolute(runtime) || !sessionFile || !TOKEN.test(sessionId)) return;
		const dir = path.join(runtime, DIR_NAME);
		if (!privateDir(dir)) {
			this.#pi.logger.warn("herdline-ingress: the endpoint directory is not private; nothing published", { dir });
			return;
		}
		this.#rebuild(ctx);
		const base = `${sessionId}-${this.#options.pid}`;
		const published: Published = {
			ctx,
			sessionId,
			sessionFile,
			nonce: randomBytes(24).toString("base64url"),
			sidecar: path.join(dir, `${base}.json`),
		};
		const missing = missingHooks(ctx);
		if (missing.length === 0) {
			const socket = path.join(dir, `${base}.sock`);
			fs.rmSync(socket, { force: true });
			published.server = await this.#listen(socket);
			published.socket = socket;
		}
		const sidecar = JSON.stringify({
			v: 1,
			sessionId,
			sessionFile,
			pid: this.#options.pid,
			nonce: published.nonce,
			...(missing.length > 0 ? { unsupported: missing } : {}),
		});
		const tmp = path.join(dir, `.${base}.${randomBytes(6).toString("hex")}.tmp`);
		fs.writeFileSync(tmp, sidecar, { mode: 0o600, flag: "wx" });
		fs.renameSync(tmp, published.sidecar);
		this.#published = published;
		// The public bus belongs to this session; switching publishes a fresh
		// subscription after the old one has been removed.
		if (this.#pi.events) {
			this.#stopSubagents = [
				this.#pi.events.on("task:subagent:progress", data => {
					if (this.#published === published) this.#subagents.progress(data);
				}),
				this.#pi.events.on("task:subagent:lifecycle", data => {
					if (this.#published === published) this.#subagents.lifecycle(data);
				}),
			];
		}
	}

	/** Takes the sidecar down first, so nothing finds a socket about to close. */
	async unpublish(): Promise<void> {
		const published = this.#published;
		this.#published = undefined;
		for (const stop of this.#stopSubagents) stop();
		this.#stopSubagents = [];
		this.#subagents.clear();
		if (!published) return;
		fs.rmSync(published.sidecar, { force: true });
		for (const client of this.#clients) client.destroy();
		this.#clients.clear();
		if (published.server) {
			const { promise, resolve } = Promise.withResolvers<void>();
			published.server.close(() => resolve());
			await promise;
		}
		if (published.socket) fs.rmSync(published.socket, { force: true });
	}

	/** Answers one request line's JSON; undefined when it carries no id to answer to. */
	async handle(request: unknown): Promise<object | undefined> {
		const published = this.#published;
		if (!published || typeof request !== "object" || request === null) return undefined;
		const line = request as { [key: string]: unknown };
		const id = line.id;
		if (typeof id !== "string" || !UUID.test(id)) return undefined;
		const ctx = published.ctx;
		if (line.v !== 1) return { id, outcome: "uncertain", state: this.#state(ctx) };
		if (line.op === "status") return this.#status(ctx, id);
		if (line.op === "withdraw") {
			return { id, withdrawn: ctx.withdrawQueuedMessage(id), state: this.#state(ctx) };
		}
		if (line.op === "dialogs") return { id, dialogs: ctx.ui.getOpenDialogs() };
		if (line.op === "dialog_submit") {
			// Only the dialog the user answered, by its exact id; OMP says whether it still was open.
			// An unusable request answers nothing: Herdline then knows it failed, never that it went.
			const dialogId = line.dialogId;
			const answer = line.answer;
			if (typeof dialogId !== "string" || dialogId === "" || typeof answer !== "object" || answer === null) {
				return undefined;
			}
			return { id, dialogId, result: await ctx.ui.submitDialog(dialogId, answer) };
		}
		if (line.op !== undefined) return { id, outcome: "uncertain", state: this.#state(ctx) };
		return this.#send(published, id, line);
	}

	/** OMP's queue changed: remembered for waiting sends and pushed to every client. */
	queueUpdated(snapshot: { steering: QueueEntry[]; followUp: QueueEntry[]; liveSteered: number }): void {
		for (const entry of [...snapshot.steering, ...snapshot.followUp]) {
			if (entry.requestId) this.#seen(this.#queuedSeen, entry.requestId, "accepted");
		}
		const queue = [
			...snapshot.steering.map(entry => queued(entry, "steering")),
			...snapshot.followUp.map(entry => queued(entry, "followUp")),
		];
		const push = `${JSON.stringify({ queue, liveSteered: snapshot.liveSteered })}\n`;
		for (const client of this.#clients) if (!client.destroyed) client.write(push);
	}

	/** A dialog opened or closed: every client gets OMP's whole open-dialog snapshot. */
	dialogsChanged(ctx: ExtensionContext): void {
		if (!this.#published || typeof ctx.ui.getOpenDialogs !== "function") return;
		const push = `${JSON.stringify({ dialogs: ctx.ui.getOpenDialogs() })}\n`;
		for (const client of this.#clients) if (!client.destroyed) client.write(push);
	}

	/** A user message reached message_end: the one Herdline sent with this id is in the session. */
	messageEnded(requestId: string): void {
		this.#seen(this.#endedSeen, requestId, "admitted");
	}

	#seen(set: Set<string>, requestId: string, late: "accepted" | "admitted"): void {
		set.add(requestId);
		for (const wake of this.#waiters) wake();
		// A send that already answered uncertain becomes known here, for Check; held
		// like a send so no second upgrade or send runs beside it.
		const record = this.#records.get(requestId);
		const published = this.#published;
		if (record?.outcome !== "pending" || this.#inFlight.has(requestId) || !published) return;
		this.#inFlight.add(requestId);
		this.#store(published.ctx, { ...record, outcome: late, at: Date.now() })
			.catch((error: unknown) => {
				// Still pending, so still uncertain: a receipt is never upgraded without its record.
				this.#pi.logger.warn("herdline-ingress: late outcome not recorded", { requestId, error: String(error) });
			})
			.finally(() => this.#inFlight.delete(requestId));
	}

	/** Request ids with a send or a late record in progress: one at a time per id. */
	readonly #inFlight = new Set<string>();

	async #send(published: Published, id: string, line: { [key: string]: unknown }): Promise<object> {
		const ctx = published.ctx;
		const refuse = (reason: Reason) => ({ id, outcome: "refused" as Outcome, reason, state: this.#state(ctx) });
		const uncertain = () => ({ id, outcome: "uncertain" as Outcome, state: this.#state(ctx) });
		const instance = line.instance as { [key: string]: unknown } | undefined;
		// The exact session, path and nonce this endpoint was published for, still current.
		const sameInstance = () =>
			this.#published === published &&
			typeof instance === "object" &&
			instance !== null &&
			instance.sessionId === published.sessionId &&
			instance.sessionFile === published.sessionFile &&
			instance.pid === this.#options.pid &&
			instance.nonce === published.nonce &&
			ctx.sessionManager.getSessionId() === published.sessionId &&
			ctx.sessionManager.getSessionFile() === published.sessionFile;
		if (!sameInstance()) return refuse("instance_changed");
		const text = typeof line.text === "string" ? line.text : undefined;
		const deliverAs = line.deliverAs;
		if (text === undefined || (deliverAs !== "auto" && deliverAs !== "steer" && deliverAs !== "followUp")) {
			return uncertain();
		}
		const digest = digestOf(text, line.attachments);
		// A repeated id is answered from its record and never sent again; one still in
		// progress is not known yet, so it is uncertain.
		const stored = this.#records.get(id);
		if (stored) {
			if (stored.digest !== digest || stored.outcome === "pending") return uncertain();
			if (stored.outcome === "refused") return refuse(stored.reason ?? "dropped");
			return { id, outcome: "replayed" as Outcome, state: this.#state(ctx) };
		}
		if (this.#inFlight.has(id)) return uncertain();
		// Reserved before the first await: a concurrent duplicate meets it above.
		this.#inFlight.add(id);
		try {
			const gate = () => {
				if (ctx.ui.hasOpenDialog()) return "dialog_open";
				return collabRefusal(ctx);
			};
			const early = gate();
			if (early) return refuse(early);
			const images = await this.#images(line.attachments);
			if (typeof images === "string") return refuse(images);
			if (text.trim() === "" && images.length === 0) return refuse("empty");

			const record: RequestRecord = { id, digest, outcome: "pending", at: Date.now() };
			// Pending in memory at once: whatever the write does, this id is never sent again here.
			this.#records.set(id, record);
			try {
				await this.#store(ctx, record);
			} catch {
				// The pending record may or may not be on disk: nothing is sent, and the id stays unknown.
				return uncertain();
			}
			// A dialog, collab or another session may have come during the awaits above.
			const late = sameInstance() ? gate() : "instance_changed";
			if (late) {
				// Nothing was sent, so it is refused even if its record cannot be written.
				await this.#store(ctx, { ...record, outcome: "refused", reason: late, at: Date.now() }).catch(() => {});
				return refuse(late);
			}
			const content: Content =
				images.length === 0
					? text
					: [...(text === "" ? [] : [{ type: "text" as const, text }]), ...images];
			// auto: a working agent takes it as a steer; an idle one starts the turn the user asked for.
			const deliver: Deliver = deliverAs === "auto" ? (ctx.isIdle() ? undefined : "steer") : deliverAs;
			const settled = this.#pi
				.sendUserMessage(content, deliver ? { deliverAs: deliver, requestId: id } : { requestId: id })
				.then(
					() => "resolved" as const,
					(error: unknown) => (isDropped(error) ? ("dropped" as const) : ("failed" as const)),
				);
			const result = deliver ? await this.#queuedResult(ctx, id, settled) : await this.#promptResult(id, settled);
			if (result === "uncertain") return uncertain();
			const outcome: Stored = result === "dropped" ? "refused" : result;
			const final: RequestRecord = {
				...record,
				outcome,
				at: Date.now(),
				...(result === "dropped" ? { reason: "dropped" as Reason } : {}),
			};
			try {
				await this.#store(ctx, final);
			} catch {
				// The record stays pending: status says uncertain, never an unrecorded accepted.
				return uncertain();
			}
			return result === "dropped" ? refuse("dropped") : { id, outcome: result as Outcome, state: this.#state(ctx) };
		} finally {
			this.#inFlight.delete(id);
		}
	}

	/** A steer or follow-up: accepted once the call resolved and OMP's queue (or the turn) shows the id. */
	async #queuedResult(
		ctx: ExtensionContext,
		id: string,
		settled: Promise<"resolved" | "dropped" | "failed">,
	): Promise<"accepted" | "dropped" | "uncertain"> {
		const how = await settled;
		if (how !== "resolved") return how === "dropped" ? "dropped" : "uncertain";
		const inQueue = () =>
			this.#queuedSeen.has(id) ||
			this.#endedSeen.has(id) ||
			[...ctx.getQueuedMessages().steering, ...ctx.getQueuedMessages().followUp].some(e => e.requestId === id);
		return (await this.#until(inQueue, this.#options.observeMs ?? 2_000)) ? "accepted" : "uncertain";
	}

	/**
	 * An idle send starts a turn, and its call returns only with that turn:
	 * admitted once the user message reached message_end, accepted if a turn
	 * started meanwhile and it was queued instead.
	 */
	async #promptResult(
		id: string,
		settled: Promise<"resolved" | "dropped" | "failed">,
	): Promise<"accepted" | "admitted" | "dropped" | "uncertain"> {
		let how: "resolved" | "dropped" | "failed" | undefined;
		void settled.then(value => {
			how = value;
			for (const wake of this.#waiters) wake();
		});
		await this.#until(
			() => this.#endedSeen.has(id) || this.#queuedSeen.has(id) || how !== undefined,
			this.#options.admitMs ?? 12_000,
		);
		if (this.#endedSeen.has(id)) return "admitted";
		if (this.#queuedSeen.has(id)) return "accepted";
		return how === "dropped" ? "dropped" : "uncertain";
	}

	/** Waits until [done] holds, re-checked on every queue or message event, or [ms] passed. */
	#until(done: () => boolean, ms: number): Promise<boolean> {
		if (done()) return Promise.resolve(true);
		const { promise, resolve } = Promise.withResolvers<boolean>();
		const wake = () => {
			if (done()) finish(true);
		};
		const finish = (value: boolean) => {
			clearTimeout(timer);
			this.#waiters.delete(wake);
			resolve(value);
		};
		const timer = setTimeout(() => finish(done()), ms);
		this.#waiters.add(wake);
		return promise;
	}

	/** The stored outcome of [id] and the queue; asks nothing of OMP and never sends. */
	#status(ctx: ExtensionContext, id: string): object {
		const record = this.#records.get(id);
		const state = this.#state(ctx);
		switch (record?.outcome) {
			case "accepted":
			case "admitted":
				return { id, outcome: record.outcome, state };
			case "refused":
				return { id, outcome: "refused", reason: record.reason ?? "dropped", state };
			default:
				return { id, outcome: "uncertain", state };
		}
	}

	/** Images from Herdline's staging root only, by their real path, within the staging cap. */
	async #images(attachments: unknown): Promise<{ type: "image"; data: string; mimeType: string }[] | Reason> {
		if (attachments === undefined) return [];
		if (!Array.isArray(attachments)) return "unsupported_type";
		let root: string;
		try {
			root = await fs.promises.realpath(this.#options.stagingRoot);
		} catch {
			return "unsupported_type";
		}
		const images: { type: "image"; data: string; mimeType: string }[] = [];
		for (const item of attachments) {
			const { path: file, mimeType } = (item ?? {}) as { path?: unknown; mimeType?: unknown };
			if (typeof file !== "string" || typeof mimeType !== "string" || !IMAGE_TYPES[mimeType]) {
				return "unsupported_type";
			}
			let real: string;
			try {
				real = await fs.promises.realpath(file);
			} catch {
				return "unsupported_type";
			}
			if (!real.startsWith(`${root}${path.sep}`)) return "unsupported_type";
			const stat = await fs.promises.stat(real);
			if (!stat.isFile()) return "unsupported_type";
			if (stat.size > IMAGE_LIMIT) return "too_large";
			images.push({ type: "image", data: (await fs.promises.readFile(real)).toString("base64"), mimeType });
		}
		return images;
	}

	#state(ctx: ExtensionContext): State {
		const snapshot = ctx.getQueuedMessages();
		// Entries already steered into the running turn wait no longer, and cannot be withdrawn.
		return {
			idle: ctx.isIdle(),
			queued: [
				...snapshot.steering.slice(snapshot.liveSteered).map(entry => queued(entry, "steering")),
				...snapshot.followUp.map(entry => queued(entry, "followUp")),
			],
			subagents: this.#subagents.snapshot(),
		};
	}

	/**
	 * Appends [record] and fences it: `ensureOnDisk`, then `flush`, so the
	 * session file holds it if OMP crashes (not if the machine loses power:
	 * no fsync is claimed). Only then does it count here, so nothing is
	 * answered or sent on a record that may not be there.
	 */
	async #store(ctx: ExtensionContext, record: RequestRecord): Promise<void> {
		this.#pi.appendEntry(CUSTOM_TYPE, record);
		await ctx.sessionManager.ensureOnDisk();
		await ctx.sessionManager.flush();
		this.#records.set(record.id, record);
	}

	/** The records this session already holds: a restart answers from them, never by sending again. */
	#rebuild(ctx: ExtensionContext): void {
		this.#records.clear();
		for (const entry of ctx.sessionManager.getEntries()) {
			if (entry.type !== "custom" || entry.customType !== CUSTOM_TYPE) continue;
			const data = entry.data as Partial<RequestRecord> | undefined;
			if (typeof data?.id !== "string" || typeof data.digest !== "string") continue;
			if (data.outcome !== "pending" && data.outcome !== "accepted" && data.outcome !== "admitted" && data.outcome !== "refused") {
				continue;
			}
			this.#records.set(data.id, {
				id: data.id,
				digest: data.digest,
				outcome: data.outcome,
				at: typeof data.at === "number" ? data.at : 0,
				...(typeof data.reason === "string" ? { reason: data.reason } : {}),
			});
		}
	}

	#listen(socket: string): Promise<net.Server> {
		const server = net.createServer(client => this.#serve(client));
		const { promise, resolve, reject } = Promise.withResolvers<net.Server>();
		// The socket file is created 0600 from the start, never briefly open.
		const umask = process.umask(0o077);
		server.once("error", error => {
			process.umask(umask);
			reject(error);
		});
		server.listen(socket, () => {
			process.umask(umask);
			fs.chmodSync(socket, 0o600);
			resolve(server);
		});
		return promise;
	}

	/** One client: lines answered in order. The socket is never ended first; the client closes it. */
	#serve(client: net.Socket): void {
		this.#clients.add(client);
		client.setEncoding("utf8");
		let buffer = "";
		let chain = Promise.resolve();
		client.on("data", (chunk: string) => {
			buffer += chunk;
			if (buffer.length > LINE_MAX && !buffer.includes("\n")) {
				client.destroy();
				return;
			}
			let at = buffer.indexOf("\n");
			while (at >= 0) {
				const line = buffer.slice(0, at);
				buffer = buffer.slice(at + 1);
				chain = chain.then(() => this.#answer(client, line));
				at = buffer.indexOf("\n");
			}
		});
		client.on("error", () => {});
		client.on("close", () => this.#clients.delete(client));
	}

	async #answer(client: net.Socket, line: string): Promise<void> {
		if (line.length > LINE_MAX) return;
		let request: unknown;
		try {
			request = JSON.parse(line);
		} catch {
			return;
		}
		let reply: object | undefined;
		try {
			reply = await this.handle(request);
		} catch (error) {
			this.#pi.logger.warn("herdline-ingress: request failed", { error: String(error) });
			const id = (request as { id?: unknown }).id;
			// Whatever happened, nothing here may read as refused: the request may have gone.
			reply = typeof id === "string" && UUID.test(id) ? { id, outcome: "uncertain" } : undefined;
		}
		// Compact JSON: Herdline finds its answer by the literal `"id":"<uuid>"`.
		if (reply && !client.destroyed) client.write(`${JSON.stringify(reply)}\n`);
	}
}

type QueueEntry = { text: string; requestId?: string };

function queued(entry: QueueEntry, queue: "steering" | "followUp"): Queued {
	return { requestId: entry.requestId ?? null, text: entry.text, queue };
}

/** [dir] made 0700 if missing, and only used when it is a real directory of this user's, 0700. */
function privateDir(dir: string): boolean {
	try {
		fs.mkdirSync(dir, { mode: 0o700 });
	} catch (error) {
		if ((error as NodeJS.ErrnoException).code !== "EEXIST") return false;
	}
	const stat = fs.lstatSync(dir);
	return (
		stat.isDirectory() &&
		!stat.isSymbolicLink() &&
		stat.uid === process.getuid?.() &&
		(stat.mode & 0o777) === 0o700
	);
}

/** Registers the endpoint's lifecycle and events on [pi]. */
export function install(pi: ExtensionAPI, options: IngressOptions): Ingress {
	const ingress = new Ingress(pi, options);
	const publish = async (ctx: ExtensionContext) => {
		try {
			await ingress.publish(ctx);
		} catch (error) {
			pi.logger.warn("herdline-ingress: endpoint not published", { error: String(error) });
			await ingress.unpublish().catch(() => {});
		}
	};
	pi.on("session_start", (_event, ctx) => publish(ctx));
	// Another session in this process: its own endpoint, its own records.
	pi.on("session_switch", (_event, ctx) => publish(ctx));
	pi.on("session_shutdown", () => ingress.unpublish());
	pi.on("queue_update", event => ingress.queueUpdated(event));
	pi.on("ui_dialog_open", (_event, ctx) => ingress.dialogsChanged(ctx));
	pi.on("ui_dialog_close", (_event, ctx) => ingress.dialogsChanged(ctx));
	pi.on("message_end", event => {
		const message = event.message;
		if (message.role === "user" && typeof message.requestId === "string") ingress.messageEnded(message.requestId);
	});
	return ingress;
}

export default function herdlineIngress(pi: ExtensionAPI): void {
	install(pi, {
		runtimeDir: process.env.XDG_RUNTIME_DIR,
		stagingRoot: path.join(os.homedir(), ".cache", "herdline", "attachments"),
		pid: process.pid,
	});
}
