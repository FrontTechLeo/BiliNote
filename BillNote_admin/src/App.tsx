import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  Alert, Button, Card, Checkbox, Descriptions, Drawer, Empty, Form, Input, Layout, Menu, Message, Modal,
  Popconfirm, Progress, Radio, Select, Space, Steps, Switch, Table, Tabs, Tag, Tooltip,
} from '@arco-design/web-react'
import {
  IconApps, IconCloud, IconDelete, IconDownload, IconEdit, IconLink, IconPlus, IconRefresh, IconRobot, IconSettings, IconStorage, IconUpload,
} from '@arco-design/web-react/icon'
import { api, downloadBlob } from './api'
import MarkdownPreview from './MarkdownPreview'
import { imageCompressionOptions, normaliseImageCompression } from './types'
import type { BatchItem, BatchTask, Model, Provider, SingleTaskHistory, SingleTaskStatus } from './types'

const { Header, Sider, Content } = Layout
const { Step } = Steps
const formats = [
  { label: '目录', value: 'toc' }, { label: '原片跳转', value: 'link' },
  { label: '原片截图', value: 'screenshot' }, { label: 'AI 总结', value: 'summary' },
]
const styles = [
  { label: '精简', value: 'minimal' }, { label: '详细', value: 'detailed' },
  { label: '教程', value: 'tutorial' }, { label: '学术', value: 'academic' },
  { label: '任务导向', value: 'task_oriented' }, { label: '会议纪要', value: 'meeting_minutes' },
]
const statusColor: Record<string, string> = {
  SUCCESS: 'green', FAILED: 'red', CANCELED: 'gray', PARTIAL_FAILED: 'orange', RUNNING: 'arcoblue',
  PENDING: 'gray', PARSING: 'arcoblue', DOWNLOADING: 'arcoblue', TRANSCRIBING: 'arcoblue',
  SUMMARIZING: 'arcoblue', FORMATTING: 'arcoblue', SAVING: 'arcoblue',
}
const appendableBatchStatuses = ['PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELED']
const terminalItemStatuses = ['SUCCESS', 'FAILED', 'CANCELED']
const filenameCollator = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })

function canAppendBatch(status?: string) {
  return appendableBatchStatuses.includes(status || '')
}

function normaliseOptions(values: any) {
  const grid = typeof values.grid_size === 'string'
    ? values.grid_size.split(',').map((value: string) => Number(value.trim())).filter(Boolean)
    : values.grid_size
  return {
    ...values,
    grid_size: Array.isArray(grid) && grid.length === 2 ? grid : [2, 2],
    image_compression: normaliseImageCompression(values.image_compression),
  }
}

function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / 1024 ** index
  return `${value >= 100 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`
}

function sortFilesNaturally(files: File[]) {
  return [...files].sort((left, right) => {
    const leftPath = (left as File & { webkitRelativePath?: string }).webkitRelativePath || left.name
    const rightPath = (right as File & { webkitRelativePath?: string }).webkitRelativePath || right.name
    return filenameCollator.compare(leftPath, rightPath)
  })
}

function userFacingMarkdown(markdown: string, platform: string) {
  if (platform !== 'local') return markdown
  return markdown.replace(
    /^\s*>\s*来源链接[：:]\s*\/uploads\/(?:batch_videos|batch_previews)\/[^\r\n]*\r?\n(?:\r?\n)?/i,
    '',
  )
}

function Status({ value }: { value?: string }) {
  return <Tag color={statusColor[value || ''] || 'gray'}>{value || 'UNKNOWN'}</Tag>
}

function PageHeader({ title, description, actions }: { title: string; description: string; actions?: ReactNode }) {
  return <div className="page-header"><div><h2>{title}</h2><p>{description}</p></div><div className="toolbar">{actions}</div></div>
}

function useProviders() {
  const [providers, setProviders] = useState<Provider[]>([])
  const [models, setModels] = useState<Model[]>([])
  const refresh = useCallback(async () => {
    const [providerRows, modelRows] = await Promise.all([
      api.get<Provider[]>('/get_all_providers'), api.get<Model[]>('/model_list'),
    ])
    setProviders(providerRows || [])
    setModels(modelRows || [])
  }, [])
  useEffect(() => { void refresh().catch((error: Error) => Message.error(error.message)) }, [refresh])
  return { providers, models, refresh }
}

function NoteOptions({ form }: { form: any }) {
  const videoUnderstanding = Form.useWatch('video_understanding', form)
  const selectedFormats = Form.useWatch('format', form) || []
  const formatOptions = formats.map((item) => ({
    ...item,
    disabled: item.value === 'screenshot' && !videoUnderstanding,
  }))
  useEffect(() => {
    if (!videoUnderstanding && Array.isArray(selectedFormats) && selectedFormats.includes('screenshot')) {
      form.setFieldValue('format', selectedFormats.filter((value: string) => value !== 'screenshot'))
    }
  }, [form, selectedFormats, videoUnderstanding])
  return <>
    <div className="form-grid">
      <Form.Item field="quality" label="下载质量"><Select options={['fast', 'medium', 'slow']} /></Form.Item>
      <Form.Item field="style" label="笔记风格"><Select options={styles} /></Form.Item>
      <Form.Item field="format" label="笔记格式" className="wide"><Checkbox.Group options={formatOptions} /></Form.Item>
      <Form.Item field="video_understanding" label="视频理解" triggerPropName="checked"><Switch checkedText="开启" uncheckedText="关闭" /></Form.Item>
      {videoUnderstanding && <><Form.Item field="image_compression" label="画面压缩"><Select options={imageCompressionOptions} /></Form.Item><Form.Item field="video_interval" label="抽帧间隔（秒）"><Input type="number" /></Form.Item><Form.Item field="grid_size" label="拼图网格"><Input placeholder="例如 2,2" /></Form.Item></>}
      <Form.Item field="extras" label="备注" className="wide"><Input.TextArea placeholder="追加给 AI 的课程笔记要求" autoSize={{ minRows: 3, maxRows: 6 }} /></Form.Item>
    </div>
  </>
}

function ModelFields({ form, providers, models }: { form: any; providers: Provider[]; models: Model[] }) {
  const providerId = Form.useWatch('provider_id', form)
  const modelOptions = models.filter((model) => String(model.provider_id) === String(providerId)).map((model) => ({ label: model.model_name, value: model.model_name }))
  return <div className="form-grid">
    <Form.Item field="provider_id" label="模型供应商" rules={[{ required: true, message: '请选择供应商' }]}>
      <Select placeholder="选择供应商" showSearch options={providers.map((item) => ({ label: item.name, value: item.id }))} onChange={() => form.setFieldValue('model_name', undefined)} />
    </Form.Item>
    <Form.Item field="model_name" label="AI 模型" rules={[{ required: true, message: '请选择模型' }]}>
      <Select placeholder="选择模型" showSearch options={modelOptions} disabled={!providerId} />
    </Form.Item>
  </div>
}

function SingleTaskPage() {
  const [form] = Form.useForm()
  const { providers, models } = useProviders()
  const [submitting, setSubmitting] = useState(false)
  const [task, setTask] = useState<SingleTaskStatus>()
  const [history, setHistory] = useState<SingleTaskHistory[]>([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [activeTab, setActiveTab] = useState('create')
  const [preview, setPreview] = useState<{ taskId: string; title: string; markdown: string; sourceUrl?: string }>()
  const [previewLoadingId, setPreviewLoadingId] = useState<string>()
  const [regeneratingId, setRegeneratingId] = useState<string>()
  const [cancellingId, setCancellingId] = useState<string>()
  const autoOpenedTaskRef = useRef<string | undefined>(undefined)
  const historyRequestRef = useRef(0)
  const localFile = useRef<HTMLInputElement>(null)
  const loadHistory = useCallback(async (showLoading = true) => {
    const requestId = ++historyRequestRef.current
    if (showLoading) setHistoryLoading(true)
    try {
      const result = await api.get<{ items: SingleTaskHistory[] }>('/single/tasks?page=1&page_size=100')
      if (requestId === historyRequestRef.current) setHistory(result.items)
    } finally {
      if (requestId === historyRequestRef.current) setHistoryLoading(false)
    }
  }, [])
  const poll = useCallback(async (taskId: string) => {
    try {
      const updated = await api.get<SingleTaskStatus>(`/task_status/${taskId}`)
      setTask((current) => current?.task_id === taskId ? { ...current, ...updated } : updated)
      if (['SUCCESS', 'FAILED', 'CANCELED'].includes(updated.status)) void loadHistory(false).catch(() => undefined)
      if (updated.status === 'SUCCESS' && updated.result?.markdown && autoOpenedTaskRef.current !== taskId) {
        autoOpenedTaskRef.current = taskId
        setPreview({
          taskId,
          markdown: updated.result.markdown,
          title: updated.result.audio_meta?.title || '单视频 AI 笔记',
        })
      }
    } catch (error) {
      setTask((current) => ({
        task_id: taskId,
        status: 'FAILED',
        message: error instanceof Error ? error.message : String(error),
        source_url: current?.source_url,
        platform: current?.platform,
      }))
      void loadHistory(false).catch(() => undefined)
    }
  }, [loadHistory])
  useEffect(() => { void loadHistory().catch((error: Error) => Message.error(error.message)) }, [loadHistory])
  useEffect(() => {
    if (!task?.task_id || ['SUCCESS', 'FAILED', 'CANCELED'].includes(task.status)) return
    const timer = window.setInterval(() => void poll(task.task_id), 2500)
    return () => window.clearInterval(timer)
  }, [task?.task_id, task?.status, poll])
  const hasActiveHistory = history.some((item) => !['SUCCESS', 'FAILED', 'CANCELED'].includes(item.status))
  useEffect(() => {
    if (!hasActiveHistory) return
    const timer = window.setInterval(() => void loadHistory(false).catch(() => undefined), 4000)
    return () => window.clearInterval(timer)
  }, [hasActiveHistory, loadHistory])
  const submit = async () => {
    try {
      const values = await form.validate()
      setSubmitting(true)
      let videoUrl = values.video_url
      if (values.platform === 'local') {
        const file = localFile.current?.files?.[0]
        if (!file) throw new Error('请选择本地视频')
        const body = new FormData(); body.append('file', file)
        const upload = await api.upload<{ url: string }>('/upload', body)
        videoUrl = upload.url
      }
      const result = await api.post<{ task_id: string }>('/generate_note', { ...normaliseOptions(values), video_url: videoUrl, link: values.format?.includes('link'), screenshot: values.format?.includes('screenshot') })
      autoOpenedTaskRef.current = undefined
      setTask({ task_id: result.task_id, status: 'PENDING', message: '任务已提交', source_url: videoUrl, platform: values.platform })
      void loadHistory(false).catch(() => undefined)
      Message.success('单视频生成任务已提交')
    } catch (error) { Message.error(error instanceof Error ? error.message : '提交失败') } finally { setSubmitting(false) }
  }
  const exportTask = async (taskId: string, title: string) => {
    try {
      const blob = await api.download(`/export_note/${taskId}`)
      const extension = blob.type.includes('zip') ? 'zip' : 'md'
      downloadBlob(blob, `${title.replace(/[\\/:*?"<>|]/g, '-').trim() || 'note'}.${extension}`)
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '导出失败')
    }
  }
  const readHistory = async (record: SingleTaskHistory) => {
    try {
      setPreviewLoadingId(record.task_id)
      const status = await api.get<SingleTaskStatus>(`/task_status/${record.task_id}`)
      if (!status.result?.markdown) {
        Message.warning('该任务暂时没有可阅读的笔记内容')
        return
      }
      setPreview({
        taskId: record.task_id,
        markdown: status.result.markdown,
        title: record.title || status.result.audio_meta?.title || '单视频 AI 笔记',
        sourceUrl: record.platform === 'local' ? undefined : record.source_url,
      })
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '读取笔记失败')
    } finally {
      setPreviewLoadingId(undefined)
    }
  }
  const regenerate = async (record: SingleTaskHistory) => {
    try {
      setRegeneratingId(record.task_id)
      const result = await api.post<{ task_id: string }>(`/single/tasks/${record.task_id}/regenerate`)
      autoOpenedTaskRef.current = undefined
      setTask({
        task_id: result.task_id,
        status: 'PENDING',
        message: `基于“${record.title}”重新生成`,
        source_url: record.source_url,
        platform: record.platform,
      })
      setActiveTab('create')
      void loadHistory(false).catch(() => undefined)
      Message.success('重新生成任务已提交')
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '重新生成失败')
    } finally {
      setRegeneratingId(undefined)
    }
  }
  const cancel = async (record: SingleTaskHistory) => {
    try {
      setCancellingId(record.task_id)
      await api.post(`/single/tasks/${record.task_id}/cancel`)
      setTask((current) => current?.task_id === record.task_id ? { ...current, status: 'CANCELED', message: '已请求停止，当前请求结束后生效' } : current)
      await loadHistory(false)
      Message.success('已请求停止该任务')
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '停止任务失败')
    } finally {
      setCancellingId(undefined)
    }
  }
  const currentTitle = task?.result?.audio_meta?.title || '单视频 AI 笔记'
  return <div className="page">
    <PageHeader title="单视频任务" description="创建、阅读和管理单个视频的 AI 笔记，历史结果可随时查看或重新生成。" actions={<Button icon={<IconRefresh />} onClick={() => void loadHistory()}>刷新历史</Button>} />
    <Tabs activeTab={activeTab} onChange={setActiveTab} type="rounded" className="single-task-tabs">
      <Tabs.TabPane key="create" title="新建任务">
        <Card><Form form={form} layout="vertical" initialValues={{ platform: 'bilibili', quality: 'medium', style: 'minimal', format: [], video_understanding: false, image_compression: 'high', video_interval: 6, grid_size: '2,2' }} scrollToFirstError>
          <div className="form-grid">
            <Form.Item field="platform" label="视频来源"><Select options={[{ label: '哔哩哔哩', value: 'bilibili' }, { label: 'YouTube', value: 'youtube' }, { label: '抖音', value: 'douyin' }, { label: '快手', value: 'kuaishou' }, { label: '本地视频', value: 'local' }]} /></Form.Item>
            <Form.Item shouldUpdate noStyle>{() => form.getFieldValue('platform') === 'local' ? <Form.Item label="本地视频" required><input ref={localFile} type="file" accept="video/*" /></Form.Item> : <Form.Item field="video_url" label="视频链接" rules={[{ required: true, message: '请输入视频链接' }]}><Input placeholder="粘贴视频链接" /></Form.Item>}</Form.Item>
          </div>
          <ModelFields form={form} providers={providers} models={models} />
          <NoteOptions form={form} />
          <Button type="primary" icon={<IconRobot />} loading={submitting} onClick={submit}>开始生成</Button>
        </Form></Card>
        {task && <Card className="single-task-result" title="当前任务">
          <div className="single-task-current">
            <div className="single-task-current-status">
              <Status value={task.status} />
              <div><strong>{currentTitle}</strong><span>{task.message || (task.status === 'SUCCESS' ? '笔记已生成，可以进入专业阅读模式' : '任务正在处理中，完成后将自动打开阅读视图')}</span></div>
            </div>
            <Space wrap>
              <Button disabled={!task.result?.markdown} onClick={() => task.result?.markdown && setPreview({ taskId: task.task_id, markdown: task.result.markdown, title: currentTitle, sourceUrl: task.platform === 'local' ? undefined : task.source_url })}>阅读笔记</Button>
              <Button icon={<IconDownload />} disabled={!task.result?.markdown} onClick={() => void exportTask(task.task_id, currentTitle)}>导出{task.result?.markdown?.includes('/static/screenshots/') ? ' ZIP' : ''}</Button>
            </Space>
          </div>
        </Card>}
      </Tabs.TabPane>
      <Tabs.TabPane key="history" title={`历史记录${history.length ? ` (${history.length})` : ''}`}>
        <Card>
          <Table
            rowKey="task_id"
            loading={historyLoading}
            data={history}
            pagination={false}
            scroll={{ x: 1080 }}
            noDataElement={<Empty description="暂无单视频生成记录" />}
            columns={[
              { title: '笔记标题', dataIndex: 'title', width: 300, ellipsis: true },
              { title: '来源', dataIndex: 'platform', width: 100 },
              { title: '状态', dataIndex: 'status', width: 120, render: (value: string) => <Status value={value} /> },
              { title: '原视频', dataIndex: 'source_url', width: 230, ellipsis: true, render: (value?: string) => value || '历史记录未保存' },
              { title: '导出', width: 90, render: (_: unknown, record: SingleTaskHistory) => record.has_result ? <Tag color={record.has_images ? 'arcoblue' : 'gray'}>{record.export_format.toUpperCase()}</Tag> : '-' },
              { title: '创建时间', dataIndex: 'created_at', width: 180, render: (value?: string) => value ? new Date(value).toLocaleString() : '-' },
              { title: '操作', fixed: 'right', width: 310, render: (_: unknown, record: SingleTaskHistory) => <Space className="single-task-history-actions" size={4}>
                <Button type="text" size="small" loading={previewLoadingId === record.task_id} disabled={!record.has_result} onClick={() => void readHistory(record)}>阅读</Button>
                <Button type="text" size="small" disabled={!record.has_result} onClick={() => void exportTask(record.task_id, record.title)}>导出</Button>
                {!['SUCCESS', 'FAILED', 'CANCELED'].includes(record.status) && <Popconfirm title="确认停止该视频任务？" content="当前正在执行的模型请求返回后将停止，已有转写和检查点会保留。" okText="停止" cancelText="继续" okButtonProps={{ status: 'danger' }} onOk={() => void cancel(record)}>
                  <Button type="text" status="warning" size="small" loading={cancellingId === record.task_id}>停止</Button>
                </Popconfirm>}
                <Tooltip content={record.can_regenerate ? '使用原视频和原配置创建一个新任务' : '旧任务缺少原始配置，或本地视频已不存在'}>
                  <span><Button type="text" status="warning" size="small" loading={regeneratingId === record.task_id} disabled={!record.can_regenerate} onClick={() => void regenerate(record)}>重新生成</Button></span>
                </Tooltip>
              </Space> },
            ]}
          />
        </Card>
      </Tabs.TabPane>
    </Tabs>
    <Drawer className="markdown-reader-drawer" visible={Boolean(preview)} width="min(1380px, 97vw)" title="单视频笔记阅读" onCancel={() => setPreview(undefined)} footer={null} unmountOnExit>
      {preview && <MarkdownPreview content={preview.markdown} title={preview.title} sourceUrl={preview.sourceUrl} onDownload={() => void exportTask(preview.taskId, preview.title)} />}
    </Drawer>
  </div>
}

type PreviewItem = { title: string; source_url: string; platform: string; position?: number }
type AppendPreview = {
  title: string
  items: PreviewItem[]
  discovered_count: number
  skipped_count: number
}
type LocalAnalysisProgress = {
  percent: number
  label: string
}

function CreateBatchModal({ visible, onClose, onCreated }: { visible: boolean; onClose: () => void; onCreated: () => void }) {
  const [sourceForm] = Form.useForm()
  const [configForm] = Form.useForm()
  const { providers, models } = useProviders()
  const [step, setStep] = useState(0)
  const [preview, setPreview] = useState<{ title: string; items: PreviewItem[] }>()
  const [selected, setSelected] = useState<string[]>([])
  const [files, setFiles] = useState<File[]>([])
  const [loading, setLoading] = useState(false)
  const sourceType = Form.useWatch('source_type', sourceForm) || 'bilibili'
  const reset = () => { setStep(0); setPreview(undefined); setSelected([]); setFiles([]); sourceForm.resetFields(); configForm.resetFields(); onClose() }
  const analyse = async () => {
    try {
      const values = await sourceForm.validate()
      setLoading(true)
      let result: { title: string; items: PreviewItem[] }
      if (values.source_type === 'bilibili') result = await api.post('/batch/preview/bilibili', { source_url: values.source_url })
      else {
        if (!files.length) throw new Error('请选择至少一个本地视频')
        const data = new FormData(); files.forEach((file) => data.append('files', file))
        result = await api.upload('/batch/preview/local', data)
      }
      setPreview(result); setSelected(result.items.map((item) => item.source_url)); setStep(1)
    } catch (error) { Message.error(error instanceof Error ? error.message : '解析失败') } finally { setLoading(false) }
  }
  const create = async () => {
    try {
      const values = await configForm.validate()
      if (!preview || !selected.length) throw new Error('至少选择一个视频')
      setLoading(true)
      const { task_name: taskName, ...configValues } = values
      const config = normaliseOptions(configValues)
      await api.post('/batch/tasks', { title: taskName?.trim() || preview.title, source_type: sourceType, source_value: sourceForm.getFieldValue('source_url'), config: { ...config, link: config.format?.includes('link'), screenshot: config.format?.includes('screenshot') }, items: preview.items.filter((item) => selected.includes(item.source_url)) })
      Message.success('批量任务已创建并开始执行'); onCreated(); reset()
    } catch (error) { Message.error(error instanceof Error ? error.message : '创建失败') } finally { setLoading(false) }
  }
  return <Modal visible={visible} title="新建批量合集任务" style={{ width: 880 }} footer={null} onCancel={reset} unmountOnExit>
    <Steps current={step} style={{ marginBottom: 24 }}><Step title="分析视频列表" /><Step title="配置生成任务" /></Steps>
    {step === 0 ? <Form form={sourceForm} layout="vertical" initialValues={{ source_type: 'bilibili' }}>
      <Form.Item field="source_type" label="批量任务类型"><Radio.Group><Radio value="bilibili">哔哩哔哩合集</Radio><Radio value="local">本地文件夹</Radio></Radio.Group></Form.Item>
      {sourceType === 'bilibili' ? <Form.Item field="source_url" label="合集链接" rules={[{ required: true, message: '请输入哔哩哔哩合集或视频链接' }]}><Input placeholder="例如 https://www.bilibili.com/video/BV1rv7A6oEeP/" /></Form.Item> : <Form.Item label="本地视频文件夹" required extra="选择文件夹后会上传视频并按文件名自然排序"><input type="file" multiple accept="video/*" {...({ webkitdirectory: '', directory: '' } as any)} onChange={(event) => setFiles(sortFilesNaturally(Array.from(event.target.files || [])))} /></Form.Item>}
      <Space><Button type="primary" loading={loading} onClick={analyse}>分析视频</Button><Button onClick={reset}>取消</Button></Space>
    </Form> : <>
      <div className="source-list"><Table rowKey="source_url" size="small" data={preview?.items || []} pagination={false} rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys as string[]), checkAll: true }} columns={[{ title: '序号', dataIndex: 'position', width: 72, render: (_: unknown, record: PreviewItem, index: number) => index + 1 }, { title: '视频标题', dataIndex: 'title', ellipsis: true }, { title: '来源', dataIndex: 'platform', width: 110 }]} /></div>
      <Form form={configForm} layout="vertical" initialValues={{ quality: 'medium', style: 'tutorial', format: ['toc', 'summary'], video_understanding: false, image_compression: 'high', video_interval: 6, grid_size: '2,2' }} style={{ marginTop: 16 }} scrollToFirstError><Form.Item field="task_name" label="任务名称（可选）" rules={[{ type: 'string', maxLength: 100, message: '任务名称不能超过 100 个字符' }]} extra={`不填写时使用“${preview?.title || (sourceType === 'local' ? '本地视频批量任务' : '哔哩哔哩批量任务')}”`}><Input maxLength={100} showWordLimit placeholder="为本次批量任务设置便于识别的名称" /></Form.Item><ModelFields form={configForm} providers={providers} models={models} /><NoteOptions form={configForm} /></Form>
      <Space><Button onClick={() => setStep(0)}>上一步</Button><Button type="primary" loading={loading} onClick={create}>开始批量任务</Button></Space>
    </>}
  </Modal>
}

function EditBatchModal({
  task,
  onClose,
  onUpdated,
}: {
  task?: BatchTask
  onClose: () => void
  onUpdated: (task: BatchTask) => void
}) {
  const [form] = Form.useForm()
  const { providers, models } = useProviders()
  const [saving, setSaving] = useState(false)
  const canEditConfig = Boolean(task && ['SUCCESS', 'FAILED', 'PARTIAL_FAILED', 'CANCELED'].includes(task.status))

  useEffect(() => {
    if (!task) return
    form.setFieldsValue({
      title: task.title,
      ...(task.config || {}),
      image_compression: normaliseImageCompression(task.config?.image_compression),
      grid_size: task.config?.grid_size?.join(',') || '2,2',
    })
  }, [form, task])

  const save = async () => {
    if (!task) return
    try {
      setSaving(true)
      const values = await form.validate()
      const payload: Record<string, unknown> = { title: values.title.trim() }
      if (canEditConfig) {
        const { title: _title, ...configValues } = values
        const config = normaliseOptions(configValues)
        payload.config = {
          ...config,
          link: config.format?.includes('link'),
          screenshot: config.format?.includes('screenshot'),
        }
      }
      const updated = await api.patch<BatchTask>(`/batch/tasks/${task.id}`, payload)
      Message.success('批量任务设置已更新')
      onUpdated(updated)
      onClose()
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '更新失败')
    } finally {
      setSaving(false)
    }
  }

  return <Modal visible={Boolean(task)} title="编辑批量任务" style={{ width: 760, maxWidth: 'calc(100vw - 32px)' }} confirmLoading={saving} okText="保存修改" maskClosable={false} onOk={() => void save()} onCancel={onClose} unmountOnExit>
    <Form form={form} layout="vertical" scrollToFirstError>
      <Form.Item field="title" label="任务名称" rules={[{ required: true, message: '请输入任务名称' }, { type: 'string', maxLength: 100, message: '任务名称不能超过 100 个字符' }]}><Input maxLength={100} showWordLimit /></Form.Item>
      {canEditConfig ? <><Alert type="info" content="设置修改后将用于后续追加的视频，以及失败子任务的再次重试；已经完成的笔记不会自动重新生成。" style={{ marginBottom: 16 }} /><ModelFields form={form} providers={providers} models={models} /><NoteOptions form={form} /></> : <Alert type="warning" content="任务正在执行，目前只能修改名称；生成设置需在本批任务结束后修改。" />}
    </Form>
  </Modal>
}

function AppendBatchModal({
  task,
  visible,
  onClose,
  onAppended,
}: {
  task?: BatchTask
  visible: boolean
  onClose: () => void
  onAppended: (task: BatchTask) => void
}) {
  const [sourceForm] = Form.useForm()
  const [step, setStep] = useState(0)
  const [preview, setPreview] = useState<AppendPreview>()
  const [selected, setSelected] = useState<string[]>([])
  const [files, setFiles] = useState<File[]>([])
  const [appendSourceType, setAppendSourceType] = useState<'bilibili' | 'local'>('bilibili')
  const [analysisProgress, setAnalysisProgress] = useState<LocalAnalysisProgress>()
  const [loading, setLoading] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const folderInputRef = useRef<HTMLInputElement>(null)
  const uploadAbortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    if (visible && task) {
      const sourceType = task.source_type === 'local' ? 'local' : 'bilibili'
      setAppendSourceType(sourceType)
      sourceForm.setFieldsValue({ source_url: task.source_type === 'bilibili' ? task.source_value || '' : '' })
    }
  }, [sourceForm, task, visible])

  const reset = () => {
    uploadAbortRef.current?.abort()
    uploadAbortRef.current = null
    setStep(0)
    setPreview(undefined)
    setSelected([])
    setFiles([])
    setAppendSourceType('bilibili')
    setAnalysisProgress(undefined)
    sourceForm.resetFields()
    if (fileInputRef.current) fileInputRef.current.value = ''
    if (folderInputRef.current) folderInputRef.current.value = ''
    onClose()
  }
  const mergeFiles = (incoming: File[]) => {
    setFiles((current) => {
      const unique = new Map<string, File>()
      for (const file of [...current, ...incoming]) {
        const relativePath = (file as File & { webkitRelativePath?: string }).webkitRelativePath
        unique.set(`${relativePath || file.name}:${file.size}:${file.lastModified}`, file)
      }
      return sortFilesNaturally(Array.from(unique.values()))
    })
  }
  const analyse = async () => {
    if (!task) return
    try {
      setLoading(true)
      let result: AppendPreview
      if (appendSourceType === 'bilibili') {
        const values = await sourceForm.validate()
        result = await api.post(`/batch/tasks/${task.id}/preview/bilibili`, { source_url: values.source_url })
      } else {
        if (!files.length) throw new Error('请选择至少一个本地视频或视频文件夹')
        const totalBytes = files.reduce((total, file) => total + file.size, 0)
        const data = new FormData()
        files.forEach((file) => data.append('files', file))
        const controller = new AbortController()
        uploadAbortRef.current = controller
        setAnalysisProgress({
          percent: 0,
          label: `准备上传 ${files.length} 个视频（${formatBytes(totalBytes)}）`,
        })
        result = await api.uploadWithProgress(
          `/batch/tasks/${task.id}/preview/local`,
          data,
          ({ percent }) => {
            setAnalysisProgress({
              percent,
              label: percent < 100
                ? `正在上传 ${files.length} 个视频（${formatBytes(totalBytes)}）`
                : '上传完成，后端正在保存文件并检查重复内容…',
            })
          },
          controller.signal,
        )
        uploadAbortRef.current = null
      }
      setPreview(result)
      setSelected(result.items.map((item) => item.source_url))
      setStep(1)
    } catch (error) {
      if (!(error instanceof DOMException && error.name === 'AbortError')) {
        Message.error(error instanceof Error ? error.message : '分析新增视频失败')
      }
    } finally {
      uploadAbortRef.current = null
      setAnalysisProgress(undefined)
      setLoading(false)
    }
  }
  const append = async () => {
    if (!task || !preview) return
    try {
      if (!selected.length) throw new Error('至少选择一个新增视频')
      setLoading(true)
      const result = await api.post<{
        task: BatchTask
        added_count: number
        skipped_count: number
      }>(`/batch/tasks/${task.id}/items`, {
        items: preview.items.filter((item) => selected.includes(item.source_url)),
      })
      Message.success(`已追加 ${result.added_count} 个视频，任务重新进入执行队列`)
      onAppended(result.task)
      reset()
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '追加视频失败')
    } finally {
      setLoading(false)
    }
  }

  return <Modal
    visible={visible}
    title={task ? `追加视频 · ${task.title}` : '追加视频'}
    style={{ width: 820, maxWidth: 'calc(100vw - 32px)' }}
    footer={null}
    maskClosable={false}
    onCancel={reset}
    unmountOnExit
  >
    <Alert
      type="info"
      content={`新增视频将沿用原批次的模型、笔记格式和视频理解配置；执行中也可以继续追加，已加入过的${appendSourceType === 'local' ? '相同内容文件' : '视频及分P'}会自动过滤。`}
      style={{ marginBottom: 20 }}
    />
    <Steps current={step} style={{ marginBottom: 24 }}>
      <Step title={appendSourceType === 'local' ? '选择本地视频' : '解析远程合集'} />
      <Step title="确认新增视频" />
    </Steps>
    {step === 0 ? <>
      <div className="append-source-switch">
        <span>追加来源</span>
        <Radio.Group
          type="button"
          value={appendSourceType}
          onChange={(value) => {
            setAppendSourceType(value as 'bilibili' | 'local')
            setPreview(undefined)
            setSelected([])
          }}
        >
          <Radio value="bilibili">B站链接</Radio>
          <Radio value="local">本地视频</Radio>
        </Radio.Group>
      </div>
      {appendSourceType === 'bilibili' ? (
        <Form form={sourceForm} layout="vertical">
          <Form.Item field="source_url" label="哔哩哔哩合集或视频链接" rules={[{ required: true, message: '请输入链接' }]}>
            <Input placeholder="默认使用原合集链接，也可以粘贴其它 B 站视频或合集链接" />
          </Form.Item>
        </Form>
      ) : (
        <div className="append-file-picker">
          <Space wrap>
            <Button icon={<IconUpload />} onClick={() => fileInputRef.current?.click()}>选择视频文件</Button>
            <Button icon={<IconApps />} onClick={() => folderInputRef.current?.click()}>选择视频文件夹</Button>
            <span>{files.length ? `已选择 ${files.length} 个文件` : '尚未选择文件'}</span>
          </Space>
          <input ref={fileInputRef} hidden type="file" multiple accept="video/*" onChange={(event) => mergeFiles(Array.from(event.target.files || []))} />
          <input ref={folderInputRef} hidden type="file" multiple accept="video/*" {...({ webkitdirectory: '', directory: '' } as any)} onChange={(event) => mergeFiles(Array.from(event.target.files || []))} />
          <div className="append-upload-hint">浏览器需要先把本地视频上传到 BiliNote 服务后才能分析；文件较大时请查看下方真实进度。</div>
          {files.length > 0 && <div className="append-file-summary">{files.slice(0, 4).map((file) => file.name).join('、')}{files.length > 4 ? ` 等 ${files.length} 个文件` : ''}</div>}
          {analysisProgress && <div className="append-upload-progress">
            <div><span>{analysisProgress.label}</span><strong>{analysisProgress.percent}%</strong></div>
            <Progress percent={analysisProgress.percent} showText={false} size="small" />
          </div>}
        </div>
      )}
      <Space style={{ marginTop: 20 }}>
        <Button type="primary" loading={loading} onClick={() => void analyse()}>分析新增视频</Button>
        <Button onClick={reset}>{loading && appendSourceType === 'local' ? '取消上传' : '取消'}</Button>
      </Space>
    </> : <>
      <Alert
        type={preview?.items.length ? 'success' : 'warning'}
        content={`共发现 ${preview?.discovered_count || 0} 个视频，已过滤 ${preview?.skipped_count || 0} 个已有视频，可追加 ${preview?.items.length || 0} 个。`}
        style={{ marginBottom: 16 }}
      />
      <div className="source-list">
        <Table
          rowKey="source_url"
          size="small"
          data={preview?.items || []}
          pagination={false}
          noDataElement={<Empty description="没有发现可追加的新视频" />}
          rowSelection={preview?.items.length ? {
            selectedRowKeys: selected,
            onChange: (keys) => setSelected(keys as string[]),
            checkAll: true,
          } : undefined}
          columns={[
            { title: '序号', width: 72, render: (_: unknown, _record: PreviewItem, index: number) => index + 1 },
            { title: '视频标题', dataIndex: 'title', ellipsis: true },
            { title: '来源', dataIndex: 'platform', width: 110 },
          ]}
        />
      </div>
      <Space style={{ marginTop: 20 }}>
        <Button onClick={() => setStep(0)}>重新选择</Button>
        <Button type="primary" disabled={!selected.length} loading={loading} onClick={() => void append()}>追加并开始处理</Button>
      </Space>
    </>}
  </Modal>
}

function BatchDetail({
  task,
  onClose,
  onAppend,
  onEdit,
  onTaskChange,
}: {
  task?: BatchTask
  onClose: () => void
  onAppend: (task: BatchTask) => void
  onEdit: (task: BatchTask) => void
  onTaskChange: (task: BatchTask) => void
}) {
  const [items, setItems] = useState<BatchItem[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [events, setEvents] = useState<any[]>([])
  const [preview, setPreview] = useState<{ markdown: string; title: string; sourceUrl?: string; itemId: string }>()
  const [previewLoadingId, setPreviewLoadingId] = useState<string>()
  const [retryingId, setRetryingId] = useState<string>()
  const [cancellingId, setCancellingId] = useState<string>()
  const loadRequestRef = useRef(0)
  const taskId = task?.id
  useEffect(() => {
    loadRequestRef.current += 1
    setItems([])
    setSelected([])
    setEvents([])
  }, [taskId])
  const load = useCallback(async () => {
    if (!taskId) return
    const requestId = ++loadRequestRef.current
    const [updatedTask, updatedItems] = await Promise.all([
      api.get<BatchTask>(`/batch/tasks/${taskId}`),
      api.get<BatchItem[]>(`/batch/tasks/${taskId}/items`),
    ])
    if (requestId !== loadRequestRef.current) return
    setItems(updatedItems)
    onTaskChange(updatedTask)
  }, [onTaskChange, taskId])
  useEffect(() => { void load().catch((error: Error) => Message.error(error.message)) }, [load])
  const hasActiveItems = items.some((item) => !terminalItemStatuses.includes(item.status))
  const batchIsActive = Boolean(task && ['PENDING', 'RUNNING'].includes(task.status))
  useEffect(() => {
    if (!taskId || (!batchIsActive && !hasActiveItems)) return
    const timer = window.setInterval(() => void load().catch(() => undefined), 2500)
    return () => window.clearInterval(timer)
  }, [batchIsActive, hasActiveItems, load, taskId])
  const retry = async (item: BatchItem) => {
    if (!taskId) return
    try {
      setRetryingId(item.id)
      const updatedItem = await api.post<BatchItem>(`/batch/tasks/${taskId}/items/${item.id}/retry`)
      setItems((current) => current.map((row) => row.id === updatedItem.id ? updatedItem : row))
      setSelected((current) => current.filter((id) => id !== item.id))
      Message.success('已加入重试队列')
      try {
        await load()
      } catch (error) {
        Message.warning(error instanceof Error ? `任务已重试，但状态刷新失败：${error.message}` : '任务已重试，请稍后刷新状态')
      }
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '重试失败')
    } finally {
      setRetryingId(undefined)
    }
  }
  const cancel = async (item: BatchItem) => {
    if (!taskId) return
    try {
      setCancellingId(item.id)
      const updatedItem = await api.post<BatchItem>(`/batch/tasks/${taskId}/items/${item.id}/cancel`)
      setItems((current) => current.map((row) => row.id === updatedItem.id ? updatedItem : row))
      setSelected((current) => current.filter((id) => id !== item.id))
      Message.success('已请求停止该任务')
      await load()
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '停止任务失败')
    } finally {
      setCancellingId(undefined)
    }
  }
  const showEvents = async (item: BatchItem) => { setEvents(await api.get<any[]>(`/batch/tasks/${task?.id}/items/${item.id}/events`)) }
  const showPreview = async (item: BatchItem) => {
    try {
      setPreviewLoadingId(item.id)
      const result = await api.get<any>(`/task_status/${item.task_id}`)
      const markdown = result.result?.markdown
      if (!markdown) {
        Message.warning('笔记内容暂不可预览')
        return
      }
      setPreview({
        markdown: userFacingMarkdown(markdown, item.platform),
        title: item.title || result.result?.audio_meta?.title || '本地视频笔记',
        sourceUrl: item.platform === 'local' ? undefined : item.source_url,
        itemId: item.id,
      })
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '读取笔记失败')
    } finally {
      setPreviewLoadingId(undefined)
    }
  }
  const exportItems = async (ids?: string[]) => { try { const blob = await api.download(`/batch/tasks/${task?.id}/export`, ids ? { item_ids: ids } : undefined); downloadBlob(blob, `${task?.title || 'notes'}.zip`) } catch (error) { Message.error(error instanceof Error ? error.message : '导出失败') } }
  const applyDeletion = async (result: { task: BatchTask; deleted_count: number }, deletedIds: string[]) => {
    setSelected((current) => current.filter((id) => !deletedIds.includes(id)))
    setPreview((current) => current && deletedIds.includes(current.itemId) ? undefined : current)
    setEvents([])
    onTaskChange(result.task)
    await load()
    Message.success(`已删除 ${result.deleted_count} 个子任务及相关资源`)
  }
  const deleteItem = async (item: BatchItem) => {
    if (!taskId) return
    try {
      const result = await api.delete<{ task: BatchTask; deleted_count: number }>(`/batch/tasks/${taskId}/items/${item.id}`)
      await applyDeletion(result, [item.id])
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '删除失败')
      throw error
    }
  }
  const deleteSelected = async () => {
    if (!taskId || !selected.length) return
    const ids = [...selected]
    try {
      const result = await api.post<{ task: BatchTask; deleted_count: number }>(`/batch/tasks/${taskId}/items/delete`, { item_ids: ids })
      await applyDeletion(result, ids)
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '批量删除失败')
      throw error
    }
  }
  return <><Drawer visible={Boolean(task)} width={1080} title={task ? `批量任务：${task.title}` : ''} onCancel={onClose} footer={null}>
    {task && <Tabs><Tabs.TabPane key="items" title="子任务">
      <Descriptions column={2} data={[{ label: '整体状态', value: <Status value={task.status} /> }, { label: '总体进度', value: <Progress percent={task.progress} size="small" /> }, { label: '成功 / 失败 / 取消', value: `${task.completed_count} / ${task.failed_count} / ${task.canceled_count}` }, { label: '视频总数', value: task.total_count }]} />
      <div className="toolbar" style={{ margin: '16px 0' }}>
        <Button icon={<IconEdit />} onClick={() => onEdit(task)}>编辑设置</Button>
        {canAppendBatch(task.status) && <Button type="primary" icon={<IconPlus />} onClick={() => onAppend(task)}>追加视频</Button>}
        <Button icon={<IconDownload />} onClick={() => void exportItems()}>导出全部</Button>
        <Button disabled={!selected.length} icon={<IconDownload />} onClick={() => void exportItems(selected)}>导出所选</Button>
        <Popconfirm title={`确认删除选中的 ${selected.length} 个子任务？`} content="对应笔记、缓存、截图和无引用的本地源文件将一并清理，此操作不可恢复。" okText="确认删除" cancelText="取消" okButtonProps={{ status: 'danger' }} disabled={!selected.length} onOk={deleteSelected}>
          <Button status="danger" icon={<IconDelete />} disabled={!selected.length}>批量删除</Button>
        </Popconfirm>
      </div>
      <Table
        className="batch-detail-table"
        rowKey="id"
        data={items}
        scroll={{ x: 1180 }}
        rowSelection={{
          selectedRowKeys: selected,
          onChange: (keys) => setSelected(keys as string[]),
          checkboxProps: (record) => ({ disabled: !terminalItemStatuses.includes(record.status) }),
        }}
        columns={[
          { title: '序号', dataIndex: 'position', width: 64 },
          { title: '视频', dataIndex: 'title', width: 240, ellipsis: true },
          { title: '状态', dataIndex: 'status', width: 130, render: (value: string) => <Status value={value} /> },
          { title: '日志', dataIndex: 'message', ellipsis: true, render: (value: string, record: BatchItem) => <Tooltip content={value}><Button type="text" size="small" onClick={() => void showEvents(record)}>{value || '查看日志'}</Button></Tooltip> },
          { title: '次数', dataIndex: 'attempts', width: 72 },
          { title: '操作', width: 286, fixed: 'right', bodyCellStyle: { whiteSpace: 'nowrap' }, render: (_: unknown, record: BatchItem) => {
            const canDelete = terminalItemStatuses.includes(record.status)
            return <Space className="batch-row-actions" size={4}>
              <Button type="text" size="small" loading={previewLoadingId === record.id} disabled={record.status !== 'SUCCESS'} onClick={() => void showPreview(record)}>阅读</Button>
              <Button type="text" size="small" disabled={record.status !== 'SUCCESS'} onClick={() => void exportItems([record.id])}>导出</Button>
              {['FAILED', 'CANCELED'].includes(record.status) && <Button type="text" status="warning" size="small" loading={retryingId === record.id} onClick={() => void retry(record)}>重试</Button>}
              {!terminalItemStatuses.includes(record.status) && <Popconfirm title="确认停止该视频任务？" content="当前正在执行的模型请求返回后将停止，已有转写和检查点会保留。" okText="停止" cancelText="继续" okButtonProps={{ status: 'danger' }} onOk={() => void cancel(record)}>
                <Button type="text" status="warning" size="small" loading={cancellingId === record.id}>停止</Button>
              </Popconfirm>}
              <Tooltip content={canDelete ? '删除子任务及相关资源' : '运行中或排队中的子任务不能删除'}>
                <span><Popconfirm title="确认删除该子任务？" content="对应笔记、缓存、截图和无引用的本地源文件将一并清理，此操作不可恢复。" okText="确认删除" cancelText="取消" okButtonProps={{ status: 'danger' }} disabled={!canDelete} onOk={() => deleteItem(record)}><Button type="text" status="danger" size="small" disabled={!canDelete}>删除</Button></Popconfirm></span>
              </Tooltip>
            </Space>
          } },
        ]}
      />
    </Tabs.TabPane><Tabs.TabPane key="logs" title="运行日志"><div className="terminal">{events.length ? events.map((event) => `[${event.created_at || '-'}] ${event.status} ${event.message || ''}`).join('\n') : '点击子任务的日志内容查看运行事件。'}</div></Tabs.TabPane></Tabs>
    }</Drawer>
    <Drawer
      className="markdown-reader-drawer"
      visible={Boolean(preview)}
      width="min(1380px, 97vw)"
      title="笔记阅读"
      onCancel={() => setPreview(undefined)}
      footer={null}
      unmountOnExit
    >
      {preview && (
        <MarkdownPreview
          content={preview.markdown}
          title={preview.title}
          sourceUrl={preview.sourceUrl}
          onDownload={() => void exportItems([preview.itemId])}
        />
      )}
    </Drawer>
  </>
}

function BatchTaskPage() {
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
      setTasks(result.items)
      setActive((current) => current ? result.items.find((item) => item.id === current.id) || current : undefined)
    } catch (error) {
      if (requestId === loadRequestRef.current) Message.error(error instanceof Error ? error.message : '加载失败')
    } finally {
      if (requestId === loadRequestRef.current) setLoading(false)
    }
  }, [])
  const handleTaskChange = useCallback((updated: BatchTask) => {
    setTasks((current) => current.map((item) => item.id === updated.id ? updated : item))
    setActive((current) => current?.id === updated.id ? updated : current)
  }, [])
  const openEdit = useCallback(async (task: BatchTask) => {
    try {
      setEditTarget(await api.get<BatchTask>(`/batch/tasks/${task.id}`))
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '加载任务设置失败')
    }
  }, [])
  useEffect(() => { void load() }, [load])
  const hasActiveTasks = tasks.some((item) => ['PENDING', 'RUNNING'].includes(item.status))
  useEffect(() => {
    if (!hasActiveTasks) return
    const timer = window.setInterval(() => void load(), 4000)
    return () => window.clearInterval(timer)
  }, [hasActiveTasks, load])
  const stats = useMemo(() => ({ running: tasks.filter((item) => item.status === 'RUNNING').length, complete: tasks.filter((item) => item.status === 'SUCCESS').length, failed: tasks.filter((item) => ['FAILED', 'PARTIAL_FAILED', 'CANCELED'].includes(item.status)).length }), [tasks])
  return <div className="page"><PageHeader title="批量合集任务" description="解析哔哩哔哩公开视频合集或上传本地文件夹，持续生成可导出的课程笔记。" actions={<><Button icon={<IconRefresh />} onClick={() => void load()}>刷新</Button><Button type="primary" icon={<IconPlus />} onClick={() => setCreateVisible(true)}>新建批量任务</Button></>} /><div className="stats"><div className="stat"><div className="stat-value">{tasks.length}</div><div className="stat-label">批量任务</div></div><div className="stat"><div className="stat-value">{stats.running}</div><div className="stat-label">正在运行</div></div><div className="stat"><div className="stat-value">{stats.complete}</div><div className="stat-label">已全部完成</div></div><div className="stat"><div className="stat-value">{stats.failed}</div><div className="stat-label">需要处理</div></div></div><Card><Table rowKey="id" loading={loading} data={tasks} scroll={{ x: 1200 }} onRow={(record) => ({ onClick: () => setActive(record) })} columns={[{ title: '任务名称', dataIndex: 'title', width: 260, ellipsis: true }, { title: '来源', dataIndex: 'source_type', width: 110 }, { title: '状态', dataIndex: 'status', width: 130, render: (value: string) => <Status value={value} /> }, { title: '进度', width: 220, render: (_: unknown, record: BatchTask) => <Progress percent={record.progress} size="small" /> }, { title: '完成 / 失败 / 总计', width: 160, render: (_: unknown, record: BatchTask) => `${record.completed_count} / ${record.failed_count} / ${record.total_count}` }, { title: '创建时间', dataIndex: 'created_at', width: 180, render: (value: string) => value ? new Date(value).toLocaleString() : '-' }, { title: '操作', width: 230, fixed: 'right', render: (_: unknown, record: BatchTask) => <Space><Button type="text" size="small" onClick={(event) => { event.stopPropagation(); setActive(record) }}>查看</Button><Button type="text" size="small" icon={<IconEdit />} onClick={(event) => { event.stopPropagation(); void openEdit(record) }}>编辑</Button>{canAppendBatch(record.status) && <Button type="text" size="small" icon={<IconPlus />} onClick={(event) => { event.stopPropagation(); setAppendTarget(record) }}>追加</Button>}</Space> }]} /></Card><CreateBatchModal visible={createVisible} onClose={() => setCreateVisible(false)} onCreated={load} /><AppendBatchModal task={appendTarget} visible={Boolean(appendTarget)} onClose={() => setAppendTarget(undefined)} onAppended={(updated) => { handleTaskChange(updated); void load() }} /><EditBatchModal task={editTarget} onClose={() => setEditTarget(undefined)} onUpdated={(updated) => { handleTaskChange(updated); setEditTarget(undefined); void load() }} /><BatchDetail task={active} onClose={() => setActive(undefined)} onAppend={setAppendTarget} onEdit={(task) => void openEdit(task)} onTaskChange={handleTaskChange} /></div>
}

function ModelSettings() {
  const { providers, models, refresh } = useProviders(); const [form] = Form.useForm(); const [modelForm] = Form.useForm(); const [adding, setAdding] = useState(false)
  const add = async () => { try { const values = await form.validate(); setAdding(true); await api.post('/add_provider', values); Message.success('供应商已添加'); form.resetFields(); void refresh() } catch (error) { Message.error(error instanceof Error ? error.message : '添加失败') } finally { setAdding(false) } }
  const addModel = async () => { try { await api.post('/models', await modelForm.validate()); Message.success('模型已添加'); modelForm.resetFields(); void refresh() } catch (error) { Message.error(error instanceof Error ? error.message : '添加失败') } }
  const test = async (provider: Provider) => { try { await api.post('/connect_test', { id: provider.id }); Message.success(`${provider.name} 连接成功`) } catch (error) { Message.error(error instanceof Error ? error.message : '连接失败') } }
  const removeModel = async (model: Model) => { try { await api.get(`/models/delete/${model.id}`); Message.success('模型已删除'); void refresh() } catch (error) { Message.error(error instanceof Error ? error.message : '删除失败') } }
  return <div className="page"><PageHeader title="AI 模型配置" description="维护模型供应商与可选择模型，批量任务创建时使用实时可用的配置。" /><Card title="新增供应商"><Form form={form} layout="vertical" initialValues={{ type: 'openai' }}><div className="form-grid"><Form.Item field="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item field="type" label="类型"><Select options={['openai', 'deepseek', 'qwen']} /></Form.Item><Form.Item field="base_url" label="API 地址" rules={[{ required: true }]}><Input /></Form.Item><Form.Item field="api_key" label="API Key" rules={[{ required: true }]}><Input.Password /></Form.Item></div><Button type="primary" loading={adding} onClick={add}>添加供应商</Button></Form></Card><Card title="已配置供应商" style={{ marginTop: 16 }}><Table rowKey="id" data={providers} pagination={false} columns={[{ title: '名称', dataIndex: 'name' }, { title: '类型', dataIndex: 'type' }, { title: '接口地址', dataIndex: 'base_url', ellipsis: true }, { title: '模型数', render: (_: unknown, row: Provider) => models.filter((model) => String(model.provider_id) === String(row.id)).length }, { title: '操作', render: (_: unknown, row: Provider) => <Button type="text" size="small" onClick={() => void test(row)}>连通性测试</Button> }]} /></Card><Card title="模型列表" style={{ marginTop: 16 }} extra={<Button icon={<IconRefresh />} type="text" onClick={() => void refresh()}>刷新</Button>}><Form form={modelForm} layout="inline" style={{ marginBottom: 16 }}><Form.Item field="provider_id" rules={[{ required: true }]}><Select placeholder="选择供应商" style={{ width: 220 }} options={providers.map((item) => ({ label: item.name, value: item.id }))} /></Form.Item><Form.Item field="model_name" rules={[{ required: true }]}><Input placeholder="模型名称" /></Form.Item><Button type="primary" onClick={addModel}>添加模型</Button></Form><Table rowKey="id" data={models} pagination={false} columns={[{ title: '模型名称', dataIndex: 'model_name' }, { title: '供应商', dataIndex: 'provider_id', render: (id: string) => providers.find((item) => String(item.id) === String(id))?.name || id }, { title: '操作', render: (_: unknown, row: Model) => <Button type="text" status="danger" size="small" onClick={() => void removeModel(row)}>删除</Button> }]} /></Card></div>
}

function TranscriberSettings() {
  const [form] = Form.useForm(); const [config, setConfig] = useState<any>(); const [modelStatus, setModelStatus] = useState<any[]>([]); const [saving, setSaving] = useState(false)
  const load = useCallback(async () => { const data = await api.get<any>('/transcriber_config'); setConfig(data); form.setFieldsValue(data) }, [form])
  useEffect(() => { void load().catch((error: Error) => Message.error(error.message)); void api.get<any>('/transcriber_models_status').then((data) => setModelStatus(data.whisper || [])).catch(() => undefined) }, [load])
  const save = async () => { try { setSaving(true); await api.post('/transcriber_config', await form.validate()); Message.success('转写配置已保存') } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败') } finally { setSaving(false) } }
  const download = async (modelSize: string) => { try { await api.post('/transcriber_download', { model_size: modelSize, transcriber_type: form.getFieldValue('transcriber_type') || 'fast-whisper' }); Message.success('模型下载已开始') } catch (error) { Message.error(error instanceof Error ? error.message : '下载失败') } }
  return <div className="page"><PageHeader title="音频转写配置" description="选择音频转写引擎和 Whisper 模型；本地模型需要先下载。" /><Card loading={!config}><Form form={form} layout="vertical"><div className="form-grid"><Form.Item field="transcriber_type" label="转写引擎"><Select options={config?.available_types || []} /></Form.Item><Form.Item field="whisper_model_size" label="Whisper 模型"><Select options={config?.whisper_model_sizes || []} /></Form.Item></div><Button type="primary" loading={saving} onClick={save}>保存配置</Button></Form></Card><Card title="本地模型状态" style={{ marginTop: 16 }}><Table rowKey="model_size" data={modelStatus} pagination={false} columns={[{ title: '模型', dataIndex: 'model_size' }, { title: '状态', render: (_: unknown, row: any) => row.downloaded ? <Tag color="green">已下载</Tag> : row.downloading ? <Tag color="arcoblue">下载中</Tag> : <Tag>未下载</Tag> }, { title: '操作', render: (_: unknown, row: any) => <Button type="text" size="small" disabled={row.downloaded || row.downloading} onClick={() => void download(row.model_size)}>下载</Button> }]} /></Card></div>
}

function DownloaderSettings() {
  const [form] = Form.useForm(); const [proxyForm] = Form.useForm(); const [loading, setLoading] = useState(false); const [cookieLoading, setCookieLoading] = useState(false)
  const platform = Form.useWatch('platform', form) || 'bilibili'
  useEffect(() => {
    let cancelled = false
    setCookieLoading(true)
    void api.get<{ platform: string; cookie: string } | null>(`/get_downloader_cookie/${platform}`)
      .then((data) => { if (!cancelled) form.setFieldValue('cookie', data?.cookie || '') })
      .catch((error) => { if (!cancelled) { form.setFieldValue('cookie', ''); Message.error(error instanceof Error ? error.message : '读取 Cookie 失败') } })
      .finally(() => { if (!cancelled) setCookieLoading(false) })
    return () => { cancelled = true }
  }, [form, platform])
  useEffect(() => { void api.get<any>('/proxy_config').then((data) => proxyForm.setFieldsValue(data)).catch(() => undefined) }, [proxyForm])
  const save = async () => { try { setLoading(true); await api.post('/update_downloader_cookie', await form.validate()); Message.success('下载配置已保存') } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败') } finally { setLoading(false) } }
  const saveProxy = async () => { try { await api.post('/proxy_config', await proxyForm.validate()); Message.success('代理配置已保存') } catch (error) { Message.error(error instanceof Error ? error.message : '保存失败') } }
  return <div className="page"><PageHeader title="下载配置" description="为需要登录、字幕或高质量下载的来源更新 Cookie 和代理。" /><Card title="平台 Cookie"><Form form={form} layout="vertical" initialValues={{ platform: 'bilibili' }}><Form.Item field="platform" label="视频平台"><Select options={[{ label: '哔哩哔哩', value: 'bilibili' }, { label: 'YouTube', value: 'youtube' }]} /></Form.Item><Form.Item field="cookie" label="Cookie" rules={[{ required: true, message: '请输入 Cookie' }]}><Input.TextArea autoSize={{ minRows: 5 }} /></Form.Item><Button type="primary" loading={loading || cookieLoading} onClick={save}>保存下载配置</Button></Form></Card><Card title="网络代理" style={{ marginTop: 16 }}><Form form={proxyForm} layout="vertical"><Form.Item field="enabled" label="启用代理" triggerPropName="checked"><Switch /></Form.Item><Form.Item field="url" label="代理地址"><Input placeholder="http://127.0.0.1:7890" /></Form.Item><Button onClick={saveProxy}>保存代理配置</Button></Form></Card></div>
}

function MonitorPage() {
  const [status, setStatus] = useState<any>(); const load = useCallback(async () => setStatus(await api.get('/deploy_status')), [])
  useEffect(() => { void load().catch((error: Error) => Message.error(error.message)); const timer = window.setInterval(() => void load(), 5000); return () => window.clearInterval(timer) }, [load])
  return <div className="page"><PageHeader title="部署监控" description="实时查看后端、FFmpeg、转写模型与 CUDA 的可用状态。" actions={<Button icon={<IconRefresh />} onClick={() => void load()}>刷新</Button>} /><Card loading={!status}>{status ? <Descriptions column={2} data={[{ label: '后端', value: status.backend?.status }, { label: '端口', value: status.backend?.port }, { label: 'FFmpeg', value: status.ffmpeg?.available ? <Tag color="green">可用</Tag> : <Tag color="red">不可用</Tag> }, { label: 'CUDA', value: status.cuda?.available ? status.cuda.gpu_name || '可用' : '不可用' }, { label: '转写引擎', value: status.whisper?.transcriber_type }, { label: 'Whisper 模型', value: `${status.whisper?.model_size || '-'} ${status.whisper?.downloaded ? '(已下载)' : ''}` }]} /> : <Empty />}</Card></div>
}

export default function App() {
  const [page, setPage] = useState('single')
  const content = page === 'single' ? <SingleTaskPage /> : page === 'batch' ? <BatchTaskPage /> : page === 'models' ? <ModelSettings /> : page === 'transcriber' ? <TranscriberSettings /> : page === 'downloader' ? <DownloaderSettings /> : <MonitorPage />
  return <Layout className="app-layout"><Sider width={220} theme="dark" collapsible><div className="brand">BiliNote Admin</div><Menu theme="dark" selectedKeys={[page]} onClickMenuItem={setPage}><Menu.Item key="single"><IconLink />单视频任务</Menu.Item><Menu.Item key="batch"><IconApps />批量合集任务</Menu.Item><Menu.Item key="models"><IconRobot />AI 模型配置</Menu.Item><Menu.Item key="transcriber"><IconStorage />音频转写</Menu.Item><Menu.Item key="downloader"><IconDownload />下载配置</Menu.Item><Menu.Item key="monitor"><IconCloud />部署监控</Menu.Item></Menu></Sider><Layout><Header style={{ height: 64, background: 'var(--color-bg-2)', borderBottom: '1px solid var(--color-border-2)', display: 'flex', alignItems: 'center', padding: '0 24px', color: 'var(--color-text-2)' }}><IconSettings style={{ marginRight: 8 }} />独立管理控制台</Header><Content>{content}</Content></Layout></Layout>
}
