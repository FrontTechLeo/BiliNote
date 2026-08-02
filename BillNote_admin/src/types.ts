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

export type BatchTask = {
  id: string
  title: string
  source_type: string
  source_value?: string
  status: string
  total_count: number
  completed_count: number
  failed_count: number
  running_count: number
  progress: number
  created_at?: string
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
