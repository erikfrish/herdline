/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
//! The JNI surface of `io.github.erikfrish.herdline.core.Native`. Sessions cross the
//! boundary as registry IDs rather than pointers, so no handle can dangle or
//! be freed twice; strings are parsed into domain types here and nowhere else.
//!
//! JNI resolves `Java_…` symbols by their exact names, so each export must be
//! `#[unsafe(no_mangle)]`; that attribute is the only unsafe code in this
//! crate, and every function body is safe Rust over the `jni` crate.
//!
//! Each body runs inside `EnvUnowned::with_env`, which also catches panics so
//! none unwinds into the JVM; an `ErrorPolicy` then turns the outcome into the
//! value Java receives.
#![allow(unsafe_code)]

use crate::{
    Error, Result, attach, config, herdr_setup,
    input::Touch,
    keys,
    session::{self, Session},
    ssh_config,
    theme::Theme,
};
use jni::{
    Env, EnvUnowned,
    errors::ErrorPolicy,
    jni_str,
    objects::{JByteArray, JClass, JString, Reference as _},
    strings::JNIString,
    sys::{jboolean, jint, jlong},
};
use std::{
    any::Any,
    collections::HashMap,
    sync::{
        Arc, LazyLock, Mutex, PoisonError,
        atomic::{AtomicI64, Ordering},
    },
    time::Duration,
};
use zeroize::Zeroizing;

static SESSIONS: LazyLock<Mutex<HashMap<i64, Arc<Session>>>> = LazyLock::new(Mutex::default);
static NEXT_ID: AtomicI64 = AtomicI64::new(1);

fn session(id: jlong) -> Result<Arc<Session>> {
    SESSIONS
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .get(&id)
        .cloned()
        .ok_or(Error::NoSession)
}

fn string(env: &Env, value: &JString) -> Result<String> {
    Ok(value.try_to_string(env)?)
}

fn optional(env: &Env, value: &JString) -> Result<Option<String>> {
    if value.is_null() {
        return Ok(None);
    }
    string(env, value).map(Some)
}

/// Raise `message` as a Java `IOException` unless one is already pending.
fn throw(env: &mut Env, message: &str) {
    if !env.exception_check() {
        let _ = env.throw_new(jni_str!("java/io/IOException"), JNIString::from(message));
    }
}

/// Errors and panics become a Java `IOException`; Java gets a placeholder
/// (null, zero or false) alongside it.
struct ThrowIo;

impl<T: Default> ErrorPolicy<T, Error> for ThrowIo {
    type Captures<'local: 'call, 'call> = ();

    fn on_error<'local: 'call, 'call>(
        env: &mut Env<'local>,
        _: &mut (),
        error: Error,
    ) -> jni::errors::Result<T> {
        throw(env, &error.to_string());
        Ok(T::default())
    }

    fn on_panic<'local: 'call, 'call>(
        env: &mut Env<'local>,
        _: &mut (),
        _: Box<dyn Any + Send>,
    ) -> jni::errors::Result<T> {
        tracing::error!("native call panicked");
        throw(env, "native call panicked");
        Ok(T::default())
    }
}

/// Input failures (not yet connected, a full queue) are logged and dropped:
/// a keystroke is not worth an exception.
struct DropInput;

impl<T: Default> ErrorPolicy<T, Error> for DropInput {
    type Captures<'local: 'call, 'call> = ();

    fn on_error<'local: 'call, 'call>(
        _: &mut Env<'local>,
        _: &mut (),
        error: Error,
    ) -> jni::errors::Result<T> {
        tracing::debug!(%error, "input not sent");
        Ok(T::default())
    }

    fn on_panic<'local: 'call, 'call>(
        _: &mut Env<'local>,
        _: &mut (),
        _: Box<dyn Any + Send>,
    ) -> jni::errors::Result<T> {
        tracing::error!("input handler panicked");
        Ok(T::default())
    }
}

fn u16_of(value: jint) -> u16 {
    u16::try_from(value.max(0)).unwrap_or(u16::MAX)
}

fn u32_of(value: jint) -> u32 {
    u32::try_from(value.max(0)).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_init<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    #[cfg(target_os = "android")]
    {
        use tracing_subscriber::layer::SubscriberExt as _;
        let subscriber = tracing_subscriber::registry().with(paranoid_android::layer("Herdline"));
        let _ = tracing::subscriber::set_global_default(subscriber);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_generateKey<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    comment: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let identity = keys::generate(&string(env, &comment)?)?;
        Ok(env.new_string(&*identity.private)?)
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_publicKey<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    private: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let private = Zeroizing::new(string(env, &private)?);
        Ok(env.new_string(keys::public_of(&private)?)?)
    })
    .resolve::<ThrowIo>()
}

/// Start connecting with the JSON document `config.rs` describes. The text
/// holds secrets: it is parsed into zeroizing types and not kept.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_connect<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    config_json: JString<'local>,
) -> jlong {
    env.with_env(|env| -> Result<_> {
        let json = Zeroizing::new(string(env, &config_json)?);
        let session = Session::connect(config::parse(&json)?)?;
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        SESSIONS
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .insert(id, Arc::new(session));
        Ok(id)
    })
    .resolve::<ThrowIo>()
}

/// Install or update Herdr on the last host of the route document; returns
/// `{"exit_status": …, "output": …}`. Blocks for minutes: never call it on
/// the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_setupHerdr<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    config_json: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let json = Zeroizing::new(string(env, &config_json)?);
        let outcome = herdr_setup::run(config::parse(&json)?.route)?;
        Ok(env.new_string(outcome.to_json())?)
    })
    .resolve::<ThrowIo>()
}

/// Import key text; returns `{"private": …, "info": KeyInfo}`, the private
/// key decrypted for storage.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_importKey<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
    passphrase: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let text = Zeroizing::new(string(env, &text)?);
        let passphrase = optional(env, &passphrase)?.map(Zeroizing::new);
        let (private, info) = keys::import(&text, passphrase.as_deref().map(String::as_str))?;
        let json = Zeroizing::new(
            serde_json::json!({"private": private.as_str(), "info": info}).to_string(),
        );
        Ok(env.new_string(&*json)?)
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_certificateInfo<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    certificate: JString<'local>,
    private: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let certificate = string(env, &certificate)?;
        let private = Zeroizing::new(string(env, &private)?);
        let json = serde_json::to_string(&keys::certificate(&certificate, &private)?)?;
        Ok(env.new_string(json)?)
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_parseSshConfig<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let json = serde_json::to_string(&ssh_config::parse(&string(env, &text)?))?;
        Ok(env.new_string(json)?)
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_nextEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    timeout_ms: jint,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let Ok(session) = session(id) else {
            return Ok(env.new_string(r#"{"type":"closed"}"#)?);
        };
        let timeout = Duration::from_millis(u64::from(u32_of(timeout_ms)));
        match session.next_event(timeout) {
            Some(event) => Ok(env.new_string(event.to_json())?),
            None => Ok(JString::default()),
        }
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_takeFrame<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> JByteArray<'local> {
    env.with_env(|env| -> Result<_> {
        match session(id).ok().and_then(|s| s.take_frame()) {
            Some(frame) => Ok(env.byte_array_from_slice(&frame.pack())?),
            None => Ok(JByteArray::default()),
        }
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_takeView<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        match session(id).ok().and_then(|s| s.take_view()) {
            Some(view) => Ok(env.new_string(view)?),
            None => Ok(JString::default()),
        }
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_key<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    pane: JString<'local>,
    key_code: jint,
    meta: jint,
    unicode: jint,
    repeat: jboolean,
) -> jboolean {
    env.with_env(|env| -> Result<_> {
        let pane = optional(env, &pane)?;
        let unicode = u32::try_from(unicode)
            .ok()
            .and_then(char::from_u32)
            .filter(|c| *c != '\0');
        session(id)?.key(pane.as_deref(), key_code, meta, unicode, repeat)
    })
    .resolve::<DropInput>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_chord<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    pane: JString<'local>,
    codepoint: jint,
    meta: jint,
) {
    env.with_env(|env| -> Result<_> {
        let pane = optional(env, &pane)?;
        let Some(ch) = u32::try_from(codepoint).ok().and_then(char::from_u32) else {
            return Ok(());
        };
        session(id)?.chord(pane.as_deref(), ch, meta)
    })
    .resolve::<DropInput>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_text<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    pane: JString<'local>,
    text: JString<'local>,
) {
    env.with_env(|env| -> Result<_> {
        let pane = optional(env, &pane)?;
        let text = string(env, &text)?;
        session(id)?.text_input(pane.as_deref(), &text)
    })
    .resolve::<DropInput>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_paste<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    pane: JString<'local>,
    text: JString<'local>,
) {
    env.with_env(|env| -> Result<_> {
        let pane = optional(env, &pane)?;
        let text = string(env, &text)?;
        session(id)?.paste(pane.as_deref(), &text)
    })
    .resolve::<DropInput>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_touch<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    pane: JString<'local>,
    kind: jint,
    column: jint,
    row: jint,
    lines: jint,
) {
    env.with_env(|env| -> Result<_> {
        let pane = string(env, &pane)?;
        let kind = match kind {
            1 => Touch::ScrollUp,
            2 => Touch::ScrollDown,
            _ => Touch::Tap,
        };
        session(id)?.touch(&pane, kind, u16_of(column), u16_of(row), u16_of(lines))
    })
    .resolve::<DropInput>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_resize<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    cols: jint,
    rows: jint,
    cell_width: jint,
    cell_height: jint,
) {
    env.with_env(|_| -> Result<_> {
        session(id)?.resize(session::options(
            u16_of(cols),
            u16_of(rows),
            u32_of(cell_width),
            u32_of(cell_height),
        ))
    })
    .resolve::<DropInput>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_request<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    method: JString<'local>,
    params: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let method = string(env, &method)?;
        let params = serde_json::from_str(&string(env, &params)?)?;
        let request_id = session(id)?.request(&method, params)?;
        Ok(env.new_string(request_id)?)
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_composerCheck<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    pane: JString<'local>,
    viewport: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let pane = string(env, &pane)?;
        let viewport = optional(env, &viewport)?;
        let check = session(id)?.composer_check(&pane, viewport.as_deref())?;
        Ok(env.new_string(check)?)
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_composerDeliver<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    request: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let request = string(env, &request)?;
        let outcome = session(id)?.composer_deliver(&request)?;
        Ok(env.new_string(outcome)?)
    })
    .resolve::<ThrowIo>()
}

/// The first step of a steer. Blocks on the host: Kotlin calls it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_composerSteer<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    request: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let request = string(env, &request)?;
        let outcome = session(id)?.composer_steer(&request)?;
        Ok(env.new_string(outcome)?)
    })
    .resolve::<ThrowIo>()
}

/// The Enter step of a steer. Blocks on the host: Kotlin calls it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_composerSteerEnter<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    target: JString<'local>,
    receipt: JString<'local>,
    viewport: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let target = string(env, &target)?;
        let receipt = string(env, &receipt)?;
        let viewport = optional(env, &viewport)?;
        let outcome = session(id)?.composer_steer_enter(&target, &receipt, viewport.as_deref())?;
        Ok(env.new_string(outcome)?)
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_composerSteerCancel<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    target: JString<'local>,
) {
    env.with_env(|env| -> Result<_> { Session::composer_steer_cancel(&string(env, &target)?) })
        .resolve::<ThrowIo>()
}

/// Opens one attachment upload. Blocks on the host: Kotlin calls it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_attachmentBegin<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    request: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let request = string(env, &request)?;
        let outcome = session(id)?.attachment_begin(&request)?;
        Ok(env.new_string(outcome)?)
    })
    .resolve::<ThrowIo>()
}

/// The first `length` bytes of `bytes`, next in the upload. Blocks while
/// the host is slower than the phone.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_attachmentWrite<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    upload: jlong,
    bytes: JByteArray<'local>,
    length: jint,
) {
    env.with_env(|env| -> Result<_> {
        let mut chunk = env.convert_byte_array(&bytes)?;
        let length = usize::try_from(length).map_err(|_| Error::BadReply)?;
        if length > chunk.len() {
            return Err(Error::BadReply);
        }
        chunk.truncate(length);
        attach::write(upload, chunk)
    })
    .resolve::<ThrowIo>()
}

/// Ends an upload and names the file once the host's SHA-256 is `sha256`.
/// Blocks on the host.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_attachmentFinish<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    upload: jlong,
    sha256: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let sha256 = string(env, &sha256)?;
        let outcome = attach::finish(upload, &sha256)?;
        Ok(env.new_string(outcome)?)
    })
    .resolve::<ThrowIo>()
}

/// Abandons an upload; returns at once.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_attachmentCancel<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    upload: jlong,
) {
    attach::cancel(upload);
}

/// Removes a staged attachment from the host. Blocks on the host.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_attachmentDiscard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    attachment: JString<'local>,
) {
    env.with_env(|env| -> Result<_> {
        let attachment = string(env, &attachment)?;
        session(id)?.attachment_discard(&attachment)
    })
    .resolve::<ThrowIo>()
}

/// Blocks on the host: Kotlin calls it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_composerInstance<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    pane: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let pane = string(env, &pane)?;
        let instance = session(id)?.composer_instance(&pane)?;
        Ok(env.new_string(instance)?)
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_composerHold<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    target: JString<'local>,
) {
    env.with_env(|env| -> Result<_> { Session::composer_hold(&string(env, &target)?) })
        .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_composerUnlock<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    target: JString<'local>,
) {
    env.with_env(|env| -> Result<_> { Session::composer_unlock(&string(env, &target)?) })
        .resolve::<ThrowIo>()
}

/// Blocks on the host: Kotlin calls it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_transcript<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    request: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let request = string(env, &request)?;
        let transcript = session(id)?.transcript(&request)?;
        Ok(env.new_string(transcript)?)
    })
    .resolve::<ThrowIo>()
}

/// Blocks on the host: Kotlin calls it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_ingress<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    request: JString<'local>,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let request = string(env, &request)?;
        let reply = session(id)?.ingress(&request)?;
        Ok(env.new_string(reply)?)
    })
    .resolve::<ThrowIo>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_setTheme<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    theme_json: JString<'local>,
) {
    env.with_env(|env| -> Result<_> {
        let result = (|| {
            let theme: Theme = serde_json::from_str(&string(env, &theme_json)?)?;
            session(id)?.set_theme(theme);
            Ok::<_, Error>(())
        })();
        if let Err(error) = result {
            tracing::warn!(%error, "theme not applied");
        }
        Ok(())
    })
    .resolve::<DropInput>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_selectionText<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    start_column: jint,
    start_row: jint,
    end_column: jint,
    end_row: jint,
) -> JString<'local> {
    env.with_env(|env| -> Result<_> {
        let text = session(id)
            .map(|session| {
                session.text(
                    (u16_of(start_column), u16_of(start_row)),
                    (u16_of(end_column), u16_of(end_row)),
                )
            })
            .unwrap_or_default();
        Ok(env.new_string(text)?)
    })
    .resolve::<ThrowIo>()
}

/// Drop the session. Its worker stops on its own thread; nothing is joined
/// here, so the UI thread never waits on the network.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_erikfrish_herdline_core_Native_disconnect<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
) {
    let session = SESSIONS
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .remove(&id);
    if let Some(session) = session {
        session.disconnect();
    }
}
