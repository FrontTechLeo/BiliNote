import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Button, Drawer, Empty, Input, Message, Progress, Select, Switch, Tag } from '@arco-design/web-react'
import {
  IconApps, IconClose, IconDelete, IconDownload, IconEdit, IconFileVideo,
  IconLoading, IconPlus, IconRefresh,
} from '@arco-design/web-react/icon'
import { api, downloadBlob } from '../api'
import { imageCompressionOptions } from '../types'
import type { BatchItem, BatchTask, Model, Provider } from '../types'
import type { BatchWorkspaceProps, MobileAppendPreview, MobilePreviewItem } from './types'
import {
  batchFormats, batchStatusLabel, batchStyles, configForApi, defaultBatchConfig, formatBytes,
  canAppendBatch, isTerminalBatch, itemStatusLabel, normaliseBatchConfig, sortFilesNaturally,
} from './utils'

type SourceType = 'bilibili' | 'local'
type ConfigEditorProps = { providers: Provider[]; models: Model[]; value: ReturnType<typeof normaliseBatchConfig>; onChange: (value: ReturnType<typeof normaliseBatchConfig>) => void }

function statusTone(status?: string) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'PARTIAL_FAILED') return 'danger'
  if (status === 'CANCELED') return 'muted'
  return 'progress'
}

function ChoiceGrid({ value, options, disabled, onChange }: { value: string[]; options: Array<{ value: string; label: string }>; disabled?: string[]; onChange: (value: string[]) => void }) {
  return <div className="mobile-choice-grid">{options.map((option) => {
    const isDisabled = disabled?.includes(option.value)
    return <button type="button" key={option.value} disabled={isDisabled} className={`mobile-choice ${value.includes(option.value) ? 'is-selected' : ''} ${isDisabled ? 'is-disabled' : ''}`} onClick={() => onChange(value.includes(option.value) ? value.filter((item) => item !== option.value) : [...value, option.value])}><span className="mobile-choice-mark" />{option.label}</button>
  })}</div>
}

function ConfigEditor({ providers, models, value, onChange }: ConfigEditorProps) {
  const enabledProviders = useMemo(() => providers.filter((item) => item.enabled !== 0), [providers])
  const modelOptions = useMemo(() => models.filter((item) => String(item.provider_id) === String(value.provider_id)).map((item) => ({ label: item.model_name, value: item.model_name })), [models, value.provider_id])
  useEffect(() => {
    if (value.provider_id || !enabledProviders.length) return
    onChange({ ...value, provider_id: String(enabledProviders[0].id), model_name: '' })
  }, [enabledProviders, onChange, value])
  useEffect(() => {
    if (!value.provider_id || !modelOptions.length || modelOptions.some((item) => item.value === value.model_name)) return
    onChange({ ...value, model_name: modelOptions[0].value })
  }, [modelOptions, onChange, value])
  const update = (patch: Partial<ReturnType<typeof normaliseBatchConfig>>) => onChange({ ...value, ...patch })
  return <div className="mobile-batch-config">
    <label className="mobile-field-label">生成模型</label>
    <Select value={value.provider_id || undefined} placeholder="选择模型供应商" options={enabledProviders.map((item) => ({ label: item.name, value: String(item.id) }))} onChange={(providerId) => update({ provider_id: String(providerId), model_name: '' })} />
    <Select className="mobile-model-select" value={value.model_name || undefined} placeholder="选择 AI 模型" disabled={!value.provider_id} options={modelOptions} onChange={(model_name) => update({ model_name: String(model_name) })} />
    <label className="mobile-field-label mobile-format-label">笔记风格</label>
    <div className="mobile-segmented">{batchStyles.map((item) => <button type="button" key={item.value} className={value.style === item.value ? 'is-active' : ''} onClick={() => update({ style: item.value })}>{item.label}</button>)}</div>
    <label className="mobile-field-label mobile-format-label">笔记内容</label>
    <ChoiceGrid value={value.format} options={batchFormats} disabled={value.video_understanding ? [] : ['screenshot']} onChange={(format) => update({ format: format.filter((item) => value.video_understanding || item !== 'screenshot') })} />
    <div className="mobile-setting-row mobile-batch-switch"><div><strong>视频理解</strong><span>提取每集画面中的关键内容</span></div><Switch checked={value.video_understanding} onChange={(video_understanding) => update({ video_understanding, format: video_understanding ? value.format : value.format.filter((item) => item !== 'screenshot') })} /></div>
    {value.video_understanding && <><label className="mobile-field-label mobile-format-label">画面压缩</label><div className="mobile-segmented">{imageCompressionOptions.map((item) => <button type="button" key={item.value} className={value.image_compression === item.value ? 'is-active' : ''} onClick={() => update({ image_compression: item.value })}>{item.label}</button>)}</div></>}
    <label className="mobile-field-label mobile-format-label">补充要求 <em>可选</em></label>
    <Input.TextArea value={value.extras || ''} onChange={(extras) => update({ extras })} placeholder="例如：突出每集的实操步骤" autoSize={{ minRows: 2, maxRows: 4 }} />
  </div>
}

function SourcePicker({ sourceType, sourceUrl, files, onSourceTypeChange, onSourceUrlChange, onFilesChange }: { sourceType: SourceType; sourceUrl: string; files: File[]; onSourceTypeChange: (value: SourceType) => void; onSourceUrlChange: (value: string) => void; onFilesChange: (files: File[]) => void }) {
  const fileRef = useRef<HTMLInputElement>(null)
  return <>
    <div className="mobile-platform-row mobile-batch-source-tabs"><button type="button" className={sourceType === 'bilibili' ? 'is-active' : ''} onClick={() => onSourceTypeChange('bilibili')}>哔哩哔哩合集</button><button type="button" className={sourceType === 'local' ? 'is-active' : ''} onClick={() => onSourceTypeChange('local')}>本地视频文件夹</button></div>
    {sourceType === 'bilibili' ? <Input.TextArea value={sourceUrl} onChange={onSourceUrlChange} placeholder="粘贴合集、视频或番剧列表链接" autoSize={{ minRows: 3, maxRows: 5 }} /> : <label className="mobile-file-picker"><IconApps /><span>{files.length ? `已选择 ${files.length} 个视频` : '选择本地视频文件夹'}</span><small>会按文件名自然排序并自动过滤重复文件</small><input ref={fileRef} type="file" multiple accept="video/*" {...({ webkitdirectory: '', directory: '' } as any)} onChange={(event) => onFilesChange(sortFilesNaturally(Array.from(event.target.files || [])))} /></label>}
    {files.length > 0 && <p className="mobile-batch-file-summary">{files.slice(0, 3).map((file) => file.name).join('、')}{files.length > 3 ? ` 等 ${files.length} 个文件` : ''}</p>}
  </>
}

function StepBar({ step }: { step: number }) {
  return <div className="mobile-batch-stepbar"><span className={step >= 0 ? 'is-active' : ''}><b>1</b> 分析内容</span><i /><span className={step >= 1 ? 'is-active' : ''}><b>2</b> 配置笔记</span></div>
}

function PreviewSelect({ items, selected, onChange }: { items: MobilePreviewItem[]; selected: string[]; onChange: (values: string[]) => void }) {
  const allSelected = Boolean(items.length && selected.length === items.length)
  return <div className="mobile-batch-preview-list"><div className="mobile-batch-preview-head"><span>发现 {items.length} 个视频</span><button type="button" onClick={() => onChange(allSelected ? [] : items.map((item) => item.source_url))}>{allSelected ? '取消全选' : '全选'}</button></div>{items.length ? items.map((item, index) => <button type="button" key={`${item.source_url}-${index}`} className={`mobile-batch-preview-item ${selected.includes(item.source_url) ? 'is-selected' : ''}`} onClick={() => onChange(selected.includes(item.source_url) ? selected.filter((url) => url !== item.source_url) : [...selected, item.source_url])}><span className="mobile-choice-mark" /><em>{String(item.position || index + 1).padStart(2, '0')}</em><strong>{item.title}</strong></button>) : <Empty description="没有发现可用视频" />}</div>
}

function BatchCreateDrawer({ visible, providers, models, onClose, onCreated }: { visible: boolean; providers: Provider[]; models: Model[]; onClose: () => void; onCreated: (task: BatchTask) => void }) {
  const [step, setStep] = useState(0)
  const [sourceType, setSourceType] = useState<SourceType>('bilibili')
  const [sourceUrl, setSourceUrl] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [preview, setPreview] = useState<{ title: string; items: MobilePreviewItem[] }>()
  const [selected, setSelected] = useState<string[]>([])
  const [title, setTitle] = useState('')
  const [config, setConfig] = useState(normaliseBatchConfig(defaultBatchConfig))
  const [loading, setLoading] = useState(false)
  const reset = () => { setStep(0); setSourceType('bilibili'); setSourceUrl(''); setFiles([]); setPreview(undefined); setSelected([]); setTitle(''); setConfig(normaliseBatchConfig(defaultBatchConfig)); onClose() }
  const analyse = async () => {
    try {
      setLoading(true)
      const result = sourceType === 'bilibili'
        ? await api.post<{ title: string; items: MobilePreviewItem[] }>('/batch/preview/bilibili', { source_url: sourceUrl.trim() })
        : await api.upload<{ title: string; items: MobilePreviewItem[] }>('/batch/preview/local', (() => { const body = new FormData(); files.forEach((file) => body.append('files', file)); return body })())
      if (!result.items?.length) throw new Error('没有发现可处理的视频')
      setPreview(result); setSelected(result.items.map((item) => item.source_url)); setStep(1)
    } catch (error) { Message.error(error instanceof Error ? error.message : '分析合集失败') } finally { setLoading(false) }
  }
  const create = async () => {
    try {
      if (!preview || !selected.length) throw new Error('至少选择一个视频')
      if (!config.provider_id || !config.model_name) throw new Error('请选择模型供应商和 AI 模型')
      setLoading(true)
      const created = await api.post<BatchTask>('/batch/tasks', { title: title.trim() || preview.title, source_type: sourceType, source_value: sourceType === 'bilibili' ? sourceUrl.trim() : undefined, config: configForApi(config), items: preview.items.filter((item) => selected.includes(item.source_url)) })
      Message.success('合集笔记任务已创建')
      onCreated(created)
      reset()
    } catch (error) { Message.error(error instanceof Error ? error.message : '创建合集失败') } finally { setLoading(false) }
  }
  return <Drawer className="mobile-batch-drawer" visible={visible} placement="bottom" height="94vh" title="新建合集笔记" footer={<div className="mobile-batch-footer">{step === 1 && <Button onClick={() => setStep(0)}>上一步</Button>}<Button type="primary" loading={loading} onClick={() => void (step === 0 ? analyse() : create())}>{step === 0 ? '分析视频列表' : '开始批量生成'}</Button></div>} onCancel={reset} unmountOnExit>
    <StepBar step={step} />
    {step === 0 ? <section className="mobile-batch-section"><label className="mobile-field-label">合集来源</label><SourcePicker sourceType={sourceType} sourceUrl={sourceUrl} files={files} onSourceTypeChange={(value) => { setSourceType(value); setPreview(undefined); setSelected([]) }} onSourceUrlChange={setSourceUrl} onFilesChange={setFiles} /><p className="mobile-batch-hint">支持 B 站合集、视频链接，也可以直接选择本地文件夹。</p></section> : <><section className="mobile-batch-section"><label className="mobile-field-label">选择要生成的内容</label><PreviewSelect items={preview?.items || []} selected={selected} onChange={setSelected} /></section><section className="mobile-batch-section"><label className="mobile-field-label">任务名称 <em>可选</em></label><Input value={title} onChange={setTitle} maxLength={100} placeholder={preview?.title || '未填写时使用合集标题'} /></section><section className="mobile-batch-section"><ConfigEditor providers={providers} models={models} value={config} onChange={setConfig} /></section></>}
  </Drawer>
}

function BatchEditDrawer({ task, providers, models, onClose, onUpdated }: { task?: BatchTask; providers: Provider[]; models: Model[]; onClose: () => void; onUpdated: (task: BatchTask) => void }) {
  const [title, setTitle] = useState('')
  const [config, setConfig] = useState(normaliseBatchConfig())
  const [saving, setSaving] = useState(false)
  const canEditConfig = Boolean(task && isTerminalBatch(task.status))
  useEffect(() => { if (task) { setTitle(task.title); setConfig(normaliseBatchConfig(task.config)) } }, [task])
  const save = async () => {
    if (!task) return
    try {
      if (!title.trim()) throw new Error('请输入任务名称')
      setSaving(true)
      const payload: Record<string, unknown> = { title: title.trim() }
      if (canEditConfig) payload.config = configForApi(config)
      const updated = await api.patch<BatchTask>(`/batch/tasks/${task.id}`, payload)
      Message.success('合集设置已保存'); onUpdated(updated); onClose()
    } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败') } finally { setSaving(false) }
  }
  return <Drawer className="mobile-batch-drawer" visible={Boolean(task)} placement="bottom" height="92vh" title="编辑合集设置" footer={<Button type="primary" long loading={saving} onClick={() => void save()}>保存修改</Button>} onCancel={onClose} unmountOnExit>{task && <><section className="mobile-batch-section"><label className="mobile-field-label">任务名称</label><Input value={title} onChange={setTitle} maxLength={100} /></section>{canEditConfig ? <section className="mobile-batch-section"><p className="mobile-batch-hint">修改后的模型和格式会用于后续追加视频与失败重试，已完成的笔记不会自动重生成。</p><ConfigEditor providers={providers} models={models} value={config} onChange={setConfig} /></section> : <section className="mobile-batch-notice"><IconLoading />任务正在执行，目前只能修改名称；生成配置请在任务结束后调整。</section>}</>}</Drawer>
}

function BatchAppendDrawer({ task, onClose, onUpdated }: { task?: BatchTask; onClose: () => void; onUpdated: (task: BatchTask) => void }) {
  const [sourceType, setSourceType] = useState<SourceType>('bilibili')
  const [sourceUrl, setSourceUrl] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [preview, setPreview] = useState<MobileAppendPreview>()
  const [selected, setSelected] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  useEffect(() => { if (task) { setSourceType(task.source_type === 'local' ? 'local' : 'bilibili'); setSourceUrl(task.source_value || '') } }, [task])
  const reset = () => { setPreview(undefined); setSelected([]); setFiles([]); setSourceUrl(task?.source_value || ''); onClose() }
  const analyse = async () => {
    if (!task) return
    try {
      setLoading(true)
      const result = sourceType === 'bilibili'
        ? await api.post<MobileAppendPreview>(`/batch/tasks/${task.id}/preview/bilibili`, { source_url: sourceUrl.trim() })
        : await api.upload<MobileAppendPreview>(`/batch/tasks/${task.id}/preview/local`, (() => { const body = new FormData(); files.forEach((file) => body.append('files', file)); return body })())
      setPreview(result); setSelected(result.items.map((item) => item.source_url))
    } catch (error) { Message.error(error instanceof Error ? error.message : '分析新增视频失败') } finally { setLoading(false) }
  }
  const append = async () => {
    if (!task || !preview || !selected.length) return
    try {
      setLoading(true)
      const result = await api.post<{ task: BatchTask; added_count: number; skipped_count: number }>(`/batch/tasks/${task.id}/items`, { items: preview.items.filter((item) => selected.includes(item.source_url)) })
      Message.success(`已追加 ${result.added_count} 个视频`); onUpdated(result.task); reset()
    } catch (error) { Message.error(error instanceof Error ? error.message : '追加视频失败') } finally { setLoading(false) }
  }
  return <Drawer className="mobile-batch-drawer" visible={Boolean(task)} placement="bottom" height="92vh" title={task ? `追加视频 · ${task.title}` : '追加视频'} footer={<div className="mobile-batch-footer">{preview && <Button onClick={() => setPreview(undefined)}>重新选择</Button>}<Button type="primary" loading={loading} disabled={Boolean(preview && !selected.length)} onClick={() => void (preview ? append() : analyse())}>{preview ? '追加并开始处理' : '分析新增视频'}</Button></div>} onCancel={reset} unmountOnExit>{task && <><p className="mobile-batch-hint">新增视频会沿用原合集的模型、笔记格式和视频理解设置，重复视频会自动过滤。</p>{!preview ? <section className="mobile-batch-section"><SourcePicker sourceType={sourceType} sourceUrl={sourceUrl} files={files} onSourceTypeChange={setSourceType} onSourceUrlChange={setSourceUrl} onFilesChange={setFiles} /></section> : <section className="mobile-batch-section"><div className="mobile-batch-append-summary">发现 {preview.discovered_count} 个，过滤 {preview.skipped_count} 个，可追加 {preview.items.length} 个</div><PreviewSelect items={preview.items} selected={selected} onChange={setSelected} /></section>}</>}</Drawer>
}

function BatchItemCard({ item, onRead, onRetry, onCancel, onDelete, onExport, onEvents, busy }: { item: BatchItem; onRead: () => void; onRetry: () => void; onCancel: () => void; onDelete: () => void; onExport: () => void; onEvents: () => void; busy?: boolean }) {
  const failed = item.status === 'FAILED'
  const success = item.status === 'SUCCESS'
  const canceled = item.status === 'CANCELED'
  const terminal = ['SUCCESS', 'FAILED', 'CANCELED'].includes(item.status)
  return <article className={`mobile-batch-item-card ${failed ? 'is-error' : ''}`}><div className={`mobile-batch-item-main ${success ? 'is-readable' : ''}`} role={success ? 'button' : undefined} tabIndex={success ? 0 : undefined} onClick={success && !busy ? onRead : undefined} onKeyDown={(event) => { if (success && !busy && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); onRead() } }}><div className={`mobile-history-icon ${statusTone(item.status)}`}><IconFileVideo /></div><div className="mobile-history-content"><strong>{item.title || `第 ${item.position} 集`}</strong><span>{itemStatusLabel(item.status)}{item.attempts > 1 ? ` · 已尝试 ${item.attempts} 次` : ''}</span></div><Tag color={failed ? 'red' : success ? 'green' : canceled ? 'gray' : 'arcoblue'}>{itemStatusLabel(item.status)}</Tag></div>{failed ? <div className="mobile-batch-error-compact"><IconClose />失败任务已收起，阅读和导出不可用<span>{item.message ? item.message.slice(0, 80) : '可以重试或删除该子任务'}</span></div> : null}<div className="mobile-batch-item-actions"><button type="button" disabled={!success || busy} onClick={onRead}>阅读</button><button type="button" disabled={!success || busy} onClick={onExport}><IconDownload /> 导出</button><button type="button" disabled={busy} onClick={onEvents}>日志</button>{!terminal && <button type="button" disabled={busy} onClick={onCancel}>停止</button>}{(failed || canceled) && <button type="button" disabled={busy} onClick={onRetry}><IconRefresh /> 重试</button>}<button type="button" disabled={!terminal || busy} onClick={onDelete}><IconDelete /> 删除</button></div></article>
}

function BatchDetailDrawer({ task, onClose, onEdit, onAppend, onTaskChange, onOpenReader }: { task?: BatchTask; onClose: () => void; onEdit: (task: BatchTask) => void; onAppend: (task: BatchTask) => void; onTaskChange: (task: BatchTask) => void; onOpenReader: BatchWorkspaceProps['onOpenReader'] }) {
  const [items, setItems] = useState<BatchItem[]>([])
  const [events, setEvents] = useState<Array<{ id: number; status: string; message?: string; created_at?: string }>>([])
  const [eventsItemId, setEventsItemId] = useState<string>()
  const [loading, setLoading] = useState(false)
  const [busyId, setBusyId] = useState<string>()
  const taskId = task?.id
  const load = useCallback(async () => {
    if (!taskId) return
    setLoading(true)
    try { const [updatedTask, updatedItems] = await Promise.all([api.get<BatchTask>(`/batch/tasks/${taskId}`), api.get<BatchItem[]>(`/batch/tasks/${taskId}/items`)]); onTaskChange(updatedTask); setItems(updatedItems) } catch (error) { Message.error(error instanceof Error ? error.message : '读取合集详情失败') } finally { setLoading(false) }
  }, [onTaskChange, taskId])
  useEffect(() => { setItems([]); setEvents([]); setEventsItemId(undefined); if (taskId) void load() }, [load, taskId])
  useEffect(() => { if (!taskId || !task || isTerminalBatch(task.status)) return; const timer = window.setInterval(() => void load(), 3500); return () => window.clearInterval(timer) }, [load, task, taskId])
  const action = async (item: BatchItem, fn: () => Promise<void>) => { try { setBusyId(item.id); await fn(); await load() } catch (error) { Message.error(error instanceof Error ? error.message : '操作失败') } finally { setBusyId(undefined) } }
  const read = async (item: BatchItem) => { if (!item.task_id) return; await action(item, async () => { const detail = await api.get<any>(`/task_status/${item.task_id}`); if (!detail.result?.markdown) throw new Error('笔记内容暂不可阅读'); onOpenReader({ taskId: item.task_id as string, title: item.title, markdown: detail.result.markdown, sourceUrl: item.platform === 'local' ? undefined : item.source_url, exportPath: `/batch/tasks/${taskId}/items/${item.id}/export`, backLabel: '合集', returnTab: 'batch' }) }) }
  const exportItem = async (item: BatchItem) => { await action(item, async () => { const blob = await api.download(`/batch/tasks/${taskId}/items/${item.id}/export`); downloadBlob(blob, `${item.title || 'note'}.zip`) }) }
  const exportAll = async () => { if (!taskId) return; try { const blob = await api.download(`/batch/tasks/${taskId}/export`); downloadBlob(blob, `${task?.title || '合集笔记'}.zip`) } catch (error) { Message.error(error instanceof Error ? error.message : '导出失败') } }
  const retry = async (item: BatchItem) => { if (!taskId) return; await action(item, async () => { await api.post(`/batch/tasks/${taskId}/items/${item.id}/retry`); Message.success('已加入重试队列') }) }
  const cancel = async (item: BatchItem) => { if (!taskId) return; await action(item, async () => { await api.post(`/batch/tasks/${taskId}/items/${item.id}/cancel`); Message.success('已请求停止该任务') }) }
  const remove = async (item: BatchItem) => { if (!taskId) return; await action(item, async () => { await api.delete(`/batch/tasks/${taskId}/items/${item.id}`); Message.success('子任务已删除') }) }
  const showEvents = async (item: BatchItem) => { if (!taskId) return; try { setEventsItemId(item.id); setEvents(await api.get<Array<{ id: number; status: string; message?: string; created_at?: string }>>(`/batch/tasks/${taskId}/items/${item.id}/events`)) } catch (error) { Message.error(error instanceof Error ? error.message : '读取日志失败') } }
  return <Drawer className="mobile-batch-detail-drawer" visible={Boolean(task)} placement="right" width="100vw" title={<div className="mobile-batch-detail-title"><span>{task?.title || '合集详情'}</span><small>{task ? batchStatusLabel(task.status) : ''}</small></div>} onCancel={onClose} footer={null} unmountOnExit>{task && <div className="mobile-batch-detail-body"><section className="mobile-batch-overview"><div><strong>{task.progress}%</strong><span>整体进度</span></div><div><strong>{task.completed_count}</strong><span>已完成</span></div><div><strong>{task.failed_count}</strong><span>失败</span></div><div><strong>{task.canceled_count}</strong><span>取消</span></div><div><strong>{task.total_count}</strong><span>视频</span></div><Progress percent={task.progress} showText={false} /></section><div className="mobile-batch-toolbar"><Button icon={<IconEdit />} onClick={() => onEdit(task)}>编辑</Button>{canAppendBatch(task.status) && <Button type="primary" icon={<IconPlus />} onClick={() => onAppend(task)}>追加</Button>}<Button icon={<IconDownload />} onClick={() => void exportAll()}>导出合集</Button><Button icon={<IconRefresh />} onClick={() => void load()}>刷新</Button></div><div className="mobile-batch-items-heading"><strong>视频列表</strong><span>{loading ? '同步中…' : `${items.length} 个视频`}</span></div><div className="mobile-batch-items-list">{items.length ? items.map((item) => <BatchItemCard key={item.id} item={item} busy={busyId === item.id} onRead={() => void read(item)} onExport={() => void exportItem(item)} onRetry={() => void retry(item)} onCancel={() => void cancel(item)} onDelete={() => void remove(item)} onEvents={() => void showEvents(item)} />) : <Empty description="暂无子任务" />}</div>{eventsItemId && <section className="mobile-batch-events"><div><strong>运行日志</strong><button type="button" onClick={() => setEventsItemId(undefined)} aria-label="关闭日志"><IconClose /></button></div>{events.length ? events.map((event) => <p key={event.id}><time>{event.created_at ? new Date(event.created_at).toLocaleTimeString() : '--:--'}</time><b>{itemStatusLabel(event.status)}</b><span>{event.message || '状态已更新'}</span></p>) : <Empty description="暂无运行事件" />}</section>}</div>}</Drawer>
}

export default function MobileBatchWorkspace({ providers, models, onOpenReader }: BatchWorkspaceProps) {
  const [tasks, setTasks] = useState<BatchTask[]>([])
  const [loading, setLoading] = useState(false)
  const [createVisible, setCreateVisible] = useState(false)
  const [active, setActive] = useState<BatchTask>()
  const [editTarget, setEditTarget] = useState<BatchTask>()
  const [appendTarget, setAppendTarget] = useState<BatchTask>()
  const load = useCallback(async () => { try { setLoading(true); const result = await api.get<{ items: BatchTask[] }>('/batch/tasks?page=1&page_size=50'); setTasks(result.items || []) } catch (error) { Message.error(error instanceof Error ? error.message : '读取合集任务失败') } finally { setLoading(false) } }, [])
  useEffect(() => { void load() }, [load])
  const updateTask = useCallback((updated: BatchTask) => { setTasks((current) => current.map((item) => item.id === updated.id ? updated : item)); setActive((current) => current?.id === updated.id ? updated : current) }, [])
  const running = tasks.filter((task) => ['RUNNING', 'PENDING'].includes(task.status)).length
  const finished = tasks.filter((task) => task.status === 'SUCCESS').length
  const failed = tasks.filter((task) => ['FAILED', 'PARTIAL_FAILED', 'CANCELED'].includes(task.status)).length
  return <div className="mobile-batch-workspace"><header className="mobile-simple-header"><div><span className="mobile-eyebrow">COLLECTION NOTES</span><h1>合集笔记</h1></div><button type="button" className="mobile-header-action" onClick={() => void load()} aria-label="刷新合集"><IconRefresh /></button></header><section className="mobile-batch-create-hero"><div><IconApps /><div><strong>把一组视频整理成课程</strong><span>支持 B 站合集或本地视频文件夹</span></div></div><button type="button" onClick={() => setCreateVisible(true)} aria-label="新建合集"><IconPlus /></button></section><div className="mobile-library-stat"><div><strong>{tasks.length}</strong><span>全部合集</span></div><div><strong>{running}</strong><span>处理中</span></div><div><strong>{finished}</strong><span>已完成</span></div><div><strong>{failed}</strong><span>需处理</span></div></div><section className="mobile-batch-list">{loading && !tasks.length ? <div className="mobile-loading-state"><IconLoading /> 正在同步合集</div> : tasks.length ? tasks.map((task) => <article key={task.id} className="mobile-batch-task-card" onClick={() => setActive(task)}><div className="mobile-batch-task-main"><div className={`mobile-history-icon ${statusTone(task.status)}`}><IconApps /></div><div className="mobile-history-content"><strong>{task.title || '未命名合集'}</strong><span>{task.source_type === 'local' ? '本地文件夹' : '哔哩哔哩合集'} · {task.total_count} 个视频</span></div><Tag color={statusTone(task.status) === 'danger' ? 'red' : statusTone(task.status) === 'success' ? 'green' : 'arcoblue'}>{batchStatusLabel(task.status)}</Tag></div><div className="mobile-batch-task-progress"><Progress percent={task.progress} showText={false} /><span>{task.completed_count}/{task.total_count} 已完成</span></div><div className="mobile-batch-task-actions"><button type="button" onClick={(event) => { event.stopPropagation(); setActive(task) }}>查看详情</button><button type="button" onClick={(event) => { event.stopPropagation(); setEditTarget(task) }}><IconEdit /> 编辑</button>{canAppendBatch(task.status) && <button type="button" onClick={(event) => { event.stopPropagation(); setAppendTarget(task) }}><IconPlus /> 追加</button>}</div></article>) : <div className="mobile-empty-home"><IconApps /><strong>还没有合集笔记</strong><span>从一组视频开始建立你的课程笔记</span><Button type="primary" icon={<IconPlus />} onClick={() => setCreateVisible(true)}>新建合集</Button></div>}</section><BatchCreateDrawer visible={createVisible} providers={providers} models={models} onClose={() => setCreateVisible(false)} onCreated={(task) => { setTasks((current) => [task, ...current]); setActive(task) }} /><BatchEditDrawer task={editTarget} providers={providers} models={models} onClose={() => setEditTarget(undefined)} onUpdated={updateTask} /><BatchAppendDrawer task={appendTarget} onClose={() => setAppendTarget(undefined)} onUpdated={updateTask} /><BatchDetailDrawer task={active} onClose={() => setActive(undefined)} onEdit={(task) => setEditTarget(task)} onAppend={(task) => setAppendTarget(task)} onTaskChange={updateTask} onOpenReader={onOpenReader} /></div>
}
