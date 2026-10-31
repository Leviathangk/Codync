import { test } from 'node:test'
import assert from 'node:assert/strict'
import { approvalChoices, selectedApproval, withApproval } from './approvals.ts'
import { withBackend } from './fallbacks.ts'
import type { BotDraft } from './models.ts'

const codex = {
  modes: [{ id: 'auto', name: 'Auto review', description: 'Only ask for actions detected as potentially unsafe' }, { id: 'read-only', name: 'Read-only' }],
  currentModeId: 'auto',
}

test("approvals list the agent's modes, Auto first, then approve automatically", () => {
  const choices = approvalChoices(codex)
  assert.deepEqual(choices.map((c) => c.label), ['Auto review', 'Read-only', 'Approve automatically'])
  assert.equal(choices[0]!.note, 'Only ask for actions detected as potentially unsafe')
  const draft = { permission: 'ask', mode: 'auto' as string | null }
  assert.equal(selectedApproval(draft, choices, codex)?.label, 'Auto review')
  assert.equal(selectedApproval({ permission: 'ask', mode: null }, choices, codex)?.label, 'Auto review', "an unset mode is the agent's start")
  assert.deepEqual(withApproval(draft, choices[1]!.id), { permission: 'ask', mode: 'read-only' })
  const approve = withApproval(draft, choices[2]!.id)
  assert.deepEqual(approve, { permission: 'auto', mode: null })
  assert.equal(JSON.stringify(approve), '{"permission":"auto","mode":null}', 'null reaches the host and clears it')
  assert.equal(selectedApproval(approve, choices, codex)?.label, 'Approve automatically')
})

test('an agent without Auto asks, and one still loading offers Auto', () => {
  const plain = { modes: [] }
  const choices = approvalChoices(plain)
  assert.deepEqual(choices.map((c) => c.label), ['Ask me', 'Approve automatically'])
  assert.equal(selectedApproval({ permission: 'ask', mode: 'auto' }, choices, plain)?.label, 'Ask me')
  assert.deepEqual(approvalChoices(null).map((c) => c.label), ['Auto', 'Ask me', 'Approve automatically'])
})

test("another agent's mode ids mean nothing: a picked mode becomes its Auto mode", () => {
  const draft: BotDraft = { name: 'B', description: '', avatarColor: 'red', avatarShape: 'blob', backend: 'claude', cwd: '', permission: 'ask', mode: 'plan' }
  assert.equal(withBackend(draft, 'codex').mode, 'auto')
  assert.equal(withBackend({ ...draft, mode: null }, 'codex').mode, null)
})
