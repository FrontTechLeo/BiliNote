import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { api, saveExport, statusColor } from './api'
import MarkdownReader from './markdown'

// ---------- Toast ----------

type Toast = { id: number; kind: 'ok' | 'bad' | 'warn'; text: string }
type ToastContextValue = (kind: Toast['kind'], text: string) => void

const ToastContext = createContext<ToastContextValue>(() => undefined)

export function useToast() {
  return useContext(ToastContext)
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const seq = useRef(0)
  const push = useCallback<ToastContextValue>((kind, text) => {
    const id = ++seq.current
    setToasts((current) => [...current, { id, kind, text }])
    window.setTimeout(() => setToasts((current) => current.filter((t) => t.id !== id)), 3600)
  }, [])
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toast-stack" role="status">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast ${toast.kind}`}>{toast.text}</div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

// ---------- 基础组件 ----------

export function StatusTag({ value }: { value?: string }) {
  const color = statusColor[value || ''] || 'dim'
  return <span className={`status-tag ${color}`}>{value || 'UNKNOWN'}</span>
}

export function Spinner({ label }: { label?: string }) {
  return (
    <span className="spinner-wrap">
      <span className="spinner" />
      {label && <span>{label}</span>}
    </span>
  )
}

export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="empty-state">
      <div className="empty-title">{title}</div>
      {hint && <div className="empty-hint">{hint}</div>}
    </div>
  )
}

export function Modal({ title, width = 720, onClose, children }: {
  title: string
  width?: number
  onClose: () => void
  children: ReactNode
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="modal-mask" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose() }}>
      <div className="modal" style={{ width: `min(${width}px, calc(100vw - 48px))` }}>
        <header className="modal-header">
          <strong>{title}</strong>
          <button type="button" className="icon-btn" aria-label="关闭" onClick={onClose}>✕</button>
        </header>
        <div className="modal-body">{children}</div>
      </div>
    </div>
  )
}

export function Field({ label, hint, required, children }: { label: string; hint?: string; required?: boolean; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}{required && <em>*</em>}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  )
}

export function ProgressBar({ percent }: { percent?: number }) {
  const value = Math.max(0, Math.min(100, Math.round(percent ?? 0)))
  return (
    <div className="progress">
      <div className="progress-fill" style={{ width: `${value}%` }} />
    </div>
  )
}

export function Checkbox({ checked, onChange, disabled, children }: {
  checked: boolean
  onChange: (next: boolean) => void
  disabled?: boolean
  children: ReactNode
}) {
  return (
    <label className={`chk ${disabled ? 'disabled' : ''}`}>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
      />
      <span>{children}</span>
    </label>
  )
}

// ---------- 确认弹窗（WebView 不支持原生 confirm/prompt，必须应用内实现） ----------

type ConfirmOptions = { title: string; content?: string; danger?: boolean; okText?: string; cancelText?: string }
type ConfirmContextValue = (options: ConfirmOptions) => Promise<boolean>

const ConfirmContext = createContext<ConfirmContextValue>(async () => false)

export function useConfirm() {
  return useContext(ConfirmContext)
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [options, setOptions] = useState<ConfirmOptions | null>(null)
  const resolveRef = useRef<((ok: boolean) => void) | null>(null)

  const confirm = useCallback<ConfirmContextValue>((next) => {
    return new Promise<boolean>((resolve) => {
      resolveRef.current = resolve
      setOptions(next)
    })
  }, [])

  const settle = (ok: boolean) => {
    resolveRef.current?.(ok)
    resolveRef.current = null
    setOptions(null)
  }

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {options && (
        <Modal title={options.title} width={460} onClose={() => settle(false)}>
          {options.content && <p className="confirm-content">{options.content}</p>}
          <div className="row end">
            <button type="button" className="btn ghost" onClick={() => settle(false)}>
              {options.cancelText || '取消'}
            </button>
            <button
              type="button"
              className={`btn ${options.danger ? 'danger' : 'primary'}`}
              onClick={() => settle(true)}
            >
              {options.okText || '确定'}
            </button>
          </div>
        </Modal>
      )}
    </ConfirmContext.Provider>
  )
}

// ---------- 侧滑抽屉（批量任务详情 / 阅读器容器） ----------

export function Drawer({ title, width = 1080, onClose, children }: {
  title: string
  width?: number
  onClose: () => void
  children: ReactNode
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="drawer-mask" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose() }}>
      <div className="drawer" style={{ width: `min(${width}px, calc(100vw - 48px))` }}>
        <header className="drawer-header">
          <strong>{title}</strong>
          <button type="button" className="icon-btn" aria-label="关闭" onClick={onClose}>✕</button>
        </header>
        <div className="drawer-body">{children}</div>
      </div>
    </div>
  )
}

// ---------- 笔记阅读器（全局覆盖层） ----------

export type ReaderPayload = {
  title: string
  markdown: string
  taskId?: string
  sourceUrl?: string
  hasImages?: boolean
}

type ReaderContextValue = (payload: ReaderPayload) => void

const ReaderContext = createContext<ReaderContextValue>(() => undefined)

export function useReader() {
  return useContext(ReaderContext)
}

export function ReaderProvider({ children }: { children: ReactNode }) {
  const [payload, setPayload] = useState<ReaderPayload | null>(null)
  const [exporting, setExporting] = useState(false)
  const open = useCallback<ReaderContextValue>((next) => setPayload(next), [])
  const toast = useToast()

  const download = async () => {
    if (!payload?.taskId || exporting) return
    setExporting(true)
    try {
      const blob = await api.download(`/export_note/${payload.taskId}`)
      const savedPath = await saveExport(blob, payload.title)
      if (savedPath === null) toast('warn', '已取消导出')
      else toast('ok', savedPath ? `已导出到：${savedPath}` : '导出成功')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '导出失败')
    } finally {
      setExporting(false)
    }
  }

  return (
    <ReaderContext.Provider value={open}>
      {children}
      {payload && (
        <div className="reader-overlay" role="dialog" aria-modal="true">
          <header className="reader-overlay-header">
            <strong>笔记阅读</strong>
            <button type="button" className="icon-btn" aria-label="关闭阅读" onClick={() => setPayload(null)}>✕</button>
          </header>
          <div className="reader-overlay-body">
            <MarkdownReader
              content={payload.markdown}
              title={payload.title}
              sourceUrl={payload.sourceUrl}
              exporting={exporting}
              onDownload={payload.taskId ? () => void download() : undefined}
              onCopy={() => {
                void navigator.clipboard.writeText(payload.markdown)
                toast('ok', 'Markdown 已复制')
              }}
            />
          </div>
        </div>
      )}
    </ReaderContext.Provider>
  )
}
