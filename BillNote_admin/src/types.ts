export type BatchItem = {
  id: string
  batch_id: string
  task_id?: string
  title: string
  source_url: string
  platform: string
  position: number
  status: string
  stage?: string
  message?: string
  attempts: number
}

export type ImageCompression = 'high' | 'medium' | 'original'

export const imageCompressionOptions: Array<{ value: ImageCompression; label: string }> = [
  { value: 'high', label: '高压缩（JPEG/720p）' },
  { value: 'medium', label: '中等（JPEG）' },
  { value: 'original', label: '最高/原图（PNG）' },
]

export function normaliseImageCompression(value: unknown): ImageCompression {
  if (value === 'medium') return 'medium'
  if (value === 'original' || value === 'highest' || value === 'lossless' || value === 'none') return 'original'
  return 'high'
}

export type BatchTask = {
  id: string
  title: string
  source_type: string
  source_value?: string
  status: string
  total_count: number
  completed_count: number
  failed_count: number
  canceled_count: number
  running_count: number
  progress: number
  created_at?: string
  config?: BatchGenerationConfig
}

export type BatchGenerationConfig = {
  quality: string
  model_name: string
  provider_id: string
  screenshot: boolean
  link: boolean
  format: string[]
  style: string
  extras?: string
  video_understanding: boolean
  video_interval: number
  grid_size: number[]
  image_compression: ImageCompression
}

export type Provider = { id: string; name: string; base_url: string; type: string; enabled?: number }
export type Model = { id: number; provider_id: string; model_name: string }

export type SingleTaskResult = {
  markdown: string
  audio_meta?: { title?: string; [key: string]: unknown }
  [key: string]: unknown
}

export type SingleTaskStatus = {
  task_id: string
  status: string
  message?: string
  result?: SingleTaskResult
  source_url?: string
  platform?: string
}

export type SingleTaskHistory = {
  task_id: string
  title: string
  platform: string
  source_url?: string
  status: string
  message?: string
  created_at?: string
  has_result: boolean
  has_images: boolean
  export_format: 'md' | 'zip'
  can_regenerate: boolean
}
