import { invoke } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { open as dialogOpen } from '@tauri-apps/plugin-dialog'

export type BackendInfo = {
  port: number
  base_url: string
  lan_base_urls: string[]
  data_dir: string
  sidecar_mode: boolean
}

export type DesktopSettings = {
  port: number | null
  data_dir: string | null
}

const isTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window

export let backendInfo: BackendInfo | null = null
export let apiBase = import.meta.env.VITE_API_BASE_URL as string | undefined

export function absoluteAssetUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) return path
  const origin = apiBase && /^https?:\/\//.test(apiBase)
    ? new URL(apiBase).origin
    : backendInfo?.base_url.replace(/\/$/, '') || 'http://127.0.0.1:8483'
  return `${origin}${path.startsWith('/') ? path : `/${path}`}`
}

export async function initBackend(): Promise<BackendInfo> {
  if (!isTauri) {
    // 纯浏览器开发模式：直接连本地 backend
    backendInfo = {
      port: 8483,
      base_url: 'http://127.0.0.1:8483/',
      lan_base_urls: [],
      data_dir: '',
      sidecar_mode: false,
    }
  } else {
    backendInfo = await invoke<BackendInfo>('get_backend_info')
  }
  apiBase = `${backendInfo.base_url.replace(/\/$/, '')}/api`
  return backendInfo
}

export function onBackendEvent(handlers: {
  ready?: (port: number) => void
  failed?: (message: string) => void
  warning?: (payload: unknown) => void
}) {
  if (!isTauri) return () => undefined
  const unlistens = [
    listen<number>('backend-ready', (event) => handlers.ready?.(event.payload)),
    listen<string>('backend-failed', (event) => handlers.failed?.(event.payload)),
    listen<unknown>('backend-warning', (event) => handlers.warning?.(event.payload)),
  ]
  return () => { unlistens.forEach((p) => void p.then((fn) => fn())) }
}

export const desktop = {
  getInfo: () => invoke<BackendInfo>('get_backend_info'),
  getSettings: () => invoke<DesktopSettings>('get_desktop_settings'),
  saveSettings: (settings: DesktopSettings) => invoke<void>('save_desktop_settings', { settings }),
  openDataDir: () => invoke<void>('open_data_dir'),
  openPath: (path: string) => invoke<void>('open_path', { path }),
  restartBackend: () => invoke<void>('restart_backend_sidecar'),
  diagnostics: async () => {
    if (!isTauri) return null
    return invoke<Record<string, unknown>>('get_install_path_diagnostics')
  },
  pickFiles: async () => {
    if (!isTauri) return null
    const selected = await dialogOpen({
      multiple: true,
      title: '选择本地视频',
      filters: [{ name: '视频文件', extensions: ['mp4', 'mkv', 'mov', 'avi', 'webm', 'm4v'] }],
    })
    return normalizeSelection(selected)
  },
  pickFolder: async () => {
    if (!isTauri) return null
    const selected = await dialogOpen({ directory: true, title: '选择视频文件夹' })
    return normalizeSelection(selected)
  },
}

function normalizeSelection(selected: string | string[] | null): string[] | null {
  if (!selected) return null
  return Array.isArray(selected) ? selected : [selected]
}
