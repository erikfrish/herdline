// Display snapshots from OMP's public session event bus; no child history is opened.
const MAX_ROWS = 64;
const END: Record<string, true> = { completed: true, failed: true, aborted: true, cancelled: true };
const STATES: Record<string, true> = { pending: true, running: true, ...END };

type ObjectValue = Record<string, unknown>;
export interface ChildSnapshot {
	id: string;
	parentCallId: string;
	agent: string;
	type?: string;
	status: string;
	task?: string;
	stage?: string;
	output?: string;
	durationMs?: number;
	requests?: number;
}
const object = (value: unknown): ObjectValue | undefined =>
	value !== null && typeof value === "object" && !Array.isArray(value) ? value as ObjectValue : undefined;
const idOf = (value: unknown): string | undefined =>
	typeof value === "string" && value.trim() !== "" && value.length <= 256 ? value : undefined;
const text = (value: unknown, max: number): string | undefined =>
	typeof value === "string" && value.trim() !== "" ? value.slice(0, max).replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, "") : undefined;

export class LiveSubagents {
	readonly #rows = new Map<string, ChildSnapshot>();
	clear(): void { this.#rows.clear(); }
	snapshot(): ChildSnapshot[] { return [...this.#rows.values()]; }

	progress(payload: unknown): void {
		const frame = object(payload);
		const progress = object(frame?.progress);
		if (!frame || !progress) return;
		const id = idOf(progress.id);
		const parentCallId = idOf(frame.parentToolCallId);
		const status = typeof progress.status === "string" ? progress.status : "";
		if (!id || !parentCallId || !Object.hasOwn(STATES, status)) return;
		const old = this.#rows.get(id);
		const recent = Array.isArray(progress.recentOutput)
			? progress.recentOutput.slice(0, 8).filter((line): line is string => typeof line === "string").reverse()
			: [];
		const next: ChildSnapshot = {
			id, parentCallId, agent: id,
			type: text(progress.agent ?? frame.agent, 128) ?? old?.type,
			status: old && Object.hasOwn(END, old.status) && !Object.hasOwn(END, status) ? old.status : status,
			task: text(progress.description ?? progress.task ?? frame.task, 1024) ?? old?.task,
			stage: text(progress.stage ?? progress.lastIntent ?? progress.currentToolIntent, 256) ?? old?.stage,
			output: text(recent.map(line => line.slice(0, 512)).join("\n"), 4096) ?? old?.output,
			durationMs: typeof progress.durationMs === "number" && Number.isSafeInteger(progress.durationMs) && progress.durationMs >= 0 ? progress.durationMs : old?.durationMs,
			requests: typeof progress.requests === "number" && Number.isSafeInteger(progress.requests) && progress.requests >= 0 ? progress.requests : old?.requests,
		};
		this.#put(next);
	}

	lifecycle(payload: unknown): void {
		const frame = object(payload);
		if (!frame) return;
		const id = idOf(frame.id);
		const old = id ? this.#rows.get(id) : undefined;
		const parentCallId = idOf(frame.parentToolCallId) ?? old?.parentCallId;
		const status = frame.status === "started" ? "running" : frame.status;
		if (!id || !parentCallId || typeof status !== "string" || !Object.hasOwn(STATES, status)) return;
		this.#put({
			...old, id, parentCallId, agent: id,
			type: text(frame.agent, 128) ?? old?.type,
			task: text(frame.description, 1024) ?? old?.task,
			status: old && Object.hasOwn(END, old.status) && !Object.hasOwn(END, status) ? old.status : status,
		});
	}

	#put(row: ChildSnapshot): void {
		if (!this.#rows.has(row.id) && this.#rows.size >= MAX_ROWS) {
			// Retire finished rows first; never drop a known active child for a new one.
			const finished = [...this.#rows].find(([, child]) => Object.hasOwn(END, child.status));
			if (!finished) return;
			this.#rows.delete(finished[0]);
		}
		this.#rows.set(row.id, row);
	}
}
