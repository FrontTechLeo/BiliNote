export type ApiResult<T> = { code: number; msg: string; data: T }

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const { apiBase } = await import('./ipc')
  const base = apiBase || '/api'
  const response = await fetch(`${base}${path}`, {
    cache: 'no-store',
    ...init,
    headers: init?.body instanceof FormData ? init.headers : { 'Content-Type': 'application/json', ...init?.headers },
  })
  const body = (await response.json()) as ApiResult<T>
  if (!response.ok || (body.code !== 0 && body.code !== 200)) throw new Error(body.msg || `请求失败（HTTP ${response.status}）`)
  return body.data
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, data?: unknown) => request<T>(path, { method: 'POST', body: JSON.stringify(data ?? {}) }),
  patch: <T>(path: string, data?: unknown) => request<T>(path, { method: 'PATCH', body: JSON.stringify(data) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  download: async (path: string, data?: unknown) => {
    const { apiBase } = await import('./ipc')
    const response = await fetch(`${apiBase || '/api'}${path}`, data === undefined ? undefined : {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
    })
    if (!response.ok) {
      // 导出失败时后端返回 FastAPI 的 {detail: ...}，尽量把原因带给用户
      let detail = `导出失败（HTTP ${response.status}）`
      try {
        const body = await response.json()
        if (typeof body?.detail === 'string') detail = body.detail
        else if (typeof body?.msg === 'string') detail = body.msg
      } catch { /* 非 JSON 响应保留默认提示 */ }
      throw new Error(detail)
    }
    return response.blob()
  },
}

export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

export function sanitizeFilename(title: string, fallback = 'note') {
  return title.replace(/[\\/:*?"<>|]/g, '-').trim() || fallback
}

/**
 * 导出下载内容到磁盘：Tauri 里弹原生「另存为」选目录后写入；
 * 返回落盘完整路径；用户取消返回 null；纯浏览器开发模式走网页下载，返回 ''。
 */
export async function saveExport(blob: Blob, baseTitle: string, forcedExtension?: 'zip'): Promise<string | null> {
  const { saveBlobToDisk } = await import('./ipc')
  const extension = forcedExtension ?? (blob.type.includes('zip') ? 'zip' : 'md')
  const filename = `${sanitizeFilename(baseTitle)}.${extension}`
  const filters = [extension === 'zip'
    ? { name: 'ZIP 压缩包', extensions: ['zip'] }
    : { name: 'Markdown 文档', extensions: ['md'] }]
  const savedPath = await saveBlobToDisk(filename, blob, filters)
  if (savedPath === '') saveBlob(blob, filename)
  return savedPath
}

// ---------- 类型 ----------

// 供应商 id 可能是数字（全新库）或字符串（历史库如 'openai'），统一原样传递不强转
// enabled 可能缺失（旧版本接口），缺省按「启用」处理
export type Provider = {
  id: number | string
  name: string
  type: string
  base_url: string
  enabled?: number | null
}

/**
 * 供应商是否可用于生成任务。
 * enabled 缺省或为 1 视为可用；显式为 0 视为禁用。
 */
export function isProviderEnabled(provider: Provider): boolean {
  const enabled = provider.enabled
  return enabled === undefined || enabled === null || Number(enabled) !== 0
}

export type Model = { id: number | string; model_name: string; provider_id: number | string }

/**
 * 把 id / provider_id 归一化成字符串再比较。
 *
 * models.provider_id 在 SQLite 里被声明为 INTEGER，纯数字 id 会被存成整型，
 * 而 providers.id 是字符串；同时 <option value> 经 DOM 取回后恒为字符串。
 * 不做归一化就会出现「同一个值，比较不相等」，导致 select 匹配不到任何 option、
 * 选中态丢失（下拉仍停在 placeholder）。
 */
export function sameId(a: number | string | null | undefined, b: number | string | null | undefined): boolean {
  return String(a ?? '') === String(b ?? '')
}

// 供应商远端模型（OpenAI 兼容 /v1/models 返回条目）
export type RemoteModel = { id: string; owned_by?: string; object?: string }

/**
 * 实时拉取供应商在远端可用的模型列表 —— 对齐 admin 的处理方式：
 * GET /model_list/{provider_id}，后端走 OpenAI SDK list_models。
 * 手打名称容易写出远端不存在的模型，连通性测试与生成都会失败；
 * 后端异常时该接口返回 []，这里统一归一成数组。
 */
export async function fetchProviderModels(providerId: number | string): Promise<RemoteModel[]> {
  const data = await api.get<{ models?: RemoteModel[] }>(`/model_list/${providerId}`)
  return Array.isArray(data) ? [] : (data.models ?? [])
}

export type SingleTaskStatus = {
  task_id: string
  status: string
  message?: string
  source_url?: string
  platform?: string
  result?: {
    markdown?: string
    audio_meta?: { title?: string; duration?: number }
  }
}

export type SingleTaskHistory = {
  task_id: string
  title?: string
  source_url?: string
  platform?: string
  status: string
  created_at?: string
  has_result?: boolean
  has_images?: boolean
  export_format?: string
  can_regenerate?: boolean
}

export type BatchTask = {
  id: string
  title: string
  source_type: string
  source_value?: string
  status: string
  progress?: number
  total_count: number
  completed_count: number
  failed_count: number
  canceled_count?: number
  created_at?: string
  config?: Record<string, unknown>
}

export type BatchItem = {
  id: string
  task_id?: string
  title: string
  source_url: string
  platform: string
  status: string
  message?: string
  attempts?: number
  position?: number
}

export type PreviewItem = { title: string; source_url: string; platform: string; position?: number }

export type LocalPreview = {
  preview_id: string
  title: string
  items: PreviewItem[]
  discovered_count: number
  duplicate_count?: number
  skipped_count?: number
}

export type LocalInspectItem = {
  path: string
  title: string
  exists: boolean
  is_video: boolean
  size_bytes: number | null
  duration_seconds: number | null
  error?: string
}

export type GenerateOptions = {
  quality: string
  style: string
  format: string[]
  video_understanding: boolean
  image_compression: string
  video_interval: number
  grid_size: [number, number]
  extras?: string
}

export const formats = [
  { label: '目录', value: 'toc' }, { label: '原片跳转', value: 'link' },
  { label: '原片截图', value: 'screenshot' }, { label: 'AI 总结', value: 'summary' },
]
export const styles = [
  { label: '精简', value: 'minimal' }, { label: '详细', value: 'detailed' },
  { label: '教程', value: 'tutorial' }, { label: '学术', value: 'academic' },
  { label: '任务导向', value: 'task_oriented' }, { label: '会议纪要', value: 'meeting_minutes' },
]
export const imageCompressionOptions = ['high', 'medium', 'low']

export const statusColor: Record<string, string> = {
  SUCCESS: 'ok', FAILED: 'bad', CANCELED: 'dim', PARTIAL_FAILED: 'warn', RUNNING: 'busy',
  PENDING: 'dim', PARSING: 'busy', DOWNLOADING: 'busy', TRANSCRIBING: 'busy',
  SUMMARIZING: 'busy', FORMATTING: 'busy', SAVING: 'busy',
}

export const terminalStatuses = ['SUCCESS', 'FAILED', 'CANCELED']

export function formatBytes(bytes: number | null | undefined): string {
  if (!Number.isFinite(bytes as number) || (bytes as number) <= 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes as number) / Math.log(1024)), units.length - 1)
  const value = (bytes as number) / 1024 ** index
  return `${value >= 100 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`
}

export function formatDuration(seconds: number | null | undefined): string {
  if (!Number.isFinite(seconds as number) || (seconds as number) <= 0) return '—'
  const total = Math.round(seconds as number)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`
}

export function naturalCompare(a: string, b: string): number {
  return new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' }).compare(a, b)
}
