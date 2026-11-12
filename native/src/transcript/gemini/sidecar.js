// The pane sidecar the owned launcher writes and the session hook checks,
// installed beside them: $XDG_RUNTIME_DIR/herdline-gemini/<pane>.json, holding
// {pid, nonce}. As ingress.rs:296-305 does for its own sidecars, the runtime
// dir must be a plain absolute path; here it must also be its own realpath (no
// link in it or any ancestor), and it and herdline-gemini must be this uid's
// dirs with no group or other bits. The binding reader (binding.rs, PYTHON)
// holds the same rule.
import fs from 'node:fs';
import path from 'node:path';

export const PANE = /^[A-Za-z0-9:_-]{1,64}$/;
const BASE = /^\/[A-Za-z0-9._/-]*$/;
const MAX = 256;
const { O_RDONLY, O_NOFOLLOW, O_NONBLOCK } = fs.constants;

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

/// The pane's {pid, nonce}, null when there is none; throws when it is not
/// safe to trust.
export function read(pane) {
  if (!PANE.test(pane)) throw unsafe('pane');
  let fd;
  try {
    fd = fs.openSync(path.join(folder(false), `${pane}.json`), O_RDONLY | O_NOFOLLOW | O_NONBLOCK);
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
  let text;
  try {
    const st = fs.fstatSync(fd);
    if (!st.isFile() || st.uid !== process.getuid() || st.mode & 0o077 || st.nlink !== 1 || st.size > MAX) {
      throw unsafe('sidecar');
    }
    text = fs.readFileSync(fd, 'utf8');
  } finally {
    fs.closeSync(fd);
  }
  const value = JSON.parse(text);
  const keys = value && typeof value === 'object' ? Object.keys(value).sort().join() : '';
  if (keys !== 'nonce,pid' || !Number.isInteger(value.pid) || typeof value.nonce !== 'string') {
    throw unsafe('sidecar');
  }
  return value;
}
