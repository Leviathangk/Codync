import type { BotDraft } from './models'

/** Codync's id for the agent's Auto mode, whatever the agent calls it: new bots start there. */
export const AUTO_MODE = 'auto'

/** One of the modes an agent works in (Claude's Manual / Plan / Auto, Codex's Read-only / Auto review…). */
export interface AgentMode {
  id: string
  name: string
  description?: string | null
}

/** What an agent's model list says about its modes (`agentModels`); null while it loads. */
export interface ModeCatalog {
  modes?: AgentMode[] | null
  currentModeId?: string | null
}

/** A bot's Approvals choice. `note` is the agent's own words, untranslated; `detail` is Codync's, to translate. */
export interface ApprovalChoice {
  id: string
  label: string
  note: string | null
  detail: string
}

const ASK = 'ask'
const APPROVE = 'approve'
const modeId = (mode: string) => `mode:${mode}`

/**
 * The agent's modes, its Auto mode first, then Codync approving everything. An agent without modes offers Ask me
 * instead; while its list loads (or from an older host), Auto and Ask me.
 */
export function approvalChoices(catalog: ModeCatalog | null): ApprovalChoice[] {
  const ask = { id: ASK, label: 'Ask me', note: null, detail: "You'll get an approval card and a notification whenever the agent asks." }
  const approve = {
    id: APPROVE, label: 'Approve automatically', note: null,
    detail: "Tool requests are approved automatically. Your agent's own settings (like Claude Code's permission rules) still apply.",
  }
  const modes = catalog?.modes
  if (!modes) {
    return [{ id: modeId(AUTO_MODE), label: 'Auto', note: null, detail: "The agent decides what's safe and asks you about the rest." }, ask, approve]
  }
  if (!modes.length) return [ask, approve]
  const asks = 'Anything the agent still asks about comes to you as an approval card.'
  return [...modes.map((m) => ({ id: modeId(m.id), label: m.name, note: m.description ?? null, detail: asks })), approve]
}

/** The choice a bot's setting shows as: an unset mode is the one the agent starts in. */
export function selectedApproval(draft: Pick<BotDraft, 'permission' | 'mode'>, choices: ApprovalChoice[], catalog: ModeCatalog | null) {
  if (draft.permission === 'auto') return choices.find((c) => c.id === APPROVE) ?? null
  const mode = draft.mode ?? catalog?.currentModeId
  return (mode ? choices.find((c) => c.id === modeId(mode)) : undefined) ?? choices.find((c) => c.id === ASK) ?? choices[0] ?? null
}

/** The draft with an Approvals choice picked. */
export function withApproval<D extends Pick<BotDraft, 'permission' | 'mode'>>(draft: D, id: string): D {
  if (id === APPROVE) return { ...draft, permission: 'auto', mode: null }
  if (id === ASK) return { ...draft, permission: 'ask', mode: null }
  return { ...draft, permission: 'ask', mode: id.slice('mode:'.length) }
}
