import Foundation

/// One of the modes an agent works in (Claude's Manual / Accept edits / Plan / Auto, Codex's Read-only / Auto review…),
/// as `agentModels` lists them: its Auto mode first and listed as `auto`.
public struct AgentMode: Decodable, Hashable, Sendable {
    /// Codync's id for the agent's Auto mode, whatever the agent calls it: new bots start there.
    public static let auto = "auto"

    public let id: String
    public let name: String
    public let description: String?
}

/// A bot's Approvals choice: the agent's modes, then Codync approving everything. An agent without modes offers
/// Ask me instead; while its list loads (or from an older host), Auto and Ask me.
public struct ApprovalChoice: Identifiable, Hashable, Sendable {
    public static let ask = "ask"
    public static let approve = "approve"

    public let id: String
    public let label: String
    /// The agent's own words for its mode, untranslated.
    public let note: String?
    /// What Codync does, to translate.
    public let detail: String

    /// The choices for an agent, from its model list (nil while it loads).
    public static func all(_ catalog: AgentModels?) -> [ApprovalChoice] {
        let ask = ApprovalChoice(id: ask, label: "Ask me", note: nil,
                                 detail: "You'll get an approval card and a notification whenever the agent asks.")
        let approve = ApprovalChoice(id: approve, label: "Approve automatically", note: nil,
                                     detail: "Tool requests are approved automatically. Your agent's own settings (like Claude Code's permission rules) still apply.")
        guard let modes = catalog?.modes else {
            let auto = ApprovalChoice(id: modeId(AgentMode.auto), label: "Auto", note: nil,
                                      detail: "The agent decides what's safe and asks you about the rest.")
            return [auto, ask, approve]
        }
        if modes.isEmpty { return [ask, approve] }
        return modes.map {
            ApprovalChoice(id: modeId($0.id), label: $0.name, note: $0.description,
                           detail: "Anything the agent still asks about comes to you as an approval card.")
        } + [approve]
    }

    /// The choice a bot's setting shows as: an unset mode is the one the agent starts in.
    public static func selected(permission: String, mode: String?, in choices: [ApprovalChoice], catalog: AgentModels?) -> ApprovalChoice? {
        if permission == "auto" { return choices.first { $0.id == approve } }
        if let mode = mode ?? catalog?.currentModeId, let match = choices.first(where: { $0.id == modeId(mode) }) { return match }
        return choices.first { $0.id == ask } ?? choices.first
    }

    static func modeId(_ mode: String) -> String { "mode:\(mode)" }
}

public extension BotDraft {
    /// Picks an Approvals choice (`ApprovalChoice`).
    mutating func setApproval(_ id: String) {
        switch id {
        case ApprovalChoice.approve: (permission, mode) = ("auto", nil)
        case ApprovalChoice.ask: (permission, mode) = ("ask", nil)
        default: (permission, mode) = ("ask", String(id.dropFirst("mode:".count)))
        }
    }
}
