import { useCallback, useEffect, useRef, useState } from 'react'
import { api, saveBlob, terminalStatuses } from '../api'
import type { SingleTaskHistory, SingleTaskStatus } from '../api'
import { desktop } from '../ipc'
import { EmptyState, Field, StatusTag, useConfirm, useReader, useToast } from '../ui'
import { ModelFields, NoteOptions, SINGLE_DEFAULTS, normaliseOptions, useProviders } from './GenerateForm'

const PLATFORMS = [
  { label: '哔哩哔哩', value: 'bilibili' },
  { label: 'YouTube', value: 'youtube' },
  { label: '抖音', value: 'douyin' },
  { label: '快手', value: 'kuaishou' },
  { label: '本地视频', value: 'local' },
]

type NoteValues = typeof SINGLE_DEFAULTS & { providerId: number | string | ''; modelName: string }

export default function SingleTaskPage() {
  const toast = useToast()
  const confirm = useConfirm()
  const openReader = useReader()
  const { providers, models } = useProviders()

  const [tab, setTab] = useState<'create' | 'history'>('create')
  const [platform, setPlatform] = useState('bilibili')
  const [videoUrl, setVideoUrl] = useState('')
  const [localPath, setLocalPath] = useState('')
  const [values, setValues] = useState<NoteValues>({ ...SINGLE_DEFAULTS, providerId: '', modelName: '' })
  const [submitting, setSubmitting] = useState(false)
  const [task, setTask] = useState<SingleTaskStatus>()
  const [history, setHistory] = useState<SingleTaskHistory[]>([])
  const [busyId, setBusyId] = useState<string>()
  const autoOpenedRef = useRef<string | undefined>(undefined)

  const patchValues = (patch: Partial<NoteValues>) => setValues((current) => ({ ...current, ...patch }))

  const loadHistory = useCallback(async () => {
    try {
      const result = await api.get<{ items: SingleTaskHistory[] }>('/single/tasks?page=1&page_size=100')
      setHistory(result.items || [])
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '加载历史失败')
    }
  }, [toast])

  useEffect(() => { void loadHistory() }, [loadHistory])

  const readStatus = useCallback(async (taskId: string): Promise<SingleTaskStatus> => {
    return api.get<SingleTaskStatus>(`/task_status/${taskId}`)
  }, [])

  const openNote = useCallback((status: SingleTaskStatus, fallbackTitle: string, hideSource?: boolean) => {
    if (!status.result?.markdown) return
    openReader({
      taskId: status.task_id,
      markdown: userFacingMarkdown(status.result.markdown, status.platform),
      title: status.result.audio_meta?.title || fallbackTitle,
      sourceUrl: hideSource || status.platform === 'local' ? undefined : status.source_url,
      hasImages: status.result.markdown.includes('/static/screenshots/'),
    })
  }, [openReader])

  // 当前任务轮询：完成时自动打开阅读器（与 admin 交互一致）
  useEffect(() => {
    if (!task?.task_id || terminalStatuses.includes(task.status)) return
    const timer = window.setInterval(async () => {
      try {
        const updated = await readStatus(task.task_id)
        setTask(updated)
        if (updated.status === 'SUCCESS' && updated.result?.markdown && autoOpenedRef.current !== updated.task_id) {
          autoOpenedRef.current = updated.task_id
          openNote(updated, '单视频 AI 笔记')
          setTab('history')
        }
        if (terminalStatuses.includes(updated.status)) void loadHistory()
      } catch {
        /* 网络抖动下一轮再试 */
      }
    }, 2500)
    return () => window.clearInterval(timer)
  }, [task?.task_id, task?.status, readStatus, openNote, loadHistory])

  // 历史里存在进行中的任务时低频刷新
  const hasActiveHistory = history.some((item) => !terminalStatuses.includes(item.status))
  useEffect(() => {
    if (!hasActiveHistory) return
    const timer = window.setInterval(() => void loadHistory(), 4000)
    return () => window.clearInterval(timer)
  }, [hasActiveHistory, loadHistory])

  const submit = async () => {
    const videoUrlValue = platform === 'local' ? localPath.trim() : videoUrl.trim()
    if (!videoUrlValue) {
      toast('warn', platform === 'local' ? '请选择本地视频文件' : '请输入视频链接')
      return
    }
    if (!values.providerId || !values.modelName) {
      toast('warn', '请选择模型供应商与 AI 模型')
      return
    }
    setSubmitting(true)
    try {
      // 后端字段是蛇形 provider_id / model_name，表单内部用驼峰，提交前映射
      const { providerId, modelName, ...rest } = values
      const options = normaliseOptions(rest) as Record<string, unknown>
      const result = await api.post<{ task_id: string }>('/generate_note', {
        ...options,
        provider_id: providerId,
        model_name: modelName,
        video_url: videoUrlValue,
        platform,
        link: values.format.includes('link'),
        screenshot: values.format.includes('screenshot'),
      })
      autoOpenedRef.current = undefined
      setTask({ task_id: result.task_id, status: 'PENDING', message: '任务已提交', source_url: videoUrlValue, platform })
      void loadHistory()
      toast('ok', '任务已提交，生成完成后自动打开阅读')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '提交失败')
    } finally {
      setSubmitting(false)
    }
  }

  const pickLocalFile = async () => {
    const picked = await desktop.pickFiles()
    if (picked && picked.length > 0) setLocalPath(picked[0])
  }

  const exportTask = async (taskId: string, title: string) => {
    try {
      const blob = await api.download(`/export_note/${taskId}`)
      const extension = blob.type.includes('zip') ? 'zip' : 'md'
      saveBlob(blob, `${title.replace(/[\\/:*?"<>|]/g, '-').trim() || 'note'}.${extension}`)
      toast('ok', '导出成功')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '导出失败')
    }
  }

  const readHistory = async (record: SingleTaskHistory) => {
    try {
      setBusyId(record.task_id)
      const status = await readStatus(record.task_id)
      if (!status.result?.markdown) {
        toast('warn', '该任务暂时没有可阅读的笔记内容')
        return
      }
      openNote(status, record.title || '单视频 AI 笔记')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '读取笔记失败')
    } finally {
      setBusyId(undefined)
    }
  }

  const regenerate = async (record: SingleTaskHistory) => {
    const ok = await confirm({
      title: '重新生成笔记？',
      content: `将基于「${record.title || record.source_url || '原视频'}」和原配置创建一个新任务。`,
      okText: '重新生成',
    })
    if (!ok) return
    try {
      setBusyId(record.task_id)
      const result = await api.post<{ task_id: string }>(`/single/tasks/${record.task_id}/regenerate`)
      autoOpenedRef.current = undefined
      setTask({
        task_id: result.task_id,
        status: 'PENDING',
        message: `基于「${record.title}」重新生成`,
        source_url: record.source_url,
        platform: record.platform,
      })
      setTab('create')
      void loadHistory()
      toast('ok', '重新生成任务已提交')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '重新生成失败')
    } finally {
      setBusyId(undefined)
    }
  }

  const cancelTask = async (record: SingleTaskHistory) => {
    const ok = await confirm({
      title: '停止该任务？',
      content: '当前正在执行的模型请求返回后将停止，已有转写和检查点会保留。',
      danger: true,
      okText: '停止',
    })
    if (!ok) return
    try {
      setBusyId(record.task_id)
      await api.post(`/single/tasks/${record.task_id}/cancel`)
      if (task?.task_id === record.task_id) setTask({ ...task, status: 'CANCELED', message: '已请求停止，当前请求结束后生效' })
      await loadHistory()
      toast('ok', '已请求停止该任务')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '停止任务失败')
    } finally {
      setBusyId(undefined)
    }
  }

  const currentTitle = task?.result?.audio_meta?.title || '单视频 AI 笔记'

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h2>单视频任务</h2>
          <p>创建、阅读和管理单个视频的 AI 笔记，历史结果可随时查看或重新生成。</p>
        </div>
        <div className="toolbar">
          <div className="tab-switch">
            <button type="button" className={tab === 'create' ? 'active' : ''} onClick={() => setTab('create')}>新建任务</button>
            <button type="button" className={tab === 'history' ? 'active' : ''} onClick={() => setTab('history')}>
              历史记录{history.length ? ` (${history.length})` : ''}
            </button>
          </div>
        </div>
      </header>

      {tab === 'create' && (
        <div className="stack">
          <section className="card">
            <div className="form-grid">
              <Field label="视频来源">
                <select className="input" value={platform} onChange={(event) => setPlatform(event.target.value)}>
                  {PLATFORMS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                </select>
              </Field>
              {platform === 'local' ? (
                <Field label="本地视频文件" required hint="直接读取磁盘文件，不上传">
                  <div className="row">
                    <input className="input" value={localPath} readOnly placeholder="选择本地视频文件" />
                    <button type="button" className="btn" onClick={() => void pickLocalFile()}>选择文件</button>
                  </div>
                </Field>
              ) : (
                <Field label="视频链接" required>
                  <input className="input" value={videoUrl} onChange={(event) => setVideoUrl(event.target.value)}
                    placeholder="粘贴视频链接，例如 https://www.bilibili.com/video/BV..." />
                </Field>
              )}
              <ModelFields
                providerId={values.providerId}
                modelName={values.modelName}
                onChange={(patch) => patchValues(patch)}
                providers={providers}
                models={models}
              />
              <NoteOptions values={values} onChange={(patch) => patchValues(patch)} />
            </div>
            <div className="row end">
              <button type="button" className="btn primary" disabled={submitting} onClick={() => void submit()}>
                {submitting ? '提交中…' : '开始生成'}
              </button>
            </div>
          </section>

          {task && (
            <section className="card">
              <div className="detail-header">
                <div className="row">
                  <StatusTag value={task.status} />
                  <strong>{currentTitle}</strong>
                </div>
                <div className="toolbar">
                  <button type="button" className="btn" disabled={!task.result?.markdown}
                    onClick={() => openNote(task, currentTitle)}>阅读笔记</button>
                  <button type="button" className="btn" disabled={!task.result?.markdown}
                    onClick={() => void exportTask(task.task_id, currentTitle)}>
                    导出{task.result?.markdown?.includes('/static/screenshots/') ? ' ZIP' : ''}
                  </button>
                </div>
              </div>
              <p className="hint">{task.message || (task.status === 'SUCCESS' ? '笔记已生成' : '任务处理中，完成后自动打开阅读视图')}</p>
            </section>
          )}
        </div>
      )}

      {tab === 'history' && (
        <section className="card">
          {history.length === 0 ? <EmptyState title="暂无单视频生成记录" hint="在「新建任务」提交第一个视频" /> : (
            <table className="table">
              <thead>
                <tr>
                  <th>笔记标题</th><th>来源</th><th>状态</th><th>原视频</th><th>导出</th><th>创建时间</th><th className="right">操作</th>
                </tr>
              </thead>
              <tbody>
                {history.map((record) => (
                  <tr key={record.task_id}>
                    <td className="ellipsis" style={{ maxWidth: 260 }}>{record.title || '—'}</td>
                    <td><span className="pill">{record.platform || '-'}</span></td>
                    <td><StatusTag value={record.status} /></td>
                    <td className="ellipsis dim" style={{ maxWidth: 220 }}>{record.source_url || '历史记录未保存'}</td>
                    <td>{record.has_result ? <span className="pill">{(record.export_format || 'md').toUpperCase()}</span> : '—'}</td>
                    <td className="dim">{record.created_at ? new Date(record.created_at).toLocaleString() : '—'}</td>
                    <td className="right actions">
                      <button type="button" className="btn text" disabled={!record.has_result || busyId === record.task_id}
                        onClick={() => void readHistory(record)}>阅读</button>
                      <button type="button" className="btn text" disabled={!record.has_result}
                        onClick={() => void exportTask(record.task_id, record.title || 'note')}>导出</button>
                      {!terminalStatuses.includes(record.status) && (
                        <button type="button" className="btn text warn" disabled={busyId === record.task_id}
                          onClick={() => void cancelTask(record)}>停止</button>
                      )}
                      <button type="button" className="btn text warn" disabled={!record.can_regenerate || busyId === record.task_id}
                        title={record.can_regenerate ? '使用原视频和原配置创建新任务' : '旧任务缺少原始配置或本地视频已不存在'}
                        onClick={() => void regenerate(record)}>重新生成</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </div>
  )
}


export function userFacingMarkdown(markdown: string, platform?: string) {
  // 本地平台的笔记里「来源链接」指向内部上传缓存路径，对用户没有意义，展示时去掉
  if (platform !== 'local') return markdown
  return markdown.replace(
    /^\s*>\s*来源链接[：:]\s*\/uploads\/(?:batch_videos|batch_previews|uploads)\/[^\r\n]*\r?\n(?:\r?\n)?/i,
    '',
  )
}
