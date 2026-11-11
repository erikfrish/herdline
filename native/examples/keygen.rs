/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: renamed to the Herdline package and identity. */
//! Writes a fresh device identity to the given path and prints its public
//! key, for setting up the live test's SSH server.
#![allow(clippy::unwrap_used, clippy::expect_used)]

fn main() {
    let path = std::env::args()
        .nth(1)
        .expect("usage: keygen <private-key-path>");
    let identity = herdline::keys::generate("herdline-live").unwrap();
    std::fs::write(&path, identity.private.as_bytes()).unwrap();
    println!("{}", identity.public);
}
