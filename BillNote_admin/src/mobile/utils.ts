import { normaliseImageCompression } from '../types'
import type { BatchGenerationConfig } from '../types'

export const batchStyles = [
  { value: 'minimal', label: '精简' },
  { value: 'detailed', label: '详细' },
  { value: 'tutorial', label: '教程' },
  { value: 'meeting_minutes', label: '纪要' },
]

export const batchFormats = [
  { value: 'toc', label: '章节目录' },
  { value: 'link', label: '时间跳转' },
  { value: 'screenshot', label: '关键截图' },
  { value: 'summary', label: 'AI 总结' },
]

export const defaultBatchConfig: BatchGenerationConfig = {
  quality: 'medium', model_name: '', provider_id: '', screenshot: false, link: false,
  format: ['toc', 'summary'], style: 'tutorial', extras: '', video_understanding: false,
  video_interval: 6, grid_size: [2, 2], image_compression: 'high',
}

export function normaliseBatchConfig(config?: Partial<BatchGenerationConfig>): BatchGenerationConfig {
  const rawGrid: unknown = config?.grid_size
  const grid = typeof rawGrid === 'string'
    ? rawGrid.split(',').map((item) => Number(item.trim())).filter(Boolean)
    : rawGrid
  return {
    ...defaultBatchConfig,
    ...config,
    format: Array.isArray(config?.format) ? config.format : defaultBatchConfig.format,
    grid_size: Array.isArray(grid) && grid.length === 2 ? grid : [2, 2],
    image_compression: normaliseImageCompression(config?.image_compression),
  }
}

export function configForApi(config: BatchGenerationConfig) {
  const normalised = normaliseBatchConfig(config)
  return {
    ...normalised,
    link: normalised.format.includes('link'),
    screenshot: normalised.format.includes('screenshot'),
  }
}

export function sortFilesNaturally(files: File[]) {
  const collator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })
  return [...files].sort((left, right) => collator.compare(
    (left as File & { webkitRelativePath?: string }).webkitRelativePath || left.name,
    (right as File & { webkitRelativePath?: string }).webkitRelativePath || right.name,
  ))
}

export function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / 1024 ** index
  return `${value >= 100 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`
}

export function batchStatusLabel(status?: string) {
  return ({ SUCCESS: '已完成', FAILED: '全部失败', CANCELED: '已取消', PARTIAL_FAILED: '部分失败', RUNNING: '处理中', PENDING: '排队中' } as Record<string, string>)[status || ''] || status || '未知状态'
}

export function itemStatusLabel(status?: string) {
  return ({ SUCCESS: '已完成', FAILED: '处理失败', CANCELED: '已取消', RUNNING: '处理中', PENDING: '排队中' } as Record<string, string>)[status || ''] || status || '未知状态'
}

export function isTerminalBatch(status?: string) {
  return ['SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELED'].includes(status || '')
}

export function canAppendBatch(status?: string) {
  return ['PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELED'].includes(status || '')
}
