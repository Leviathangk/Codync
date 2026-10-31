import CodyncKit
import OSLog
import SwiftUI

private let log = Logger(subsystem: "com.pokai.Codync", category: "Bots")

/// Permissions: the modes the bot's agent offers, its Auto mode first, then approving everything (`ApprovalChoice`).
struct ApprovalPicker: View {
    @Binding var draft: BotDraft
    @Environment(BotStore.self) private var store
    @State private var catalog: AgentModels?

    private var choices: [ApprovalChoice] { ApprovalChoice.all(catalog) }
    private var selected: ApprovalChoice? {
        ApprovalChoice.selected(permission: draft.permission, mode: draft.mode, in: choices, catalog: catalog)
    }

    var body: some View {
        ValueRow("Permissions", detail: selected.map { [$0.note, L10n.tr($0.detail)].compactMap { $0 }.joined(separator: " ") }) {
            ChoicePicker(selection: Binding(get: { selected?.id ?? "" }, set: { draft.setApproval($0) }),
                         options: choices.map { ($0.id, $0.label) })
        }
        .task(id: "\(store.computer.id)/\(draft.backend)/\(store.connection == .online)") { await load() }
    }

    /// The agent's modes come with its model list (the host shares one asked for from several places at once).
    private func load() async {
        catalog = nil
        guard draft.backend != "custom", store.connection == .online, let client = store.client else { return }
        do {
            let response = try await client.agentModels(draft.backend)
            try Task.checkCancellation()
            catalog = response
        } catch {
            // The model picker says why; Auto, Ask me and Approve automatically stay on offer meanwhile.
            log.info("no agent modes for \(draft.backend, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }
}
