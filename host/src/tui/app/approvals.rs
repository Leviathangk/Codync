//! The bot form's Approvals: the agent's modes, its Auto mode first (`agent::modes`), then Codync approving
//! everything. An agent without modes offers Ask me instead; one whose list hasn't come yet, Auto and Ask me.

use super::{App, Form, cycle};
use crate::agent::modes::AUTO;
use serde_json::Value;

/// The modes an agent's model list came with (`agentModels`).
#[derive(Clone, Debug, Default)]
pub struct AgentModes {
    /// `(id, name)`, Auto first and listed as `auto`.
    pub list: Vec<(String, String)>,
    /// The mode its sessions start in.
    pub current: Option<String>,
}

impl AgentModes {
    pub fn parse(v: &Value) -> Self {
        let list = v["modes"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|m| {
                let id = m["id"].as_str()?;
                Some((id.to_owned(), m["name"].as_str().unwrap_or(id).to_owned()))
            })
            .collect();
        Self { list, current: v["currentModeId"].as_str().map(str::to_owned) }
    }
}

/// One Approvals choice: the mode to work in and whether Codync approves what still asks.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Approval {
    pub mode: Option<String>,
    pub auto: bool,
    pub label: String,
}

impl Approval {
    fn new(mode: Option<&str>, auto: bool, label: &str) -> Self {
        Self { mode: mode.map(str::to_owned), auto, label: label.to_owned() }
    }
}

impl App {
    /// What the form offers for `backend`.
    pub fn approvals(&self, backend: &str) -> Vec<Approval> {
        let ask = Approval::new(None, false, "Ask me");
        let approve = Approval::new(None, true, "Approve automatically");
        match self.modes.get(backend) {
            None => vec![Approval::new(Some(AUTO), false, "Auto"), ask, approve],
            Some(m) if m.list.is_empty() => vec![ask, approve],
            Some(m) => m
                .list
                .iter()
                .map(|(id, name)| Approval::new(Some(id), false, name))
                .chain(std::iter::once(approve))
                .collect(),
        }
    }

    /// The choice the form's setting shows as: an unset mode is the one the agent starts in.
    pub fn approval(&self, f: &Form) -> Approval {
        let choices = self.approvals(&f.backend);
        let current = self.modes.get(&f.backend).and_then(|m| m.current.as_deref());
        let mode = f.mode.as_deref().or(current);
        let pick = |c: &&Approval| {
            if f.auto {
                c.auto
            } else {
                !c.auto && (c.mode.as_deref() == mode || (c.mode.is_none() && f.mode.is_none()))
            }
        };
        let first = choices.iter().find(|c| !c.auto).cloned();
        choices.iter().find(pick).cloned().or(first).unwrap_or_else(|| Approval::new(None, f.auto, "Ask me"))
    }

    pub(super) fn cycle_approval(&self, f: &mut Form, d: isize) {
        let choices = self.approvals(&f.backend);
        let now = self.approval(f);
        let i = choices.iter().position(|c| *c == now).unwrap_or(0);
        let next = &choices[cycle(i, d, choices.len())];
        (f.mode, f.auto) = (next.mode.clone(), next.auto);
    }
}

#[cfg(test)]
mod tests {
    use super::super::{Overlay, test_app};
    use super::*;
    use serde_json::json;

    fn form(app: &mut App, bot: &Value) -> Form {
        app.bots.insert("b".into(), super::super::Bot::parse(bot).expect("a bot"));
        app.selected = Some("b".into());
        app.edit_bot();
        let Some(Overlay::Form(f)) = app.overlays.pop() else { panic!("the editor opens") };
        *f
    }

    #[tokio::test]
    async fn approvals_list_the_agents_modes_then_approve_automatically() {
        let mut app = test_app();
        let codex = json!({"modes": [{"id": "auto", "name": "Auto review"}, {"id": "read-only", "name": "Read-only"}],
            "currentModeId": "auto"});
        app.modes.insert("codex".into(), AgentModes::parse(&codex));
        let mut f = form(&mut app, &json!({"id": "b", "backend": "codex", "permission": "ask", "mode": "auto"}));
        assert_eq!(app.approval(&f).label, "Auto review");
        let labels: Vec<String> = app.approvals("codex").into_iter().map(|c| c.label).collect();
        assert_eq!(labels, ["Auto review", "Read-only", "Approve automatically"]);
        app.cycle_approval(&mut f, 1);
        assert_eq!((f.mode.as_deref(), f.auto), (Some("read-only"), false));
        app.cycle_approval(&mut f, 1);
        assert_eq!((f.mode.as_deref(), f.auto), (None, true));
        assert_eq!(f.changes(), json!({"permission": "auto", "mode": null}));

        // An agent without modes asks about everything; one whose list hasn't come yet offers Auto too.
        app.modes.insert("gemini".into(), AgentModes::default());
        let f = form(&mut app, &json!({"id": "b", "backend": "gemini", "permission": "ask", "mode": "auto"}));
        assert_eq!(app.approval(&f).label, "Ask me");
        assert_eq!(app.approvals("other").len(), 3);
    }
}
