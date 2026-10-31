//! Keeps each live session in the bot's mode (`BotConfig::mode`, see `agent::modes`): set once per session, and
//! again after the user picks another. The agent may leave it during the session (Plan, once the plan is approved;
//! Auto, on a model without it), and that stands until the next session.

use super::{Actor, NoticeStyle};
use crate::agent::modes::Modes;
use serde_json::{Value, json};

impl Actor {
    /// Records the session's modes from an agent answer or a `config_option_update`.
    pub(super) fn note_modes(&mut self, sid: &str, options: &Value) {
        if let (Some(conn), Some(modes)) = (self.conn.as_mut(), Modes::from_session(options)) {
            conn.modes.insert(sid.to_owned(), modes);
        }
    }

    /// Switches a live session to the bot's mode before its turn, once per session.
    pub(super) async fn sync_mode(&mut self, sid: &str) {
        let Some(wanted) = self.cfg.mode.clone() else { return };
        let Some(conn) = self.conn.as_mut() else { return };
        if !conn.mode_set.insert(sid.to_owned()) {
            return;
        }
        let Some(modes) = conn.modes.get(sid) else { return };
        let Some(id) = modes.resolve(&wanted).map(str::to_owned) else {
            // An agent without that mode (or without Auto) keeps its own, and asks as it does.
            tracing::info!(bot = %self.cfg.id, mode = wanted, "the agent doesn't offer this mode");
            return;
        };
        if modes.current.as_deref() == Some(id.as_str()) {
            return;
        }
        let (method, params) = match &modes.config_id {
            Some(config) => ("session/set_config_option", json!({"sessionId": sid, "configId": config, "value": id})),
            None => ("session/set_mode", json!({"sessionId": sid, "modeId": id})),
        };
        match self.session_request(method, params).await {
            Ok(answer) => self.note_options(sid, &answer, super::fast_mode::Listing::Switch),
            Err(error) => {
                tracing::warn!(bot = %self.cfg.id, mode = id, error = format!("{error:#}"), "couldn't set the mode");
                self.notice(&format!("Couldn't switch the agent to its {id} mode: {error:#}"), NoticeStyle::Info);
            }
        }
    }

    /// The user picked another mode: every live session switches before its next turn.
    pub(super) fn mode_changed(&mut self) {
        if let Some(conn) = self.conn.as_mut() {
            conn.mode_set.clear();
        }
    }
}
