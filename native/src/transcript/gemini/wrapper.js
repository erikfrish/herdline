// Herdline's owned Gemini CLI launcher (gemini-cli v0.68.0, 5a5c7180).
//
// Installed, in a QA sandbox only, as a file named exactly `gemini` beside a
// package.json of {"type":"module"}, and run as `node <dir>/gemini [args]`:
// Herdr then detects the pane as Gemini from the script's basename
// (detect/mod.rs:602-630). GEMINI_CLI_HERDLINE_BUNDLE is the absolute path of
// the pinned bundle/gemini.js, imported in this same process with the
// arguments unchanged; GEMINI_CLI_NO_RELAUNCH keeps Gemini from re-spawning
// itself (bundle/gemini.js:50), so this pid stays Gemini's.
//
// This process's pid and a fresh 128-bit nonce reach the session hook through
// the environment (GEMINI_CLI_ keys are never redacted for hooks,
// environmentSanitization.ts:153), and are written to the pane's sidecar
// $XDG_RUNTIME_DIR/herdline-gemini/<pane>.json as {pid, nonce} (sidecar.js,
// beside this file, holds the dir rule): a 0600 file of this uid, written by
// an O_EXCL temp file and a rename. On
// exit, and on SIGINT/SIGTERM/SIGHUP when Gemini has no handler of its own,
// this launch's published session file is removed, and the sidecar too
// while it still holds this nonce. A SIGKILL leaves both behind; the reader
// refuses them until a new SessionStart reports.
import { randomBytes } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { PANE, folder, read, replace, session_file } from './sidecar.js';

const bundle = process.env.GEMINI_CLI_HERDLINE_BUNDLE;
if (!bundle || !path.isAbsolute(bundle)) {
  process.stderr.write(
    'herdline gemini: set GEMINI_CLI_HERDLINE_BUNDLE to the absolute path of the pinned bundle/gemini.js\n',
  );
  process.exit(2);
}

const nonce = randomBytes(16).toString('hex');
process.env.GEMINI_CLI_NO_RELAUNCH = 'true';
process.env.GEMINI_CLI_HERDLINE_PID = String(process.pid);
process.env.GEMINI_CLI_HERDLINE_GEN = nonce;
// Never a pane or socket inherited from an outer launch.
delete process.env.GEMINI_CLI_HERDLINE_PANE;
delete process.env.GEMINI_CLI_HERDLINE_SOCKET;

let sidecar = null;
const pane = process.env.HERDR_PANE_ID;
if (pane && PANE.test(pane)) {
  process.env.GEMINI_CLI_HERDLINE_PANE = pane;
  if (process.env.HERDR_SOCKET_PATH) {
    process.env.GEMINI_CLI_HERDLINE_SOCKET = process.env.HERDR_SOCKET_PATH;
  }
  try {
    sidecar = write(pane);
  } catch (error) {
    process.stderr.write(
      `herdline gemini: no sidecar (${error.code || error.message}); Chat refuses this session\n`,
    );
  }
}

function write(pane) {
  const file = path.join(folder(true), `${pane}.json`);
  replace(file, JSON.stringify({ pid: process.pid, nonce }));
  return file;
}

function clear() {
  const file = sidecar;
  sidecar = null;
  if (!file) return;
  try {
    fs.unlinkSync(session_file(pane, nonce));
  } catch {
    // Never published, or already retracted.
  }
  try {
    if (read(pane)?.nonce === nonce) fs.unlinkSync(file);
  } catch {
    // Gone, replaced or unsafe: a newer launch owns the pane now.
  }
}

process.on('exit', clear);
for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(signal, function onSignal() {
    // Gemini's own handler exits the process, and `exit` clears.
    if (process.listenerCount(signal) > 1) return;
    clear();
    process.removeListener(signal, onSignal);
    process.kill(process.pid, signal);
  });
}

await import(pathToFileURL(bundle).href);
