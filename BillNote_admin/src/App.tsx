import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  Button, Card, Checkbox, Descriptions, Drawer, Empty, Form, Input, Layout, Menu, Message, Modal,
  Progress, Radio, Select, Space, Steps, Switch, Table, Tabs, Tag, Tooltip,
} from '@arco-design/web-react'
import {
  IconApps, IconCloud, IconDownload, IconLink, IconPlus, IconRefresh, IconRobot, IconSettings, IconStorage,
} from '@arco-design/web-react/icon'
import { api, downloadBlob } from './api'
import MarkdownPreview from './MarkdownPreview'
import type { BatchItem, BatchTask, Model, Provider } from './types'

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
  SUCCESS: 'green', FAILED: 'red', PARTIAL_FAILED: 'orange', RUNNING: 'arcoblue',
  PENDING: 'gray', PARSING: 'arcoblue', DOWNLOADING: 'arcoblue', TRANSCRIBING: 'arcoblue',
  SUMMARIZING: 'arcoblue', FORMATTING: 'arcoblue', SAVING: 'arcoblue',
}

function normaliseOptions(values: any) {
  const grid = typeof values.grid_size === 'string'
    ? values.grid_size.split(',').map((value: string) => Number(value.trim())).filter(Boolean)
    : values.grid_size
  return { ...values, grid_size: Array.isArray(grid) && grid.length === 2 ? grid : [2, 2] }
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
      {videoUnderstanding && <><Form.Item field="video_interval" label="抽帧间隔（秒）"><Input type="number" /></Form.Item><Form.Item field="grid_size" label="拼图网格"><Input placeholder="例如 2,2" /></Form.Item></>}
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
  const [task, setTask] = useState<any>()
  const localFile = useRef<HTMLInputElement>(null)
  const poll = useCallback(async (taskId: string) => {
    try { setTask(await api.get<any>(`/task_status/${taskId}`)) } catch (error) { setTask({ status: 'FAILED', message: String(error) }) }
  }, [])
  useEffect(() => {
    if (!task?.task_id || ['SUCCESS', 'FAILED'].includes(task.status)) return
    const timer = window.setInterval(() => void poll(task.task_id), 2500)
    return () => window.clearInterval(timer)
  }, [task?.task_id, task?.status, poll])
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
      setTask({ task_id: result.task_id, status: 'PENDING', message: '任务已提交' })
      Message.success('单视频生成任务已提交')
    } catch (error) { Message.error(error instanceof Error ? error.message : '提交失败') } finally { setSubmitting(false) }
  }
  return <div className="page">
    <PageHeader title="单视频生成" description="支持哔哩哔哩、YouTube、抖音、快手与本地视频，使用与原应用一致的生成配置。" />
    <Card><Form form={form} layout="vertical" initialValues={{ platform: 'bilibili', quality: 'medium', style: 'minimal', format: [], video_interval: 6, grid_size: '2,2' }} scrollToFirstError>
      <div className="form-grid">
        <Form.Item field="platform" label="视频来源"><Select options={[{ label: '哔哩哔哩', value: 'bilibili' }, { label: 'YouTube', value: 'youtube' }, { label: '抖音', value: 'douyin' }, { label: '快手', value: 'kuaishou' }, { label: '本地视频', value: 'local' }]} /></Form.Item>
        <Form.Item shouldUpdate noStyle>{() => form.getFieldValue('platform') === 'local' ? <Form.Item label="本地视频" required><input ref={localFile} type="file" accept="video/*" /></Form.Item> : <Form.Item field="video_url" label="视频链接" rules={[{ required: true, message: '请输入视频链接' }]}><Input placeholder="粘贴视频链接" /></Form.Item>}</Form.Item>
      </div>
      <ModelFields form={form} providers={providers} models={models} />
      <NoteOptions form={form} />
      <Button type="primary" icon={<IconRobot />} loading={submitting} onClick={submit}>开始生成</Button>
    </Form></Card>
    {task && <Card className="single-task-result" style={{ marginTop: 16 }} title="当前任务">
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Space><Status value={task.status} /><span>{task.message || (task.status === 'SUCCESS' ? '笔记已生成，可直接阅读' : '正在处理')}</span></Space>
        {task.result?.markdown && (
          <MarkdownPreview
            content={task.result.markdown}
            title={task.result?.audio_meta?.title || '单视频 AI 笔记'}
          />
        )}
      </Space>
    </Card>}
  </div>
}

type PreviewItem = { title: string; source_url: string; platform: string; position?: number }

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
      const config = await configForm.validate()
      if (!preview || !selected.length) throw new Error('至少选择一个视频')
      setLoading(true)
      await api.post('/batch/tasks', { title: preview.title, source_type: sourceType, source_value: sourceForm.getFieldValue('source_url'), config: { ...normaliseOptions(config), link: config.format?.includes('link'), screenshot: config.format?.includes('screenshot') }, items: preview.items.filter((item) => selected.includes(item.source_url)) })
      Message.success('批量任务已创建并开始执行'); onCreated(); reset()
    } catch (error) { Message.error(error instanceof Error ? error.message : '创建失败') } finally { setLoading(false) }
  }
  return <Modal visible={visible} title="新建批量合集任务" style={{ width: 880 }} footer={null} onCancel={reset} unmountOnExit>
    <Steps current={step} style={{ marginBottom: 24 }}><Step title="分析视频列表" /><Step title="配置生成任务" /></Steps>
    {step === 0 ? <Form form={sourceForm} layout="vertical" initialValues={{ source_type: 'bilibili' }}>
      <Form.Item field="source_type" label="批量任务类型"><Radio.Group><Radio value="bilibili">哔哩哔哩合集</Radio><Radio value="local">本地文件夹</Radio></Radio.Group></Form.Item>
      {sourceType === 'bilibili' ? <Form.Item field="source_url" label="合集链接" rules={[{ required: true, message: '请输入哔哩哔哩合集或视频链接' }]}><Input placeholder="例如 https://www.bilibili.com/video/BV1rv7A6oEeP/" /></Form.Item> : <Form.Item label="本地视频文件夹" required extra="选择文件夹后会上传视频并展示可选列表"><input type="file" multiple accept="video/*" {...({ webkitdirectory: '', directory: '' } as any)} onChange={(event) => setFiles(Array.from(event.target.files || []))} /></Form.Item>}
      <Space><Button type="primary" loading={loading} onClick={analyse}>分析视频</Button><Button onClick={reset}>取消</Button></Space>
    </Form> : <>
      <div className="source-list"><Table rowKey="source_url" size="small" data={preview?.items || []} pagination={false} rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys as string[]), checkAll: true }} columns={[{ title: '序号', dataIndex: 'position', width: 72, render: (_: unknown, record: PreviewItem, index: number) => index + 1 }, { title: '视频标题', dataIndex: 'title', ellipsis: true }, { title: '来源', dataIndex: 'platform', width: 110 }]} /></div>
      <Form form={configForm} layout="vertical" initialValues={{ quality: 'medium', style: 'tutorial', format: ['toc', 'summary'], video_understanding: false, video_interval: 6, grid_size: '2,2' }} style={{ marginTop: 16 }} scrollToFirstError><ModelFields form={configForm} providers={providers} models={models} /><NoteOptions form={configForm} /></Form>
      <Space><Button onClick={() => setStep(0)}>上一步</Button><Button type="primary" loading={loading} onClick={create}>开始批量任务</Button></Space>
    </>}
  </Modal>
}

function BatchDetail({ task, onClose }: { task?: BatchTask; onClose: () => void }) {
  const [items, setItems] = useState<BatchItem[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [events, setEvents] = useState<any[]>([])
  const [preview, setPreview] = useState<{ markdown: string; title: string; sourceUrl: string; itemId: string }>()
  const [previewLoadingId, setPreviewLoadingId] = useState<string>()
  const load = useCallback(async () => { if (task) setItems(await api.get<BatchItem[]>(`/batch/tasks/${task.id}/items`)) }, [task])
  useEffect(() => { void load().catch((error: Error) => Message.error(error.message)) }, [load])
  useEffect(() => { if (!task || ['SUCCESS', 'FAILED', 'PARTIAL_FAILED'].includes(task.status)) return; const timer = window.setInterval(() => void load(), 2500); return () => window.clearInterval(timer) }, [task, load])
  const retry = async (item: BatchItem) => { try { await api.post(`/batch/tasks/${task?.id}/items/${item.id}/retry`); Message.success('已加入重试队列'); void load() } catch (error) { Message.error(error instanceof Error ? error.message : '重试失败') } }
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
        markdown,
        title: result.result?.audio_meta?.title || item.title,
        sourceUrl: item.source_url,
        itemId: item.id,
      })
    } catch (error) {
      Message.error(error instanceof Error ? error.message : '读取笔记失败')
    } finally {
      setPreviewLoadingId(undefined)
    }
  }
  const exportItems = async (ids?: string[]) => { try { const blob = await api.download(`/batch/tasks/${task?.id}/export`, ids ? { item_ids: ids } : undefined); downloadBlob(blob, `${task?.title || 'notes'}.zip`) } catch (error) { Message.error(error instanceof Error ? error.message : '导出失败') } }
  return <><Drawer visible={Boolean(task)} width={980} title={task ? `批量任务：${task.title}` : ''} onCancel={onClose} footer={null}>
    {task && <Tabs><Tabs.TabPane key="items" title="子任务"><Descriptions column={2} data={[{ label: '整体状态', value: <Status value={task.status} /> }, { label: '总体进度', value: <Progress percent={task.progress} size="small" /> }, { label: '成功 / 失败', value: `${task.completed_count} / ${task.failed_count}` }, { label: '视频总数', value: task.total_count }]} /><div className="toolbar" style={{ margin: '16px 0' }}><Button icon={<IconDownload />} onClick={() => void exportItems()}>导出全部</Button><Button disabled={!selected.length} icon={<IconDownload />} onClick={() => void exportItems(selected)}>导出所选</Button></div><Table rowKey="id" data={items} scroll={{ x: 980 }} rowSelection={{ selectedRowKeys: selected, onChange: (keys) => setSelected(keys as string[]) }} columns={[{ title: '序号', dataIndex: 'position', width: 64 }, { title: '视频', dataIndex: 'title', width: 240, ellipsis: true }, { title: '状态', dataIndex: 'status', width: 130, render: (value: string) => <Status value={value} /> }, { title: '日志', dataIndex: 'message', ellipsis: true, render: (value: string, record: BatchItem) => <Tooltip content={value}><Button type="text" size="small" onClick={() => void showEvents(record)}>{value || '查看日志'}</Button></Tooltip> }, { title: '次数', dataIndex: 'attempts', width: 72 }, { title: '操作', width: 190, fixed: 'right', render: (_: unknown, record: BatchItem) => <Space><Button type="text" size="small" loading={previewLoadingId === record.id} disabled={record.status !== 'SUCCESS'} onClick={() => void showPreview(record)}>阅读</Button><Button type="text" size="small" disabled={record.status !== 'SUCCESS'} onClick={() => void exportItems([record.id])}>导出</Button>{record.status === 'FAILED' && <Button type="text" status="warning" size="small" onClick={() => void retry(record)}>重试</Button>}</Space> }]} /></Tabs.TabPane><Tabs.TabPane key="logs" title="运行日志"><div className="terminal">{events.length ? events.map((event) => `[${event.created_at || '-'}] ${event.status} ${event.message || ''}`).join('\n') : '点击子任务的日志内容查看运行事件。'}</div></Tabs.TabPane></Tabs>
    }</Drawer>
    <Drawer
      className="markdown-reader-drawer"
      visible={Boolean(preview)}
      width="min(1240px, 96vw)"
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
  const load = useCallback(async () => { setLoading(true); try { const result = await api.get<{ items: BatchTask[] }>('/batch/tasks'); setTasks(result.items) } catch (error) { Message.error(error instanceof Error ? error.message : '加载失败') } finally { setLoading(false) } }, [])
  useEffect(() => { void load() }, [load])
  const hasActiveTasks = tasks.some((item) => ['PENDING', 'RUNNING'].includes(item.status))
  useEffect(() => {
    if (!hasActiveTasks) return
    const timer = window.setInterval(() => void load(), 4000)
    return () => window.clearInterval(timer)
  }, [hasActiveTasks, load])
  const stats = useMemo(() => ({ running: tasks.filter((item) => item.status === 'RUNNING').length, complete: tasks.filter((item) => item.status === 'SUCCESS').length, failed: tasks.filter((item) => ['FAILED', 'PARTIAL_FAILED'].includes(item.status)).length }), [tasks])
  return <div className="page"><PageHeader title="批量合集任务" description="解析哔哩哔哩公开视频合集或上传本地文件夹，持续生成可导出的课程笔记。" actions={<><Button icon={<IconRefresh />} onClick={() => void load()}>刷新</Button><Button type="primary" icon={<IconPlus />} onClick={() => setCreateVisible(true)}>新建批量任务</Button></>} /><div className="stats"><div className="stat"><div className="stat-value">{tasks.length}</div><div className="stat-label">批量任务</div></div><div className="stat"><div className="stat-value">{stats.running}</div><div className="stat-label">正在运行</div></div><div className="stat"><div className="stat-value">{stats.complete}</div><div className="stat-label">已全部完成</div></div><div className="stat"><div className="stat-value">{stats.failed}</div><div className="stat-label">需要处理</div></div></div><Card><Table rowKey="id" loading={loading} data={tasks} scroll={{ x: 1050 }} onRow={(record) => ({ onClick: () => setActive(record) })} columns={[{ title: '任务名称', dataIndex: 'title', width: 260, ellipsis: true }, { title: '来源', dataIndex: 'source_type', width: 110 }, { title: '状态', dataIndex: 'status', width: 130, render: (value: string) => <Status value={value} /> }, { title: '进度', width: 220, render: (_: unknown, record: BatchTask) => <Progress percent={record.progress} size="small" /> }, { title: '完成 / 失败 / 总计', width: 160, render: (_: unknown, record: BatchTask) => `${record.completed_count} / ${record.failed_count} / ${record.total_count}` }, { title: '创建时间', dataIndex: 'created_at', width: 180, render: (value: string) => value ? new Date(value).toLocaleString() : '-' }, { title: '操作', width: 90, fixed: 'right', render: (_: unknown, record: BatchTask) => <Button type="text" size="small" onClick={(event) => { event.stopPropagation(); setActive(record) }}>查看</Button> }]} /></Card><CreateBatchModal visible={createVisible} onClose={() => setCreateVisible(false)} onCreated={load} /><BatchDetail task={active} onClose={() => setActive(undefined)} /></div>
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
