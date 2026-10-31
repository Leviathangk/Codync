//! The agent's session modes: how it asks before it acts (Claude's Manual / Accept edits / Plan / Auto, Codex's
//! Read-only / Auto review / Full access…), as its `mode` config option or the older `modes` field lists them.
//! A bot names the one it wants (`BotConfig::mode`); what still reaches Codync follows the bot's Approvals.

use serde_json::{Value, json};

/// The mode where the agent itself decides what is safe and asks only about the rest. Codync's name for it,
/// and Claude's id: an agent that calls it something else (Codex's `agent`, "Auto review") is listed under it.
pub const AUTO: &str = "auto";

/// A session's modes as the agent last listed them.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Modes {
    /// The config option that switches them; none for an agent that lists only the older `modes` field.
    pub config_id: Option<String>,
    pub current: Option<String>,
    /// `{id, name, description}`, the agent's ids, in its order.
    pub list: Vec<Value>,
}

impl Modes {
    /// From a `session/new` / `session/load` answer or a `config_option_update`; none when the agent lists no modes.
    pub fn from_session(session: &Value) -> Option<Self> {
        if let Some(option) =
            session["configOptions"].as_array().into_iter().flatten().find(|o| o["category"] == "mode")
        {
            let mut list = Vec::new();
            flatten(&option["options"], &mut list);
            return (!list.is_empty()).then(|| Self {
                config_id: text(&option["id"]),
                current: text(&option["currentValue"]),
                list,
            });
        }
        let modes = &session["modes"];
        let list: Vec<Value> =
            modes["availableModes"].as_array().into_iter().flatten().filter_map(|m| entry(&m["id"], m)).collect();
        (!list.is_empty()).then(|| Self { config_id: None, current: text(&modes["currentModeId"]), list })
    }

    /// The agent's id for the mode a bot wants: its own id, or for [`AUTO`] the agent's Auto mode under any name.
    pub fn resolve(&self, wanted: &str) -> Option<&str> {
        let ids = || self.list.iter().filter_map(|m| m["id"].as_str());
        ids().find(|id| *id == wanted).or_else(|| (wanted == AUTO).then(|| self.auto()).flatten())
    }

    /// The agent's Auto mode: Claude's `auto`, or one named "Auto review" (Codex's `agent`).
    fn auto(&self) -> Option<&str> {
        let named = |m: &&Value| m["name"].as_str().is_some_and(|n| n.eq_ignore_ascii_case("auto review"));
        self.list.iter().find(|m| m["id"] == AUTO).or_else(|| self.list.iter().find(named))?["id"].as_str()
    }

    /// What clients offer: `{modes, currentModeId}`, the Auto mode first and listed as [`AUTO`], so a bot's choice
    /// carries over to another agent that has one.
    pub fn catalog(&self) -> Value {
        let auto = self.auto();
        let current = self.current.as_deref();
        let current = if current.is_some() && current == auto { Some(AUTO) } else { current };
        let mut modes: Vec<Value> = self.list.clone();
        modes.sort_by_key(|m| m["id"].as_str() != auto);
        for mode in &mut modes {
            if mode["id"].as_str() == auto {
                mode["id"] = AUTO.into();
            }
        }
        json!({"modes": modes, "currentModeId": current})
    }
}

fn flatten(options: &Value, out: &mut Vec<Value>) {
    for option in options.as_array().into_iter().flatten() {
        if option["options"].is_array() {
            flatten(&option["options"], out);
        } else if let Some(mode) = entry(&option["value"], option)
            && !out.iter().any(|m| m["id"] == mode["id"])
        {
            out.push(mode);
        }
    }
}

fn entry(id: &Value, mode: &Value) -> Option<Value> {
    let id = text(id)?;
    Some(
        json!({"name": text(&mode["name"]).unwrap_or_else(|| id.clone()), "description": text(&mode["description"]), "id": id}),
    )
}

fn text(v: &Value) -> Option<String> {
    v.as_str().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn claude() -> Value {
        json!({"configOptions": [
            {"id": "model", "category": "model", "type": "select", "options": []},
            {"id": "mode", "category": "mode", "type": "select", "currentValue": "default", "options": [
                {"value": "default", "name": "Manual", "description": "Always ask before making changes"},
                {"value": "acceptEdits", "name": "Accept edits"},
                {"value": "plan", "name": "Plan"},
                {"value": "auto", "name": "Auto", "description": "Claude handles permission decisions"}]}]})
    }

    fn codex() -> Value {
        json!({"configOptions": [{"id": "mode", "category": "mode", "type": "select", "currentValue": "agent", "options": [
            {"value": "read-only", "name": "Read-only"},
            {"value": "agent", "name": "Auto review", "description": "Only ask for actions detected as potentially unsafe"},
            {"value": "agent-full-access", "name": "Full access"}]}]})
    }

    #[test]
    fn auto_is_the_agents_own_auto_mode_under_any_id() {
        let claude = Modes::from_session(&claude()).unwrap();
        assert_eq!(claude.config_id.as_deref(), Some("mode"));
        assert_eq!(claude.resolve(AUTO), Some("auto"));
        assert_eq!(claude.resolve("plan"), Some("plan"));
        assert_eq!(claude.resolve("agent"), None, "another agent's id");
        let codex = Modes::from_session(&codex()).unwrap();
        assert_eq!(codex.resolve(AUTO), Some("agent"));
        assert_eq!(codex.resolve("read-only"), Some("read-only"));
        let manual = json!({"modes": {"currentModeId": "ask", "availableModes": [{"id": "ask", "name": "Ask"}, {"id": "code", "name": "Code"}]}});
        let legacy = Modes::from_session(&manual).unwrap();
        assert_eq!((legacy.config_id.as_deref(), legacy.current.as_deref()), (None, Some("ask")));
        assert_eq!(legacy.resolve(AUTO), None, "no Auto mode: the bot asks the user");
        assert!(Modes::from_session(&json!({"configOptions": [{"id": "model", "category": "model"}]})).is_none());
    }

    #[test]
    fn clients_get_the_auto_mode_first_and_under_codyncs_name() {
        let codex = Modes::from_session(&codex()).unwrap().catalog();
        let ids: Vec<&str> = codex["modes"].as_array().unwrap().iter().filter_map(|m| m["id"].as_str()).collect();
        assert_eq!(ids, ["auto", "read-only", "agent-full-access"]);
        assert_eq!(codex["modes"][0]["name"], "Auto review");
        assert_eq!(codex["currentModeId"], "auto");
        let claude = Modes::from_session(&claude()).unwrap().catalog();
        assert_eq!(claude["modes"][0]["id"], "auto");
        assert_eq!(claude["modes"][1]["name"], "Manual");
        assert_eq!(claude["currentModeId"], "default");
    }
}
