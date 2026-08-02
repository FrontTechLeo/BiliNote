export type ApiResult<T> = { code: number; msg: string; data: T }
export type UploadProgress = { loaded: number; total: number; percent: number }

const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    cache: 'no-store',
    ...init,
    headers: init?.body instanceof FormData ? init.headers : { 'Content-Type': 'application/json', ...init?.headers },
  })
  const body = (await response.json()) as ApiResult<T>
  if (!response.ok || body.code !== 0) throw new Error(body.msg || '请求失败')
  return body.data
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, data?: unknown) => request<T>(path, { method: 'POST', body: JSON.stringify(data) }),
  upload: <T>(path: string, data: FormData) => request<T>(path, { method: 'POST', body: data }),
  uploadWithProgress: <T>(
    path: string,
    data: FormData,
    onProgress: (progress: UploadProgress) => void,
    signal?: AbortSignal,
  ) => new Promise<T>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    const abort = () => xhr.abort()
    xhr.open('POST', `${baseUrl}${path}`)
    xhr.responseType = 'json'
    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable) return
      onProgress({
        loaded: event.loaded,
        total: event.total,
        percent: Math.min(100, Math.round(event.loaded / event.total * 100)),
      })
    }
    xhr.onload = () => {
      signal?.removeEventListener('abort', abort)
      const body = xhr.response as ApiResult<T> | null
      if (xhr.status >= 200 && xhr.status < 300 && body?.code === 0) resolve(body.data)
      else reject(new Error(body?.msg || `上传失败（HTTP ${xhr.status}）`))
    }
    xhr.onerror = () => {
      signal?.removeEventListener('abort', abort)
      reject(new Error('上传失败，请检查服务连接'))
    }
    xhr.onabort = () => {
      signal?.removeEventListener('abort', abort)
      reject(new DOMException('上传已取消', 'AbortError'))
    }
    signal?.addEventListener('abort', abort, { once: true })
    if (signal?.aborted) {
      xhr.abort()
      return
    }
    xhr.send(data)
  }),
  download: async (path: string, data?: unknown) => {
    const response = await fetch(`${baseUrl}${path}`, data === undefined ? undefined : {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
    })
    if (!response.ok) throw new Error('导出失败')
    return response.blob()
  },
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}
