/** Opt-in OMP identity and status for Herdline's herdr-core backend.
 * Load explicitly with `omp -e /path/to/herdline-core.ts` inside an hc terminal.
 * No prompts, installation, or global configuration changes are performed.
 */
import { spawn } from "node:child_process";
import { accessSync, constants } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { ExtensionAPI, ExtensionContext } from "@oh-my-pi/pi-coding-agent";

const KEYS = ["herdline.agent", "herdline.session_file", "herdline.session_id", "herdline.pid"] as const;

export default function herdlineCore(pi: ExtensionAPI): void {
	const terminal = process.env.HC_TERMINAL_ID;
	if (!terminal) return;
	const command = [...(process.env.PATH ?? "").split(":"), join(homedir(), ".local", "bin")]
		.map(dir => join(dir, "herdr-core"))
		.find(file => { try { accessSync(file, constants.X_OK); return true; } catch { return false; } });
	if (!command) { pi.logger.warn("herdline-core: herdr-core executable not found"); return; }
	let pending = Promise.resolve();
	let epoch = 0;
	let stopped = false;
	const label = (values: string[], version: number) => {
		pending = pending.then(() => new Promise<void>(resolve => {
			if (version !== epoch) return resolve();
			const child = spawn(command, ["label", terminal, ...KEYS.map((key, i) => `${key}=${values[i] ?? ""}`)], {
				stdio: "ignore", timeout: 5000,
			});
			child.once("error", error => {
				pi.logger.warn("herdline-core: identity not published", { error: String(error) });
				resolve();
			});
			child.once("close", code => {
				if (code !== 0) pi.logger.warn("herdline-core: identity command failed", { code });
				resolve();
			});
		}));
		return pending;
	};
	const publish = (ctx: ExtensionContext) => {
		if (stopped) return pending;
		const version = ++epoch;
		const file = ctx.sessionManager.getSessionFile();
		const id = ctx.sessionManager.getSessionId();
		return label(file && id ? ["omp", file, id, String(process.pid)] : [], version);
	};
	const status = (word: "idle" | "working" | "done" | "error") => {
		if (!stopped && process.stdout.isTTY) process.stdout.write(`\x1b]7501;state=${word}:app=omp\x1b\\`);
	};
	pi.on("session_start", async (_event, ctx) => { await publish(ctx); status("idle"); });
	pi.on("session_switch", async (_event, ctx) => { await publish(ctx); status(ctx.isIdle() ? "idle" : "working"); });
	pi.on("agent_start", () => { status("working"); });
	pi.on("agent_end", event => {
		if (event.willContinue || event.awaitingAsyncWork) { status("working"); return; }
		const last = [...event.messages].reverse().find(message => message.role === "assistant");
		const reason = last && "stopReason" in last ? last.stopReason : undefined;
		status(reason === "error" ? "error" : reason === "aborted" ? "idle" : "done");
	});
	pi.on("session_shutdown", async () => {
		stopped = true;
		await label([], ++epoch);
	});
}
