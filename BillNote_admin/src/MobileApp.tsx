import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Button, Drawer, Empty, Input, Message, Select, Switch, Tag } from '@arco-design/web-react'
import {
  IconApps, IconArrowLeft, IconCheckCircleFill, IconDownload, IconExclamationCircleFill, IconFileVideo,
  IconHistory, IconHome, IconLink, IconLoading, IconPlus, IconRefresh, IconRobot, IconSettings,
} from '@arco-design/web-react/icon'
import { api, downloadBlob } from './api'
import MarkdownPreview from './MarkdownPreview'
import type { Model, Provider, SingleTaskHistory, SingleTaskStatus } from './types'
import MobileBatchWorkspace from './mobile/MobileBatchWorkspace'

type MobileTab = 'home' | 'batch' | 'history' | 'settings'
type ReaderPayload = {
  taskId: string
  title: string
  markdown: string
  sourceUrl?: string
  exportPath?: string
  backLabel?: string
  returnTab?: MobileTab
  initialScrollTop?: number
}
type MobileHistoryState = {
  bilinoteMobile: true
  tab: MobileTab
  scrollY: number
  readerTaskId?: string
}
type LastReading = {
  version: 1
  taskId: string
  title: string
  sourceUrl?: string
  exportPath?: string
  returnTab: MobileTab
  scrollTop: number
  progress: number
  updatedAt: string
}
type MobileDraft = {
  platform: string
  videoUrl: string
  providerId: string
  modelName: string
  style: string
  videoUnderstanding: boolean
  formats: string[]
  extras: string
}

const platforms = [
  { value: 'bilibili', label: '哔哩哔哩' },
  { value: 'youtube', label: 'YouTube' },
  { value: 'douyin', label: '抖音' },
  { value: 'kuaishou', label: '快手' },
  { value: 'local', label: '本地视频' },
]
const formatOptions = [
  { value: 'toc', label: '章节目录' },
  { value: 'link', label: '时间跳转' },
  { value: 'screenshot', label: '关键截图' },
  { value: 'summary', label: 'AI 总结' },
]
const styleOptions = [
  { value: 'minimal', label: '精简' },
  { value: 'detailed', label: '详细' },
  { value: 'tutorial', label: '教程' },
  { value: 'meeting_minutes', label: '纪要' },
]
const defaultDraft: MobileDraft = {
  platform: 'bilibili', videoUrl: '', providerId: '', modelName: '', style: 'minimal',
  videoUnderstanding: false, formats: ['toc', 'summary'], extras: '',
}
const statusLabels: Record<string, string> = {
  PENDING: '排队中', PARSING: '正在解析', DOWNLOADING: '下载视频', TRANSCRIBING: '提取字幕',
  SUMMARIZING: '整理笔记', FORMATTING: '排版中', SAVING: '保存中', SUCCESS: '已完成', FAILED: '未完成',
}
const lastReadingStorageKey = 'bilinote-mobile-last-reading-v1'

function loadLastReading(): LastReading | undefined {
  try {
    const value = JSON.parse(localStorage.getItem(lastReadingStorageKey) || 'null') as LastReading | null
    return value?.version === 1 && value.taskId && value.title ? value : undefined
  } catch {
    return undefined
  }
}

function displayTime(value?: string) {
  if (!value) return '刚刚'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const diff = Date.now() - date.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

function isFinished(status?: string) {
  return status === 'SUCCESS' || status === 'FAILED'
}

function statusTone(status?: string) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'progress'
}

function ToggleChips({ value, options, onChange }: { value: string[]; options: Array<{ value: string; label: string }>; onChange: (value: string[]) => void }) {
  return <div className="mobile-choice-grid">
    {options.map((option) => <button key={option.value} type="button" className={`mobile-choice ${value.includes(option.value) ? 'is-selected' : ''}`} onClick={() => onChange(value.includes(option.value) ? value.filter((item) => item !== option.value) : [...value, option.value])}>
      <span className="mobile-choice-mark" />{option.label}
    </button>)}
  </div>
}

function NoteComposer({ visible, onClose, providers, models, onCreated }: {
  visible: boolean
  onClose: () => void
  providers: Provider[]
  models: Model[]
  onCreated: (task: SingleTaskStatus) => void
}) {
  const fileInput = useRef<HTMLInputElement>(null)
  const [draft, setDraft] = useState<MobileDraft>(defaultDraft)
  const [submitting, setSubmitting] = useState(false)
  const enabledProviders = useMemo(() => providers.filter((item) => item.enabled !== 0), [providers])
  const modelOptions = useMemo(() => models.filter((item) => String(item.provider_id) === draft.providerId).map((item) => ({ label: item.model_name, value: item.model_name })), [draft.providerId, models])

  useEffect(() => {
    if (!visible || draft.providerId || !enabledProviders.length) return
    setDraft((current) => ({ ...current, providerId: String(enabledProviders[0].id) }))
  }, [draft.providerId, enabledProviders, visible])

  useEffect(() => {
    if (!draft.providerId || !modelOptions.length || modelOptions.some((item) => item.value === draft.modelName)) return
    setDraft((current) => ({ ...current, modelName: String(modelOptions[0].value) }))
  }, [draft.modelName, draft.providerId, modelOptions])

  const update = (patch: Partial<MobileDraft>) => setDraft((current) => ({ ...current, ...patch }))
  const submit = async () => {
    try {
      if (!draft.providerId || !draft.modelName) throw new Error('请先选择用于生成笔记的 AI 模型')
      let videoUrl = draft.videoUrl.trim()
      if (draft.platform === 'local') {
        const file = fileInput.current?.files?.[0]
        if (!file) throw new Error('请选择一个本地视频文件')
        const body = new FormData()
        body.append('file', file)
        const uploaded = await api.upload<{ url: string }>('/upload', body)
        videoUrl = uploaded.url
      }
      if (!videoUrl) throw new Error('请粘贴视频链接')
      setSubmitting(true)
      const result = await api.post<{ task_id: string }>('/generate_note', {
        video_url: videoUrl,
        platform: draft.platform,
        provider_id: draft.providerId,
        model_name: draft.modelName,
        quality: 'medium',
        style: draft.style,
        format: draft.formats,
        link: draft.formats.includes('link'),
        screenshot: draft.formats.includes('screenshot'),
        extras: draft.extras,
        video_understanding: draft.videoUnderstanding,
        video_interval: 6,
        grid_size: [2, 2],
      })
      onCreated({ task_id: result.task_id, status: 'PENDING', message: '任务已加入队列', source_url: videoUrl, platform: draft.platform })
      setDraft((current) => ({ ...defaultDraft, providerId: current.providerId, modelName: current.modelName }))
      Message.success('笔记任务已创建')
      onClose()
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '创建任务失败')
    } finally {
      setSubmitting(false)
    }
  }

  return <Drawer className="mobile-compose-drawer" visible={visible} height="92vh" placement="bottom" title="创建一篇新笔记" footer={<Button type="primary" long loading={submitting} icon={<IconRobot />} onClick={() => void submit()}>开始生成笔记</Button>} onCancel={onClose} unmountOnExit>
    <section className="mobile-compose-section">
      <label className="mobile-field-label">视频来源</label>
      <div className="mobile-platform-row">{platforms.map((item) => <button type="button" key={item.value} className={`mobile-platform-chip ${draft.platform === item.value ? 'is-active' : ''}`} onClick={() => update({ platform: item.value })}>{item.label}</button>)}</div>
      {draft.platform === 'local'
        ? <label className="mobile-file-picker"><IconFileVideo /><span>选择本地视频</span><input ref={fileInput} type="file" accept="video/*" onChange={() => undefined} /></label>
        : <Input.TextArea className="mobile-url-input" value={draft.videoUrl} onChange={(value) => update({ videoUrl: value })} placeholder="粘贴视频链接" autoSize={{ minRows: 3, maxRows: 5 }} />}
    </section>
    <section className="mobile-compose-section">
      <label className="mobile-field-label">生成模型</label>
      <Select value={draft.providerId || undefined} placeholder={enabledProviders.length ? '选择模型供应商' : '暂无可用供应商'} options={enabledProviders.map((item) => ({ label: item.name, value: String(item.id) }))} onChange={(value) => update({ providerId: String(value), modelName: '' })} />
      <Select className="mobile-model-select" value={draft.modelName || undefined} placeholder="选择 AI 模型" disabled={!draft.providerId} options={modelOptions} onChange={(value) => update({ modelName: String(value) })} />
    </section>
    <section className="mobile-compose-section">
      <label className="mobile-field-label">笔记风格</label>
      <div className="mobile-segmented">{styleOptions.map((item) => <button type="button" key={item.value} className={draft.style === item.value ? 'is-active' : ''} onClick={() => update({ style: item.value })}>{item.label}</button>)}</div>
    </section>
    <section className="mobile-compose-section">
      <div className="mobile-setting-row"><div><strong>视频理解</strong><span>根据画面提取关键内容</span></div><Switch checked={draft.videoUnderstanding} onChange={(value) => update({ videoUnderstanding: value })} /></div>
      <label className="mobile-field-label mobile-format-label">笔记内容</label>
      <ToggleChips value={draft.formats} options={formatOptions.map((item) => ({ ...item, label: item.value === 'screenshot' && !draft.videoUnderstanding ? '关键截图（需开启视频理解）' : item.label }))} onChange={(value) => update({ formats: value.filter((item) => item !== 'screenshot' || draft.videoUnderstanding) })} />
    </section>
    <section className="mobile-compose-section">
      <label className="mobile-field-label">补充要求 <em>可选</em></label>
      <Input.TextArea value={draft.extras} onChange={(value) => update({ extras: value })} placeholder="例如：用通俗语言整理，重点标注可实操的方法" autoSize={{ minRows: 3, maxRows: 6 }} />
    </section>
  </Drawer>
}

function HistoryCard({ record, loadingId, onRead, onExport, onRegenerate }: {
  record: SingleTaskHistory
  loadingId?: string
  onRead: (record: SingleTaskHistory) => void
  onExport: (record: SingleTaskHistory) => void
  onRegenerate: (record: SingleTaskHistory) => void
}) {
  const state = statusTone(record.status)
  return <article className="mobile-history-card">
    <div className="mobile-history-main" onClick={() => record.has_result && onRead(record)}>
      <div className={`mobile-history-icon ${state}`}><IconFileVideo /></div>
      <div className="mobile-history-content"><strong>{record.title || '未命名 AI 笔记'}</strong><span>{record.platform} · {displayTime(record.created_at)}</span></div>
      <div className={`mobile-status-dot ${state}`} title={statusLabels[record.status] || record.status} />
    </div>
    {record.has_result ? <div className="mobile-history-actions"><button type="button" onClick={() => onRead(record)} disabled={loadingId === record.task_id}>{loadingId === record.task_id ? '正在打开' : '阅读笔记'}</button><button type="button" onClick={() => onExport(record)}><IconDownload /> 导出</button>{record.can_regenerate && <button type="button" onClick={() => onRegenerate(record)}>重新生成</button>}</div> : record.status === 'FAILED' ? <><div className="mobile-error-summary"><IconExclamationCircleFill /><strong>ERROR</strong><span>处理失败，内容已收起</span></div><div className="mobile-history-actions"><button type="button" disabled>阅读</button><button type="button" disabled><IconDownload /> 导出</button>{record.can_regenerate && <button type="button" onClick={() => onRegenerate(record)} disabled={loadingId === record.task_id}>重试生成</button>}</div></> : <p className="mobile-history-message">{statusLabels[record.status] || '正在处理'}</p>}
  </article>
}

export default function MobileApp() {
  const [tab, setTab] = useState<MobileTab>(() => {
    const state = window.history.state as Partial<MobileHistoryState> | null
    return state?.bilinoteMobile && state.tab ? state.tab : 'home'
  })
  const [composerVisible, setComposerVisible] = useState(false)
  const [providers, setProviders] = useState<Provider[]>([])
  const [models, setModels] = useState<Model[]>([])
  const [history, setHistory] = useState<SingleTaskHistory[]>([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [task, setTask] = useState<SingleTaskStatus>()
  const [reader, setReader] = useState<ReaderPayload>()
  const [lastReading, setLastReading] = useState<LastReading | undefined>(loadLastReading)
  const [openingId, setOpeningId] = useState<string>()
  const [regeneratingId, setRegeneratingId] = useState<string>()
  const lastOpenedTask = useRef<string | undefined>(undefined)
  const readerCache = useRef(new Map<string, ReaderPayload>())
  const pageScroll = useRef<Record<MobileTab, number>>({ home: 0, batch: 0, history: 0, settings: 0 })

  const restorePageScroll = useCallback((scrollY: number) => {
    const restore = () => window.scrollTo({ top: scrollY, behavior: 'auto' })
    window.requestAnimationFrame(restore)
    window.setTimeout(restore, 320)
  }, [])

  useEffect(() => {
    const currentState = window.history.state as Partial<MobileHistoryState> | null
    if (!currentState?.bilinoteMobile) {
      window.history.replaceState({ bilinoteMobile: true, tab, scrollY: window.scrollY } satisfies MobileHistoryState, '')
    }

    const handlePopState = (event: PopStateEvent) => {
      const state = event.state as MobileHistoryState | null
      if (!state?.bilinoteMobile) return
      setTab(state.tab)
      pageScroll.current[state.tab] = state.scrollY || 0
      setReader(state.readerTaskId ? readerCache.current.get(state.readerTaskId) : undefined)
      restorePageScroll(state.scrollY || 0)
    }

    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [restorePageScroll])

  const navigateTab = useCallback((nextTab: MobileTab) => {
    if (nextTab === tab && !reader) return
    pageScroll.current[tab] = window.scrollY
    const currentState = window.history.state as MobileHistoryState | null
    window.history.replaceState({
      ...(currentState?.bilinoteMobile ? currentState : {}),
      bilinoteMobile: true,
      tab,
      scrollY: window.scrollY,
    } satisfies MobileHistoryState, '')
    window.history.pushState({ bilinoteMobile: true, tab: nextTab, scrollY: pageScroll.current[nextTab] } satisfies MobileHistoryState, '')
    setReader(undefined)
    setTab(nextTab)
    restorePageScroll(pageScroll.current[nextTab])
  }, [reader, restorePageScroll, tab])

  const openReader = useCallback((payload: ReaderPayload) => {
    const stored = loadLastReading()
    const nextReader = {
      ...payload,
      initialScrollTop: payload.initialScrollTop ?? (stored?.taskId === payload.taskId ? stored.scrollTop : 0),
    }
    pageScroll.current[tab] = window.scrollY
    const currentState = window.history.state as MobileHistoryState | null
    window.history.replaceState({
      ...(currentState?.bilinoteMobile ? currentState : {}),
      bilinoteMobile: true,
      tab,
      scrollY: window.scrollY,
    } satisfies MobileHistoryState, '')
    window.history.pushState({ bilinoteMobile: true, tab, scrollY: window.scrollY, readerTaskId: payload.taskId } satisfies MobileHistoryState, '')
    readerCache.current.set(payload.taskId, nextReader)
    setLastReading({
      version: 1,
      taskId: payload.taskId,
      title: payload.title,
      sourceUrl: payload.sourceUrl,
      exportPath: payload.exportPath,
      returnTab: payload.returnTab || tab,
      scrollTop: nextReader.initialScrollTop || 0,
      progress: stored?.taskId === payload.taskId ? stored.progress : 0,
      updatedAt: new Date().toISOString(),
    })
    setReader(nextReader)
  }, [tab])

  useEffect(() => {
    if (!lastReading) {
      localStorage.removeItem(lastReadingStorageKey)
      return
    }
    localStorage.setItem(lastReadingStorageKey, JSON.stringify(lastReading))
  }, [lastReading])

  useEffect(() => {
    if (!reader) return
    let frame = 0
    let saveTimer = 0
    let restoreTimer = 0
    let scrollContainer: HTMLElement | null = null

    const saveProgress = () => {
      if (!scrollContainer) return
      const scrollTop = Math.round(scrollContainer.scrollTop)
      const maxScroll = Math.max(0, scrollContainer.scrollHeight - scrollContainer.clientHeight)
      const progress = maxScroll ? Math.min(100, Math.round((scrollTop / maxScroll) * 100)) : 0
      setLastReading((current) => current?.taskId === reader.taskId
        ? { ...current, scrollTop, progress, updatedAt: new Date().toISOString() }
        : current)
    }
    const handleScroll = () => {
      window.clearTimeout(saveTimer)
      saveTimer = window.setTimeout(saveProgress, 180)
    }
    const attach = () => {
      scrollContainer = document.querySelector<HTMLElement>('.mobile-reader-drawer .arco-drawer-body')
      if (!scrollContainer) {
        frame = window.requestAnimationFrame(attach)
        return
      }
      scrollContainer.scrollTo({ top: reader.initialScrollTop || 0, behavior: 'auto' })
      restoreTimer = window.setTimeout(() => {
        scrollContainer?.scrollTo({ top: reader.initialScrollTop || 0, behavior: 'auto' })
      }, 320)
      scrollContainer.addEventListener('scroll', handleScroll, { passive: true })
    }
    frame = window.requestAnimationFrame(attach)
    return () => {
      window.cancelAnimationFrame(frame)
      window.clearTimeout(saveTimer)
      window.clearTimeout(restoreTimer)
      saveProgress()
      scrollContainer?.removeEventListener('scroll', handleScroll)
    }
  }, [reader])

  const loadProviders = useCallback(async () => {
    const [providerRows, modelRows] = await Promise.all([api.get<Provider[]>('/get_all_providers'), api.get<Model[]>('/model_list')])
    setProviders(providerRows || [])
    setModels(modelRows || [])
  }, [])
  const loadHistory = useCallback(async (showLoading = true) => {
    if (showLoading) setHistoryLoading(true)
    try {
      const result = await api.get<{ items: SingleTaskHistory[] }>('/single/tasks?page=1&page_size=50')
      setHistory(result.items || [])
    } finally {
      if (showLoading) setHistoryLoading(false)
    }
  }, [])
  const poll = useCallback(async (taskId: string) => {
    try {
      const update = await api.get<SingleTaskStatus>(`/task_status/${taskId}`)
      setTask((current) => current?.task_id === taskId ? { ...current, ...update } : update)
      if (isFinished(update.status)) void loadHistory(false).catch(() => undefined)
      if (update.status === 'SUCCESS' && update.result?.markdown && lastOpenedTask.current !== taskId) {
        lastOpenedTask.current = taskId
        openReader({ taskId, title: update.result.audio_meta?.title || 'AI 笔记', markdown: update.result.markdown, sourceUrl: update.platform === 'local' ? undefined : task?.source_url, backLabel: '首页', returnTab: 'home' })
      }
    } catch (error) {
      setTask((current) => current ? { ...current, status: 'FAILED', message: error instanceof Error ? error.message : '任务状态获取失败' } : current)
    }
  }, [loadHistory, openReader, task?.source_url])
  useEffect(() => { void loadProviders().catch((error: Error) => Message.error(error.message)); void loadHistory().catch((error: Error) => Message.error(error.message)) }, [loadHistory, loadProviders])
  useEffect(() => {
    if (!task?.task_id || isFinished(task.status)) return
    const timer = window.setInterval(() => void poll(task.task_id), 2500)
    return () => window.clearInterval(timer)
  }, [poll, task?.status, task?.task_id])
  useEffect(() => {
    if (!history.some((item) => !isFinished(item.status))) return
    const timer = window.setInterval(() => void loadHistory(false).catch(() => undefined), 5000)
    return () => window.clearInterval(timer)
  }, [history, loadHistory])

  const exportTask = async (taskId: string, title: string, exportPath = `/export_note/${taskId}`) => {
    try {
      const blob = await api.download(exportPath)
      const extension = blob.type.includes('zip') ? 'zip' : 'md'
      downloadBlob(blob, `${title.replace(/[\\/:*?\"<>|]/g, '-').trim() || 'BiliNote'}.${extension}`)
    } catch (error) { Message.error(error instanceof Error ? error.message : '导出失败') }
  }
  const readHistory = async (record: SingleTaskHistory) => {
    try {
      setOpeningId(record.task_id)
      const detail = await api.get<SingleTaskStatus>(`/task_status/${record.task_id}`)
      if (!detail.result?.markdown) throw new Error('这篇笔记暂时还不能阅读')
      openReader({ taskId: record.task_id, title: record.title || detail.result.audio_meta?.title || 'AI 笔记', markdown: detail.result.markdown, sourceUrl: record.platform === 'local' ? undefined : record.source_url, backLabel: tab === 'history' ? '笔记库' : '首页', returnTab: tab === 'history' ? 'history' : 'home' })
    } catch (error) { Message.warning(error instanceof Error ? error.message : '读取笔记失败') } finally { setOpeningId(undefined) }
  }
  const continueReading = async () => {
    if (!lastReading) return
    try {
      setOpeningId(lastReading.taskId)
      const detail = await api.get<SingleTaskStatus>(`/task_status/${lastReading.taskId}`)
      if (!detail.result?.markdown) throw new Error('这篇笔记暂时还不能阅读')
      openReader({
        taskId: lastReading.taskId,
        title: lastReading.title || detail.result.audio_meta?.title || 'AI 笔记',
        markdown: detail.result.markdown,
        sourceUrl: lastReading.sourceUrl,
        exportPath: lastReading.exportPath,
        backLabel: '首页',
        returnTab: 'home',
        initialScrollTop: lastReading.scrollTop,
      })
    } catch (error) {
      Message.warning(error instanceof Error ? error.message : '继续阅读失败')
    } finally {
      setOpeningId(undefined)
    }
  }
  const regenerate = async (record: SingleTaskHistory) => {
    try {
      setRegeneratingId(record.task_id)
      const result = await api.post<{ task_id: string }>(`/single/tasks/${record.task_id}/regenerate`)
      setTask({ task_id: result.task_id, status: 'PENDING', message: `正在重新生成：${record.title}`, source_url: record.source_url, platform: record.platform })
      navigateTab('home')
      Message.success('已创建重新生成任务')
      void loadHistory(false)
    } catch (error) { Message.error(error instanceof Error ? error.message : '重新生成失败') } finally { setRegeneratingId(undefined) }
  }
  const completed = history.filter((item) => item.status === 'SUCCESS').length
  const closeReader = () => {
    const state = window.history.state as MobileHistoryState | null
    if (state?.bilinoteMobile && state.readerTaskId) {
      window.history.back()
      return
    }
    const returnTab = reader?.returnTab || tab
    setReader(undefined)
    setTab(returnTab)
    restorePageScroll(pageScroll.current[returnTab])
  }
  // Only show a progress card for a task created or retried in this session.
  // History records can retain an intermediate status after a worker restart.
  const inProgress = task && !isFinished(task.status) ? task : undefined

  return <main className="mobile-app-shell">
    <div className="mobile-page-scroll">
      {tab === 'home' && <>
        <header className="mobile-home-header"><div><span className="mobile-eyebrow">BILINOTE · MOBILE</span><h1>把视频，变成<br />可回看的知识</h1></div><button type="button" className="mobile-header-action" onClick={() => navigateTab('settings')} aria-label="打开设置"><IconSettings /></button></header>
        <section className="mobile-create-hero"><div className="mobile-create-icon"><IconLink /></div><div><strong>从视频开始记录</strong><span>支持链接与本地视频，自动生成结构化笔记</span></div><button type="button" onClick={() => setComposerVisible(true)} aria-label="创建笔记"><IconPlus /></button></section>
        {inProgress && <section className="mobile-task-card"><div className="mobile-task-top"><div className="mobile-task-title"><span className="mobile-pulse"><IconLoading /></span><div><strong>{statusLabels[inProgress.status] || '正在处理'}</strong><span>{inProgress.message || '任务正在准备中'}</span></div></div><Tag color="arcoblue">进行中</Tag></div><div className="mobile-progress-track"><i /></div><button type="button" onClick={() => navigateTab('history')}>查看任务进度 <span>›</span></button></section>}
        {lastReading && <button type="button" className="mobile-continue-card" onClick={() => void continueReading()} disabled={openingId === lastReading.taskId}><span className="mobile-continue-icon"><IconFileVideo /></span><span className="mobile-continue-content"><small>继续阅读 · {lastReading.progress > 0 ? `已读 ${lastReading.progress}%` : '上次阅读'}</small><strong>{lastReading.title}</strong></span><b>{openingId === lastReading.taskId ? <IconLoading /> : '›'}</b></button>}
        <section className="mobile-section"><div className="mobile-section-title"><div><span>最近生成</span><strong>笔记库</strong></div><button type="button" onClick={() => navigateTab('history')}>全部记录 <span>›</span></button></div>
          {historyLoading ? <div className="mobile-loading-state"><IconLoading /> 正在同步记录</div> : history.filter((item) => item.has_result).slice(0, 3).length ? <div className="mobile-recent-list">{history.filter((item) => item.has_result).slice(0, 3).map((record) => <HistoryCard key={record.task_id} record={record} loadingId={openingId} onRead={(item) => void readHistory(item)} onExport={(item) => void exportTask(item.task_id, item.title)} onRegenerate={(item) => void regenerate(item)} />)}</div> : <div className="mobile-empty-home"><IconRobot /><strong>还没有笔记</strong><span>创建第一篇视频笔记，内容会出现在这里</span></div>}
        </section>
      </>}
      {tab === 'batch' && <MobileBatchWorkspace providers={providers} models={models} onOpenReader={(payload) => openReader({ ...payload, backLabel: payload.backLabel || '合集', returnTab: payload.returnTab || 'batch' })} />}
      {tab === 'history' && <><header className="mobile-simple-header"><div><span className="mobile-eyebrow">YOUR LIBRARY</span><h1>全部笔记</h1></div><button type="button" className="mobile-header-action" onClick={() => void loadHistory()} aria-label="刷新记录"><IconRefresh /></button></header><div className="mobile-library-stat"><div><strong>{completed}</strong><span>已完成</span></div><div><strong>{history.length}</strong><span>全部任务</span></div><div><strong>{history.filter((item) => !isFinished(item.status)).length}</strong><span>处理中</span></div></div><section className="mobile-history-list">{historyLoading ? <div className="mobile-loading-state"><IconLoading /> 正在同步记录</div> : history.length ? history.map((record) => <HistoryCard key={record.task_id} record={record} loadingId={openingId || regeneratingId} onRead={(item) => void readHistory(item)} onExport={(item) => void exportTask(item.task_id, item.title)} onRegenerate={(item) => void regenerate(item)} />) : <Empty description="暂无笔记记录" />}</section></>}
      {tab === 'settings' && <><header className="mobile-simple-header"><div><span className="mobile-eyebrow">MOBILE SETTINGS</span><h1>偏好设置</h1></div></header><section className="mobile-settings-group"><span>当前能力</span><div className="mobile-setting-item"><IconRobot /><div><strong>{providers.filter((item) => item.enabled !== 0).length} 个可用供应商</strong><small>模型配置由管理端统一维护</small></div></div><div className="mobile-setting-item"><IconFileVideo /><div><strong>视频转笔记</strong><small>单视频任务与管理端实时同步</small></div><IconCheckCircleFill className="mobile-ok-icon" /></div></section><section className="mobile-settings-group"><span>管理端</span><button type="button" className="mobile-admin-link" onClick={() => { window.location.href = '/admin/' }}><IconSettings /><div><strong>打开桌面管理控制台</strong><small>配置模型、批量任务与下载设置</small></div><b>›</b></button></section></>}
    </div>
    <nav className="mobile-bottom-nav"><button type="button" className={tab === 'home' ? 'is-active' : ''} onClick={() => navigateTab('home')}><IconHome /><span>首页</span></button><button type="button" className={tab === 'batch' ? 'is-active' : ''} onClick={() => navigateTab('batch')}><IconApps /><span>合集</span></button><button type="button" className={tab === 'history' ? 'is-active' : ''} onClick={() => navigateTab('history')}><IconHistory /><span>笔记库</span></button></nav>
    <NoteComposer visible={composerVisible} onClose={() => setComposerVisible(false)} providers={providers} models={models} onCreated={(created) => { lastOpenedTask.current = undefined; setTask(created); void loadHistory(false) }} />
    <Drawer className="mobile-reader-drawer" visible={Boolean(reader)} width="100vw" title={<button type="button" className="mobile-reader-title" onClick={closeReader}><IconArrowLeft /><span>{reader?.backLabel || '返回'}</span></button>} footer={null} onCancel={closeReader} unmountOnExit>{reader && <MarkdownPreview content={reader.markdown} title={reader.title} sourceUrl={reader.sourceUrl} onDownload={() => void exportTask(reader.taskId, reader.title, reader.exportPath)} />}</Drawer>
  </main>
}
