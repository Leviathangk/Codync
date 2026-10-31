//! Keeps each live session in the bot's mode (`BotConfig::mode`, see `agent::modes`): set once per session, and
//! again after the user picks another. The agent may leave it during the session (Plan, once the plan is approved;
//! Auto, on a model without it), and that stands until the next session. An agent without that mode keeps its own,
//! and Codync asks about whatever it asks.

use super::Actor;
use crate::agent::modes::Modes;
use anyhow::{Context, Result};
use serde_json::{Value, json};

impl Actor {
    /// Records the session's modes from an agent answer or a `config_option_update`.
    pub(super) fn note_modes(&mut self, sid: &str, options: &Value) {
        if let (Some(conn), Some(modes)) = (self.conn.as_mut(), Modes::from_session(options)) {
            conn.modes.insert(sid.to_owned(), modes);
        }
    }

    /// Switches a live session to the bot's mode before its turn, once per session. A switch the agent refuses
    /// fails the turn: the bot never works in a mode the user didn't pick.
    pub(super) async fn sync_mode(&mut self, sid: &str) -> Result<()> {
        let Some(wanted) = self.cfg.mode.clone() else { return Ok(()) };
        let Some(conn) = self.conn.as_mut() else { return Ok(()) };
        if conn.mode_set.contains(sid) {
            return Ok(());
        }
        let target = conn.modes.get(sid).map(|modes| {
            let id = modes.resolve(&wanted).map(str::to_owned);
            let current = id.is_some() && modes.current == id;
            let name = modes.list.iter().find(|m| m["id"].as_str() == id.as_deref()).and_then(|m| m["name"].as_str());
            (id, current, modes.config_id.clone(), name.map(str::to_owned))
        });
        let (Some(id), false, config, name) = target.unwrap_or_default() else {
            // Already in it, or an agent without that mode (or without Auto): it keeps its own and asks as it does.
            conn.mode_set.insert(sid.to_owned());
            return Ok(());
        };
        let (method, params) = match config {
            Some(config) => ("session/set_config_option", json!({"sessionId": sid, "configId": config, "value": id})),
            None => ("session/set_mode", json!({"sessionId": sid, "modeId": id})),
        };
        let answer = self.session_request(method, params).await.with_context(|| {
            let mode = name.unwrap_or_else(|| id.clone());
            format!("Couldn't switch the agent to {mode}, the mode this bot works in, so this turn didn't start")
        })?;
        self.note_options(sid, &answer, super::fast_mode::Listing::Switch);
        if let Some(conn) = self.conn.as_mut() {
            conn.mode_set.insert(sid.to_owned());
        }
        Ok(())
    }

    /// The user picked another mode: every live session switches before its next turn.
    pub(super) fn mode_changed(&mut self) {
        if let Some(conn) = self.conn.as_mut() {
            conn.mode_set.clear();
        }
    }
}
