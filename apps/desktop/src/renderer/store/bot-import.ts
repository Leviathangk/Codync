import { Observable } from '../lib/observable'
import type { BotStore } from './bot-store'

/** What an export file holds, as the host reads it (`previewImport`). */
export interface ImportPlan {
  source: string
  bots: { key: string; name: string; description: string; skills: string[]; routines: unknown[]; memories: string[]; files: string | null }[]
  groups: { key: string; name: string; members: string[] }[]
  warnings: string[]
}

export interface ImportResult {
  bots: { key: string; id: string; name: string; skills: number; routines: number; memories: number; files: number }[]
  groups: { key: string; id: string; name: string }[]
  warnings: string[]
}

export type ImportStep =
  | { kind: 'start' }
  | { kind: 'review'; path: string; plan: ImportPlan; skipped: string[] }
  | { kind: 'done'; result: ImportResult }

/** Bringing bots over from another app's export file on this computer (the host's `import`). */
export class BotImport extends Observable {
  step: ImportStep = { kind: 'start' }
  /** Bot and group keys left out by the user. */
  off = new Set<string>()
  busy = false
  copied = false
  error: string | null = null

  constructor(private store: BotStore) { super() }

  /** The skill that writes the export in Grok Bot, on the clipboard. */
  async copySkill() {
    await this.run(async () => {
      const { text } = await (await this.store.ready()).call<{ text: string }>('importSkill', {})
      window.codync.app.copy(text)
      this.copied = true
    })
  }

  async choose() {
    const path = await window.codync.app.pickExport()
    if (!path) return
    await this.run(async () => {
      const read = await (await this.store.ready()).call<{ plan: ImportPlan; skipped: string[] }>('previewImport', { path }, 120_000)
      this.off = new Set()
      this.step = { kind: 'review', path, ...read }
    })
  }

  picked(key: string) {
    return !this.off.has(key)
  }

  toggle(key: string) {
    if (this.off.has(key)) this.off.delete(key)
    else this.off.add(key)
    this.changed()
  }

  /** A group can come along only with one of its bots. */
  groupAvailable(members: string[]) {
    return members.some((key) => this.picked(key))
  }

  get canImport() {
    return this.step.kind === 'review' && this.step.plan.bots.some((b) => this.picked(b.key)) && !this.busy
  }

  async importPicked() {
    const step = this.step
    if (step.kind !== 'review' || !this.canImport) return
    const bots = step.plan.bots.map((b) => b.key).filter((k) => this.picked(k))
    const groups = step.plan.groups.filter((g) => this.picked(g.key) && this.groupAvailable(g.members)).map((g) => g.key)
    await this.run(async () => {
      const result = await (await this.store.ready()).call<ImportResult>('importBots', { path: step.path, bots, groups }, 600_000)
      this.step = { kind: 'done', result }
    })
  }

  private async run(work: () => Promise<void>) {
    if (this.busy) return
    this.busy = true
    this.error = null
    this.changed()
    try {
      await work()
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e)
    } finally {
      this.busy = false
      this.changed()
    }
  }
}
