/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
//! Herdline's Android core. The Kotlin app owns the screens and gestures;
//! this library owns everything below them: SSH to the host, Herdr's
//! remote bridge and protocol (through herdr-client), and the libghostty-vt
//! terminal that turns daemon surfaces into frames the app draws.

pub mod attach;
mod clipboard;
pub mod composer;
pub mod config;
mod delivery;
mod error;
pub mod herdr_setup;
mod hold;
pub mod input;
mod jni_api;
pub mod keys;
pub mod render;
mod screen;
pub mod scroll;
pub mod session;
mod shown;
pub mod ssh;
pub mod ssh_config;
pub mod steer;
pub mod theme;
pub mod transcript;
pub mod view;
pub mod visible;
pub mod vt;

pub use error::{Error, Result};
pub use screen::Screen;
