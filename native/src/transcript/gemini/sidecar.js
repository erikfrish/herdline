// The pane sidecar the owned launcher writes and the session hook checks,
// installed beside them: $XDG_RUNTIME_DIR/herdline-gemini/<pane>.json, holding
// {pid, nonce}, and beside it <pane>.<nonce>.session, holding the session
// uuid this launch's hook last published. The reader requires the pane
// tokens' uuid to equal it, so a SessionStart Herdr did not take (a clock
// step back made its seq stale) leaves the old tokens refused, never read.
// As ingress.rs:296-305 does for its own sidecars, the runtime
// dir must be a plain absolute path; here it must also be its own realpath (no
// link in it or any ancestor), and it and herdline-gemini must be this uid's
// dirs with no group or other bits. The binding reader (binding.rs, PYTHON)
// holds the same rule.
import fs from 'node:fs';
import path from 'node:path';

export const PANE = /^[A-Za-z0-9:_-]{1,64}$/;
const BASE = /^\/[A-Za-z0-9._/-]*$/;
const GEN = /^[0-9a-f]{32}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX = 256;
const { O_RDONLY, O_WRONLY, O_CREAT, O_EXCL, O_NOFOLLOW, O_NONBLOCK } = fs.constants;

function unsafe(what) {
  return Object.assign(new Error(`unsafe ${what}`), { code: 'EUNSAFE' });
}

function private_dir(dir) {
  const st = fs.lstatSync(dir);
  if (!st.isDirectory() || st.uid !== process.getuid() || st.mode & 0o077) throw unsafe(dir);
}

/// The sidecar dir, its runtime dir checked; with `make`, created 0700.
export function folder(make) {
  const base = process.env.XDG_RUNTIME_DIR || `/run/user/${process.getuid()}`;
  // realpath also refuses `//`, `.` and `..` segments and a trailing `/`.
  if (!BASE.test(base) || fs.realpathSync(base) !== base) throw unsafe('runtime dir');
  private_dir(base);
  const dir = path.join(base, 'herdline-gemini');
  if (make) {
    try {
      fs.mkdirSync(dir, { mode: 0o700 });
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
    }
  }
  private_dir(dir);
  return dir;
}

/// A private regular file's text, null when there is none; throws when it
/// is not safe to trust.
function private_text(file) {
  let fd;
  try {
    fd = fs.openSync(file, O_RDONLY | O_NOFOLLOW | O_NONBLOCK);
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
  try {
    const st = fs.fstatSync(fd);
    if (!st.isFile() || st.uid !== process.getuid() || st.mode & 0o077 || st.nlink !== 1 || st.size > MAX) {
      throw unsafe(file);
    }
    return fs.readFileSync(fd, 'utf8');
  } finally {
    fs.closeSync(fd);
  }
}

/// `text` as `file`, through an O_EXCL 0600 temp file and a rename.
export function replace(file, text) {
  const temp = `${path.dirname(file)}/.${path.basename(file)}.${process.pid}.tmp`;
  const fd = fs.openSync(temp, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600);
  try {
    fs.writeSync(fd, text);
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  fs.renameSync(temp, file);
}

/// The pane's {pid, nonce}, null when there is none; throws when it is not
/// safe to trust.
export function read(pane) {
  if (!PANE.test(pane)) throw unsafe('pane');
  const text = private_text(path.join(folder(false), `${pane}.json`));
  if (text === null) return null;
  const value = JSON.parse(text);
  const keys = value && typeof value === 'object' ? Object.keys(value).sort().join() : '';
  if (keys !== 'nonce,pid' || !Number.isInteger(value.pid) || typeof value.nonce !== 'string') {
    throw unsafe('sidecar');
  }
  return value;
}

/// Where launch `nonce` in `pane` publishes its current session.
export function session_file(pane, nonce) {
  if (!PANE.test(pane) || !GEN.test(nonce)) throw unsafe('pane');
  return path.join(folder(false), `${pane}.${nonce}.session`);
}

export function publish(pane, nonce, id) {
  if (!UUID.test(id)) throw unsafe('session');
  replace(session_file(pane, nonce), id);
}

/// Removes the published session while it is still `id`.
export function retract(pane, nonce, id) {
  const file = session_file(pane, nonce);
  if (private_text(file) === id) fs.unlinkSync(file);
}
