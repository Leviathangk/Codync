import Foundation
import Testing
@testable import CodyncKit

private func catalog(_ json: String) throws -> AgentModels {
    try JSONDecoder().decode(AgentModels.self, from: Data(json.utf8))
}

@Test func newBotsStartInTheAgentsAutoModeAndAsk() throws {
    let draft = BotDraft(name: "New")
    #expect((draft.permission, draft.mode) == ("ask", "auto"))
    let body = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(draft)) as? [String: Any])
    #expect(body["mode"] as? String == "auto")
}

@Test func approvalsListTheAgentsModesThenApproveAutomatically() throws {
    let codex = try catalog(#"{"models":[],"modes":[{"id":"auto","name":"Auto review","description":"Only ask for actions detected as potentially unsafe"},{"id":"read-only","name":"Read-only"}],"currentModeId":"auto"}"#)
    let choices = ApprovalChoice.all(codex)
    #expect(choices.map(\.label) == ["Auto review", "Read-only", "Approve automatically"])
    #expect(choices.first?.note == "Only ask for actions detected as potentially unsafe")

    var draft = BotDraft(name: "Bot", backend: "codex")
    #expect(ApprovalChoice.selected(permission: draft.permission, mode: draft.mode, in: choices, catalog: codex)?.label == "Auto review")
    // An unset mode is the one the agent starts in.
    #expect(ApprovalChoice.selected(permission: "ask", mode: nil, in: choices, catalog: codex)?.label == "Auto review")
    draft.setApproval(choices[1].id)
    #expect((draft.permission, draft.mode) == ("ask", "read-only"))
    draft.setApproval(ApprovalChoice.approve)
    #expect((draft.permission, draft.mode) == ("auto", nil))
    // Approve automatically clears the mode on the host too.
    draft.id = "b"
    let body = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(BotUpdate(draft: draft))) as? [String: Any])
    #expect(body["mode"] is NSNull)
    #expect(ApprovalChoice.selected(permission: draft.permission, mode: draft.mode, in: choices, catalog: codex)?.label == "Approve automatically")
}

@Test func anAgentWithoutAutoAsksAndOneStillLoadingOffersAuto() throws {
    let plain = try catalog(#"{"models":[],"modes":[]}"#)
    let choices = ApprovalChoice.all(plain)
    #expect(choices.map(\.label) == ["Ask me", "Approve automatically"])
    #expect(ApprovalChoice.selected(permission: "ask", mode: "auto", in: choices, catalog: plain)?.label == "Ask me")
    #expect(ApprovalChoice.all(nil).map(\.label) == ["Auto", "Ask me", "Approve automatically"])

    // Another agent's ids mean nothing: a picked mode becomes its Auto mode.
    var draft = BotDraft(name: "Bot")
    draft.mode = "plan"
    draft.setAgent("codex")
    #expect(draft.mode == "auto")
}
