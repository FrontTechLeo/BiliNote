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
