import type { BatchGenerationConfig, BatchItem, BatchTask, Model, Provider } from '../types'

export type MobilePreviewItem = {
  title: string
  source_url: string
  platform: string
  position?: number
}

export type MobileAppendPreview = {
  title: string
  items: MobilePreviewItem[]
  discovered_count: number
  skipped_count: number
}

export type MobileBatchConfig = BatchGenerationConfig

export type BatchWorkspaceProps = {
  providers: Provider[]
  models: Model[]
  onOpenReader: (payload: { taskId: string; title: string; markdown: string; sourceUrl?: string; exportPath?: string; backLabel?: string; returnTab?: 'home' | 'batch' | 'history' }) => void
}

export type BatchDetailPayload = {
  task: BatchTask
  items: BatchItem[]
}
