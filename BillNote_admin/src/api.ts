export type ApiResult<T> = { code: number; msg: string; data: T }

const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
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
