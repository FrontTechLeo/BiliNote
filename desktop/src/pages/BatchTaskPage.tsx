import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, saveBlob, terminalStatuses } from '../api'
import type { BatchItem, BatchTask, PreviewItem } from '../api'
import { desktop } from '../ipc'
import { Checkbox, Drawer, EmptyState, Field, Modal, ProgressBar, StatusTag, useConfirm, useReader, useToast } from '../ui'
import { BATCH_DEFAULTS, ModelFields, NoteOptions, normaliseOptions, useProviders } from './GenerateForm'
import { userFacingMarkdown } from './SingleTaskPage'

const appendableStatuses = ['PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELED']
const editableConfigStatuses = ['SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELED']

type NoteValues = typeof BATCH_DEFAULTS & { providerId: number | string | ''; modelName: string }
type StepMode = 'source' | 'confirm'

function Steps({ step, labels }: { step: number; labels: [string, string] }) {
  return (
    <div className="steps">
      {[0, 1].map((index) => (
        <div key={index} className={`step ${index === step ? 'active' : ''} ${index < step ? 'done' : ''}`}>
          <span className="step-dot">{index < step ? '✓' : index + 1}</span>
          <span>{labels[index]}</span>
        </div>
      ))}
    </div>
  )
}

// ---------- 新建批量任务 ----------

function CreateBatchModal({ visible, onClose, onCreated }: { visible: boolean; onClose: () => void; onCreated: () => void }) {
  const toast = useToast()
  const { providers, models } = useProviders()
  const [step, setStep] = useState<StepMode>('source')
  const [sourceType, setSourceType] = useState<'bilibili' | 'local'>('bilibili')
  const [sourceUrl, setSourceUrl] = useState('')
  const [localPaths, setLocalPaths] = useState<string[]>([])
  const [preview, setPreview] = useState<{ title: string; items: PreviewItem[] }>()
  const [selected, setSelected] = useState<string[]>([])
  const [values, setValues] = useState<NoteValues>({ ...BATCH_DEFAULTS, providerId: '', modelName: '' })
  const [taskName, setTaskName] = useState('')
  const [loading, setLoading] = useState(false)

  const patchValues = (patch: Partial<NoteValues>) => setValues((current) => ({ ...current, ...patch }))
  const reset = () => {
    setStep('source'); setPreview(undefined); setSelected([]); setLocalPaths([])
    setSourceUrl(''); setTaskName('')
    setValues({ ...BATCH_DEFAULTS, providerId: '', modelName: '' })
    onClose()
  }

  const pickFolder = async () => {
    const picked = await desktop.pickFolder()
    if (picked && picked.length) setLocalPaths(picked)
  }
  const pickFiles = async () => {
    const picked = await desktop.pickFiles()
    if (picked && picked.length) setLocalPaths((current) => {
      const unique = new Set(current)
      picked.forEach((path) => unique.add(path))
      return [...unique]
    })
  }

  const analyse = async () => {
    try {
      setLoading(true)
      let result: { title: string; items: PreviewItem[] }
      if (sourceType === 'bilibili') {
        if (!sourceUrl.trim()) throw new Error('请输入哔哩哔哩合集或视频链接')
        result = await api.post('/batch/preview/bilibili', { source_url: sourceUrl.trim() })
      } else {
        if (!localPaths.length) throw new Error('请选择本地视频文件或文件夹')
        result = await api.post('/batch/preview/local_paths', { paths: localPaths })
      }
      setPreview(result)
      setSelected((result.items || []).map((item) => item.source_url))
      setStep('confirm')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '解析失败')
    } finally {
      setLoading(false)
    }
  }

  const create = async () => {
    if (!preview) return
    if (!values.providerId || !values.modelName) {
      toast('warn', '请选择模型供应商与 AI 模型')
      return
    }
    if (!selected.length) {
      toast('warn', '至少选择一个视频')
      return
    }
    setLoading(true)
    try {
      const { providerId, modelName, ...rest } = values
      const config = normaliseOptions(rest) as Record<string, unknown>
      await api.post('/batch/tasks', {
        title: taskName.trim() || preview.title,
        source_type: sourceType,
        source_value: sourceType === 'bilibili' ? sourceUrl.trim() : localPaths[0],
        config: {
          ...config,
          provider_id: providerId,
          model_name: modelName,
          link: values.format.includes('link'),
          screenshot: values.format.includes('screenshot'),
        },
        items: preview.items.filter((item) => selected.includes(item.source_url)),
      })
      toast('ok', '批量任务已创建并开始执行')
      onCreated()
      reset()
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '创建失败')
    } finally {
      setLoading(false)
    }
  }

  if (!visible) return null
  return (
    <Modal title="新建批量合集任务" width={880} onClose={reset}>
      <Steps step={step === 'source' ? 0 : 1} labels={['分析视频列表', '配置生成任务']} />
      {step === 'source' ? (
        <>
          <Field label="批量任务类型">
            <div className="radio-row">
              <label className="chk">
                <input type="radio" checked={sourceType === 'bilibili'} onChange={() => setSourceType('bilibili')} />
                <span>哔哩哔哩合集</span>
              </label>
              <label className="chk">
                <input type="radio" checked={sourceType === 'local'} onChange={() => setSourceType('local')} />
                <span>本地视频（直接读取磁盘，不上传）</span>
              </label>
            </div>
          </Field>
          {sourceType === 'bilibili' ? (
            <Field label="合集链接" required>
              <input className="input" value={sourceUrl} onChange={(event) => setSourceUrl(event.target.value)}
                placeholder="例如 https://www.bilibili.com/video/BV1rv7A6oEeP/" />
            </Field>
          ) : (
            <Field label="本地视频" required hint="选择文件夹时自动递归发现视频并按文件名自然排序">
              <div className="row">
                <button type="button" className="btn" onClick={() => void pickFolder()}>选择文件夹</button>
                <button type="button" className="btn" onClick={() => void pickFiles()}>选择视频文件</button>
                <span className="dim">{localPaths.length ? `已选择 ${localPaths.length} 个文件` : '尚未选择'}</span>
              </div>
              {localPaths.length > 0 && (
                <div className="path-list">
                  {localPaths.slice(0, 6).map((path) => <div key={path} className="ellipsis dim">{path}</div>)}
                  {localPaths.length > 6 && <div className="dim">…等 {localPaths.length} 个文件</div>}
                </div>
              )}
            </Field>
          )}
          <div className="row end">
            <button type="button" className="btn ghost" onClick={reset}>取消</button>
            <button type="button" className="btn primary" disabled={loading} onClick={() => void analyse()}>
              {loading ? '分析中…' : '分析视频'}
            </button>
          </div>
        </>
      ) : (
        <>
          <PreviewTable preview={preview} selected={selected} setSelected={setSelected} />
          <div className="form-grid" style={{ marginTop: 16 }}>
            <Field label="任务名称（可选）" hint={`不填写时使用「${preview?.title || '批量任务'}」`}>
              <input className="input" value={taskName} maxLength={100} onChange={(event) => setTaskName(event.target.value)}
                placeholder="为本次批量任务设置便于识别的名称" />
            </Field>
            <ModelFields providerId={values.providerId} modelName={values.modelName}
              onChange={(patch) => patchValues(patch)} providers={providers} models={models} />
            <NoteOptions values={values} onChange={(patch) => patchValues(patch)} />
          </div>
          <div className="row end">
            <button type="button" className="btn" onClick={() => setStep('source')}>上一步</button>
            <button type="button" className="btn primary" disabled={loading} onClick={() => void create()}>开始批量任务</button>
          </div>
        </>
      )}
    </Modal>
  )
}

// ---------- 追加视频 ----------

function AppendBatchModal({ task, onClose, onAppended }: { task?: BatchTask; onClose: () => void; onAppended: (task: BatchTask) => void }) {
  const toast = useToast()
  const [step, setStep] = useState<StepMode>('source')
  const [sourceType, setSourceType] = useState<'bilibili' | 'local'>('bilibili')
  const [sourceUrl, setSourceUrl] = useState('')
  const [localPaths, setLocalPaths] = useState<string[]>([])
  const [preview, setPreview] = useState<{ title?: string; items: PreviewItem[]; discovered_count: number; skipped_count: number }>()
  const [selected, setSelected] = useState<string[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (task) {
      const type = task.source_type === 'local' ? 'local' : 'bilibili'
      setSourceType(type)
      setSourceUrl(task.source_type === 'bilibili' ? task.source_value || '' : '')
      setStep('source')
      setPreview(undefined)
      setSelected([])
      setLocalPaths([])
    }
  }, [task])

  const reset = () => { setPreview(undefined); setSelected([]); setLocalPaths([]); setSourceUrl(''); onClose() }

  const analyse = async () => {
    if (!task) return
    try {
      setLoading(true)
      let result: { title?: string; items: PreviewItem[]; discovered_count: number; skipped_count: number }
      if (sourceType === 'bilibili') {
        if (!sourceUrl.trim()) throw new Error('请输入链接')
        result = await api.post(`/batch/tasks/${task.id}/preview/bilibili`, { source_url: sourceUrl.trim() })
      } else {
        if (!localPaths.length) throw new Error('请选择本地视频文件或文件夹')
        result = await api.post(`/batch/tasks/${task.id}/preview/local_paths`, { paths: localPaths })
      }
      setPreview(result)
      setSelected((result.items || []).map((item) => item.source_url))
      setStep('confirm')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '分析新增视频失败')
    } finally {
      setLoading(false)
    }
  }

  const append = async () => {
    if (!task || !preview) return
    if (!selected.length) { toast('warn', '至少选择一个新增视频'); return }
    setLoading(true)
    try {
      const result = await api.post<{ task: BatchTask; added_count: number; skipped_count: number }>(
        `/batch/tasks/${task.id}/items`,
        { items: preview.items.filter((item) => selected.includes(item.source_url)) },
      )
      toast('ok', `已追加 ${result.added_count} 个视频，任务重新进入执行队列`)
      onAppended(result.task)
      reset()
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '追加视频失败')
    } finally {
      setLoading(false)
    }
  }

  if (!task) return null
  return (
    <Modal title={`追加视频 · ${task.title}`} width={820} onClose={reset}>
      <p className="banner info">
        新增视频将沿用原批次的模型、笔记格式和视频理解配置；执行中也可以继续追加，已加入过的相同内容会自动过滤。
      </p>
      <Steps step={step === 'source' ? 0 : 1} labels={[sourceType === 'local' ? '选择本地视频' : '解析远程合集', '确认新增视频']} />
      {step === 'source' ? (
        <>
          <Field label="追加来源">
            <div className="radio-row">
              <label className="chk">
                <input type="radio" checked={sourceType === 'bilibili'} onChange={() => { setSourceType('bilibili'); setPreview(undefined) }} />
                <span>B站链接</span>
              </label>
              <label className="chk">
                <input type="radio" checked={sourceType === 'local'} onChange={() => { setSourceType('local'); setPreview(undefined) }} />
                <span>本地视频</span>
              </label>
            </div>
          </Field>
          {sourceType === 'bilibili' ? (
            <Field label="哔哩哔哩合集或视频链接" required>
              <input className="input" value={sourceUrl} onChange={(event) => setSourceUrl(event.target.value)}
                placeholder="默认使用原合集链接，也可以粘贴其它 B 站视频或合集链接" />
            </Field>
          ) : (
            <Field label="本地视频" required>
              <div className="row">
                <button type="button" className="btn" onClick={() => void desktop.pickFolder().then((p) => p && setLocalPaths((cur) => [...new Set([...cur, ...p])]))}>选择文件夹</button>
                <button type="button" className="btn" onClick={() => void desktop.pickFiles().then((p) => p && setLocalPaths((cur) => [...new Set([...cur, ...p])]))}>选择视频文件</button>
                <span className="dim">{localPaths.length ? `已选择 ${localPaths.length} 个文件` : '尚未选择'}</span>
              </div>
              {localPaths.length > 0 && (
                <div className="path-list">
                  {localPaths.slice(0, 6).map((path) => <div key={path} className="ellipsis dim">{path}</div>)}
                  {localPaths.length > 6 && <div className="dim">…等 {localPaths.length} 个文件</div>}
                </div>
              )}
            </Field>
          )}
          <div className="row end">
            <button type="button" className="btn ghost" onClick={reset}>取消</button>
            <button type="button" className="btn primary" disabled={loading} onClick={() => void analyse()}>
              {loading ? '分析中…' : '分析新增视频'}
            </button>
          </div>
        </>
      ) : (
        <>
          <p className={`banner ${preview?.items.length ? 'ok' : 'warn'}`}>
            共发现 {preview?.discovered_count || 0} 个视频，已过滤 {preview?.skipped_count || 0} 个已有视频，可追加 {preview?.items.length || 0} 个。
          </p>
          <PreviewTable preview={preview} selected={selected} setSelected={setSelected} emptyHint="没有发现可追加的新视频" />
          <div className="row end">
            <button type="button" className="btn" onClick={() => setStep('source')}>重新选择</button>
            <button type="button" className="btn primary" disabled={!selected.length || loading} onClick={() => void append()}>
              追加并开始处理
            </button>
          </div>
        </>
      )}
    </Modal>
  )
}

// ---------- 编辑批量任务 ----------

function EditBatchModal({ task, onClose, onUpdated }: { task?: BatchTask; onClose: () => void; onUpdated: (task: BatchTask) => void }) {
  const toast = useToast()
  const { providers, models } = useProviders()
  const [values, setValues] = useState<NoteValues>({ ...BATCH_DEFAULTS, providerId: '', modelName: '' })
  const [title, setTitle] = useState('')
  const [saving, setSaving] = useState(false)

  const canEditConfig = Boolean(task && editableConfigStatuses.includes(task.status))

  useEffect(() => {
    if (!task) return
    setTitle(task.title)
    const config = (task.config || {}) as Record<string, unknown>
    const grid = Array.isArray(config.grid_size) ? config.grid_size.join(',') : '2,2'
    setValues((current) => ({
      ...current,
      quality: String(config.quality || 'medium'),
      style: String(config.style || 'tutorial'),
      format: Array.isArray(config.format) ? (config.format as string[]) : [],
      video_understanding: Boolean(config.video_understanding),
      image_compression: String(config.image_compression || 'high'),
      video_interval: Number(config.video_interval) || 6,
      grid_size: String(grid),
      extras: String(config.extras || ''),
      providerId: (config.provider_id as number | undefined) ?? '',
      modelName: String(config.model_name || ''),
    }))
  }, [task])

  const save = async () => {
    if (!task) return
    if (!title.trim()) { toast('warn', '请输入任务名称'); return }
    setSaving(true)
    try {
      const payload: Record<string, unknown> = { title: title.trim() }
      if (canEditConfig) {
        const { providerId, modelName, ...rest } = values
        const config = normaliseOptions(rest) as Record<string, unknown>
        payload.config = {
          ...config,
          provider_id: providerId,
          model_name: modelName,
          link: values.format.includes('link'),
          screenshot: values.format.includes('screenshot'),
        }
      }
      const updated = await api.patch<BatchTask>(`/batch/tasks/${task.id}`, payload)
      toast('ok', '批量任务设置已更新')
      onUpdated(updated)
      onClose()
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '更新失败')
    } finally {
      setSaving(false)
    }
  }

  if (!task) return null
  return (
    <Modal title="编辑批量任务" width={760} onClose={onClose}>
      <Field label="任务名称" required>
        <input className="input" value={title} maxLength={100} onChange={(event) => setTitle(event.target.value)} />
      </Field>
      {canEditConfig ? (
        <>
          <p className="banner info">设置修改后将用于后续追加的视频，以及失败子任务的再次重试；已经完成的笔记不会自动重新生成。</p>
          <div className="form-grid">
            <ModelFields providerId={values.providerId} modelName={values.modelName}
              onChange={(patch) => setValues((current) => ({ ...current, ...patch }))}
              providers={providers} models={models} />
            <NoteOptions values={values} onChange={(patch) => setValues((current) => ({ ...current, ...patch }))} />
          </div>
        </>
      ) : (
        <p className="banner warn">任务正在执行，目前只能修改名称；生成设置需在本批任务结束后修改。</p>
      )}
      <div className="row end">
        <button type="button" className="btn ghost" onClick={onClose}>取消</button>
        <button type="button" className="btn primary" disabled={saving} onClick={() => void save()}>保存修改</button>
      </div>
    </Modal>
  )
}

// ---------- 预览列表（新建/追加共用） ----------

function PreviewTable({ preview, selected, setSelected, emptyHint }: {
  preview?: { items: PreviewItem[] }
  selected: string[]
  setSelected: (next: string[]) => void
  emptyHint?: string
}) {
  const items = preview?.items || []
  const allChecked = items.length > 0 && selected.length === items.length
  return (
    <div className="preview-list">
      {items.length === 0 ? (
        <EmptyState title={emptyHint || '没有可处理的视频'} />
      ) : (
        <table className="table compact">
          <thead>
            <tr>
              <th style={{ width: 36 }}>
                <Checkbox checked={allChecked} onChange={(next) => setSelected(next ? items.map((item) => item.source_url) : [])}>
                  {''}
                </Checkbox>
              </th>
              <th style={{ width: 64 }}>序号</th>
              <th>视频标题</th>
              <th style={{ width: 110 }}>来源</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, index) => (
              <tr key={item.source_url}>
                <td>
                  <Checkbox checked={selected.includes(item.source_url)}
                    onChange={(next) => setSelected(next ? [...selected, item.source_url] : selected.filter((id) => id !== item.source_url))}>
                    {''}
                  </Checkbox>
                </td>
                <td className="dim">{item.position ?? index + 1}</td>
                <td className="ellipsis" style={{ maxWidth: 420 }}>{item.title}</td>
                <td><span className="pill">{item.platform}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

// ---------- 批量任务详情（子任务列表 + 运行日志） ----------

function BatchDetail({ task, onClose, onAppend, onEdit, onTaskChange }: {
  task?: BatchTask
  onClose: () => void
  onAppend: (task: BatchTask) => void
  onEdit: (task: BatchTask) => void
  onTaskChange: (task: BatchTask) => void
}) {
  const toast = useToast()
  const confirm = useConfirm()
  const openReader = useReader()
  const [items, setItems] = useState<BatchItem[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [tab, setTab] = useState<'items' | 'logs'>('items')
  const [events, setEvents] = useState<{ created_at?: string; status: string; message?: string }[]>([])
  const [busyId, setBusyId] = useState<string>()
  const loadRequestRef = useRef(0)
  const taskId = task?.id

  useEffect(() => {
    loadRequestRef.current += 1
    setItems([])
    setSelected([])
    setEvents([])
    setTab('items')
  }, [taskId])

  const load = useCallback(async () => {
    if (!taskId) return
    const requestId = ++loadRequestRef.current
    const [updatedTask, updatedItems] = await Promise.all([
      api.get<BatchTask>(`/batch/tasks/${taskId}`),
      api.get<BatchItem[]>(`/batch/tasks/${taskId}/items`),
    ])
    if (requestId !== loadRequestRef.current) return
    setItems(updatedItems || [])
    onTaskChange(updatedTask)
  }, [onTaskChange, taskId])

  useEffect(() => { void load().catch((error: Error) => toast('bad', error.message)) }, [load, toast])

  const hasActiveItems = items.some((item) => !terminalStatuses.includes(item.status))
  const batchIsActive = Boolean(task && ['PENDING', 'RUNNING'].includes(task.status))
  useEffect(() => {
    if (!taskId || (!batchIsActive && !hasActiveItems)) return
    const timer = window.setInterval(() => void load().catch(() => undefined), 2500)
    return () => window.clearInterval(timer)
  }, [batchIsActive, hasActiveItems, load, taskId])

  const selectableItems = items.filter((item) => terminalStatuses.includes(item.status))
  const allSelected = selectableItems.length > 0 && selectableItems.every((item) => selected.includes(item.id))

  const toggleSelect = (item: BatchItem, next: boolean) => {
    setSelected((current) => next ? [...current, item.id] : current.filter((id) => id !== item.id))
  }

  const showPreview = async (item: BatchItem) => {
    try {
      setBusyId(item.id)
      const result = await api.get<{ result?: { markdown?: string; audio_meta?: { title?: string } } }>(`/task_status/${item.task_id}`)
      const markdown = result.result?.markdown
      if (!markdown) {
        toast('warn', '笔记内容暂不可预览')
        return
      }
      openReader({
        markdown: userFacingMarkdown(markdown, item.platform),
        title: item.title || result.result?.audio_meta?.title || '视频笔记',
        sourceUrl: item.platform === 'local' ? undefined : item.source_url,
        taskId: item.task_id,
        hasImages: markdown.includes('/static/screenshots/'),
      })
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '读取笔记失败')
    } finally {
      setBusyId(undefined)
    }
  }

  const exportItems = async (ids?: string[]) => {
    try {
      const blob = await api.download(`/batch/tasks/${taskId}/export`, ids ? { item_ids: ids } : undefined)
      saveBlob(blob, `${task?.title || 'notes'}.zip`)
      toast('ok', '导出成功')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '导出失败')
    }
  }

  const retry = async (item: BatchItem) => {
    if (!taskId) return
    try {
      setBusyId(item.id)
      await api.post(`/batch/tasks/${taskId}/items/${item.id}/retry`)
      toast('ok', '已加入重试队列')
      await load()
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '重试失败')
    } finally {
      setBusyId(undefined)
    }
  }

  const cancelItem = async (item: BatchItem) => {
    if (!taskId) return
    const ok = await confirm({
      title: '停止该子任务？',
      content: '当前正在执行的模型请求返回后将停止，已有转写和检查点会保留。',
      danger: true, okText: '停止',
    })
    if (!ok) return
    try {
      setBusyId(item.id)
      await api.post(`/batch/tasks/${taskId}/items/${item.id}/cancel`)
      toast('ok', '已请求停止该任务')
      await load()
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '停止任务失败')
    } finally {
      setBusyId(undefined)
    }
  }

  const deleteItem = async (item: BatchItem) => {
    if (!taskId) return
    const ok = await confirm({
      title: '删除该子任务？',
      content: '对应笔记、缓存、截图和无引用的本地源文件将一并清理，此操作不可恢复。',
      danger: true, okText: '确认删除',
    })
    if (!ok) return
    try {
      setBusyId(item.id)
      const result = await api.delete<{ task: BatchTask; deleted_count: number }>(`/batch/tasks/${taskId}/items/${item.id}`)
      applyDeletion(result, [item.id])
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '删除失败')
    } finally {
      setBusyId(undefined)
    }
  }

  const deleteSelected = async () => {
    if (!taskId || !selected.length) return
    const ok = await confirm({
      title: `删除选中的 ${selected.length} 个子任务？`,
      content: '对应笔记、缓存、截图和无引用的本地源文件将一并清理，此操作不可恢复。',
      danger: true, okText: '确认删除',
    })
    if (!ok) return
    try {
      const result = await api.post<{ task: BatchTask; deleted_count: number }>(`/batch/tasks/${taskId}/items/delete`, { item_ids: [...selected] })
      applyDeletion(result, [...selected])
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '批量删除失败')
    }
  }

  const applyDeletion = async (result: { task: BatchTask; deleted_count: number }, deletedIds: string[]) => {
    setSelected((current) => current.filter((id) => !deletedIds.includes(id)))
    onTaskChange(result.task)
    await load()
    toast('ok', `已删除 ${result.deleted_count} 个子任务及相关资源`)
  }

  const showEvents = async (item: BatchItem) => {
    if (!taskId) return
    try {
      const list = await api.get<{ created_at?: string; status: string; message?: string }[]>(`/batch/tasks/${taskId}/items/${item.id}/events`)
      setEvents(list || [])
      setTab('logs')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '读取日志失败')
    }
  }

  if (!task) return null
  return (
    <Drawer title={`批量任务：${task.title}`} width={1180} onClose={onClose}>
      <div className="tab-switch">
        <button type="button" className={tab === 'items' ? 'active' : ''} onClick={() => setTab('items')}>子任务</button>
        <button type="button" className={tab === 'logs' ? 'active' : ''} onClick={() => setTab('logs')}>运行日志</button>
      </div>

      {tab === 'items' && (
        <>
          <div className="desc-grid">
            <div className="desc-item"><span>整体状态</span><StatusTag value={task.status} /></div>
            <div className="desc-item"><span>总体进度</span><ProgressBar percent={task.progress} /></div>
            <div className="desc-item"><span>成功 / 失败 / 取消</span><strong>{task.completed_count} / {task.failed_count} / {task.canceled_count ?? 0}</strong></div>
            <div className="desc-item"><span>视频总数</span><strong>{task.total_count}</strong></div>
          </div>
          <div className="toolbar" style={{ margin: '12px 0' }}>
            <button type="button" className="btn" onClick={() => onEdit(task)}>编辑设置</button>
            {appendableStatuses.includes(task.status) && (
              <button type="button" className="btn primary" onClick={() => onAppend(task)}>追加视频</button>
            )}
            <button type="button" className="btn" onClick={() => void exportItems()}>导出全部</button>
            <button type="button" className="btn" disabled={!selected.length} onClick={() => void exportItems(selected)}>导出所选</button>
            <button type="button" className="btn danger ghost" disabled={!selected.length} onClick={() => void deleteSelected()}>批量删除</button>
          </div>
          {items.length === 0 ? <EmptyState title="暂无子任务" /> : (
            <table className="table compact">
              <thead>
                <tr>
                  <th style={{ width: 36 }}>
                    <Checkbox checked={allSelected}
                      onChange={(next) => setSelected(next ? selectableItems.map((item) => item.id) : [])}>
                      {''}
                    </Checkbox>
                  </th>
                  <th style={{ width: 56 }}>序号</th>
                  <th style={{ width: 240 }}>视频</th>
                  <th style={{ width: 130 }}>状态</th>
                  <th>日志</th>
                  <th style={{ width: 60 }}>次数</th>
                  <th className="right" style={{ width: 280 }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => {
                  const canDelete = terminalStatuses.includes(item.status)
                  return (
                    <tr key={item.id}>
                      <td>
                        <Checkbox checked={selected.includes(item.id)} disabled={!canDelete}
                          onChange={(next) => toggleSelect(item, next)}>
                          {''}
                        </Checkbox>
                      </td>
                      <td className="dim">{item.position ?? '—'}</td>
                      <td className="ellipsis" style={{ maxWidth: 240 }} title={item.source_url}>{item.title}</td>
                      <td><StatusTag value={item.status} /></td>
                      <td>
                        <button type="button" className="btn text" onClick={() => void showEvents(item)}>
                          <span className="ellipsis" style={{ maxWidth: 220, display: 'inline-block' }}>{item.message || '查看日志'}</span>
                        </button>
                      </td>
                      <td className="dim">{item.attempts ?? 0}</td>
                      <td className="right actions">
                        <button type="button" className="btn text" disabled={item.status !== 'SUCCESS' || busyId === item.id}
                          onClick={() => void showPreview(item)}>阅读</button>
                        <button type="button" className="btn text" disabled={item.status !== 'SUCCESS'}
                          onClick={() => void exportItems([item.id])}>导出</button>
                        {['FAILED', 'CANCELED'].includes(item.status) && (
                          <button type="button" className="btn text warn" disabled={busyId === item.id}
                            onClick={() => void retry(item)}>重试</button>
                        )}
                        {!terminalStatuses.includes(item.status) && (
                          <button type="button" className="btn text warn" disabled={busyId === item.id}
                            onClick={() => void cancelItem(item)}>停止</button>
                        )}
                        <button type="button" className="btn text bad" disabled={!canDelete || busyId === item.id}
                          onClick={() => void deleteItem(item)}>删除</button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </>
      )}

      {tab === 'logs' && (
        <pre className="log-box tall">
          {events.length
            ? events.map((event) => `[${event.created_at || '-'}] ${event.status} ${event.message || ''}`).join('\n')
            : '点击子任务的「日志」列查看运行事件。'}
        </pre>
      )}
    </Drawer>
  )
}

// ---------- 批量任务页 ----------

export default function BatchTaskPage() {
  const toast = useToast()
  const [tasks, setTasks] = useState<BatchTask[]>([])
  const [loading, setLoading] = useState(true)
  const [createVisible, setCreateVisible] = useState(false)
  const [active, setActive] = useState<BatchTask>()
  const [appendTarget, setAppendTarget] = useState<BatchTask>()
  const [editTarget, setEditTarget] = useState<BatchTask>()
  const loadRequestRef = useRef(0)

  const load = useCallback(async () => {
    const requestId = ++loadRequestRef.current
    setLoading(true)
    try {
      const result = await api.get<{ items: BatchTask[] }>('/batch/tasks')
      if (requestId !== loadRequestRef.current) return
      setTasks(result.items || [])
      setActive((current) => current ? (result.items || []).find((item) => item.id === current.id) || current : undefined)
    } catch (error) {
      if (requestId === loadRequestRef.current) toast('bad', error instanceof Error ? error.message : '加载失败')
    } finally {
      if (requestId === loadRequestRef.current) setLoading(false)
    }
  }, [toast])

  useEffect(() => { void load() }, [load])

  const handleTaskChange = useCallback((updated: BatchTask) => {
    setTasks((current) => current.map((item) => item.id === updated.id ? updated : item))
    setActive((current) => current?.id === updated.id ? updated : current)
  }, [])

  const openEdit = useCallback(async (task: BatchTask) => {
    try {
      setEditTarget(await api.get<BatchTask>(`/batch/tasks/${task.id}`))
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '加载任务设置失败')
    }
  }, [toast])

  const hasActiveTasks = tasks.some((item) => ['PENDING', 'RUNNING'].includes(item.status))
  useEffect(() => {
    if (!hasActiveTasks) return
    const timer = window.setInterval(() => void load(), 4000)
    return () => window.clearInterval(timer)
  }, [hasActiveTasks, load])

  const stats = useMemo(() => ({
    running: tasks.filter((item) => item.status === 'RUNNING').length,
    complete: tasks.filter((item) => item.status === 'SUCCESS').length,
    failed: tasks.filter((item) => ['FAILED', 'PARTIAL_FAILED', 'CANCELED'].includes(item.status)).length,
  }), [tasks])

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h2>批量合集任务</h2>
          <p>解析哔哩哔哩公开合集，或直接读取本地视频文件夹，持续生成可导出的系列笔记。</p>
        </div>
        <div className="toolbar">
          <button type="button" className="btn" onClick={() => void load()}>刷新</button>
          <button type="button" className="btn primary" onClick={() => setCreateVisible(true)}>新建批量任务</button>
        </div>
      </header>

      <div className="stat-row">
        <div className="stat"><div className="stat-value">{tasks.length}</div><div className="stat-label">批量任务</div></div>
        <div className="stat"><div className="stat-value">{stats.running}</div><div className="stat-label">正在运行</div></div>
        <div className="stat"><div className="stat-value">{stats.complete}</div><div className="stat-label">已全部完成</div></div>
        <div className="stat"><div className="stat-value">{stats.failed}</div><div className="stat-label">需要处理</div></div>
      </div>

      <section className="card">
        {tasks.length === 0 && !loading ? (
          <EmptyState title="暂无批量任务" hint="点击右上角「新建批量任务」开始" />
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>任务名称</th><th>来源</th><th>状态</th><th style={{ width: 200 }}>进度</th>
                <th>完成 / 失败 / 总计</th><th>创建时间</th><th className="right">操作</th>
              </tr>
            </thead>
            <tbody>
              {tasks.map((record) => (
                <tr key={record.id} className="clickable" onClick={() => setActive(record)}>
                  <td className="ellipsis" style={{ maxWidth: 240 }}>{record.title}</td>
                  <td><span className="pill">{record.source_type === 'local' ? '本地' : record.source_type}</span></td>
                  <td><StatusTag value={record.status} /></td>
                  <td><ProgressBar percent={record.progress} /></td>
                  <td className="dim">{record.completed_count} / {record.failed_count} / {record.total_count}</td>
                  <td className="dim">{record.created_at ? new Date(record.created_at).toLocaleString() : '—'}</td>
                  <td className="right actions" onClick={(event) => event.stopPropagation()}>
                    <button type="button" className="btn text" onClick={() => setActive(record)}>查看</button>
                    <button type="button" className="btn text" onClick={() => void openEdit(record)}>编辑</button>
                    {appendableStatuses.includes(record.status) && (
                      <button type="button" className="btn text" onClick={() => setAppendTarget(record)}>追加</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <CreateBatchModal visible={createVisible} onClose={() => setCreateVisible(false)} onCreated={load} />
      {appendTarget && (
        <AppendBatchModal task={appendTarget} onClose={() => setAppendTarget(undefined)}
          onAppended={(updated) => { handleTaskChange(updated); void load() }} />
      )}
      {editTarget && (
        <EditBatchModal task={editTarget} onClose={() => setEditTarget(undefined)}
          onUpdated={(updated) => { handleTaskChange(updated); setEditTarget(undefined); void load() }} />
      )}
      {active && (
        <BatchDetail task={active} onClose={() => setActive(undefined)}
          onAppend={setAppendTarget} onEdit={(task) => void openEdit(task)} onTaskChange={handleTaskChange} />
      )}
    </div>
  )
}
