import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import { backendInfo, desktop, type DesktopSettings } from '../ipc'
import { EmptyState, Field, useConfirm, useToast } from '../ui'

type Provider = { id: number; name: string; type: string; base_url: string }
type Model = { id: number; model_name: string; provider_id: number }

type TabKey = 'models' | 'transcriber' | 'downloader' | 'service'

export default function SettingsPage() {
  const [tab, setTab] = useState<TabKey>('models')
  const tabs: { key: TabKey; label: string }[] = [
    { key: 'models', label: 'AI 模型' },
    { key: 'transcriber', label: '音频转写' },
    { key: 'downloader', label: '下载配置' },
    { key: 'service', label: '服务与数据' },
  ]
  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h2>设置</h2>
          <p>模型、转写、下载与服务参数集中管理。</p>
        </div>
        <div className="toolbar">
          <div className="tab-switch">
            {tabs.map((item) => (
              <button key={item.key} type="button" className={tab === item.key ? 'active' : ''} onClick={() => setTab(item.key)}>
                {item.label}
              </button>
            ))}
          </div>
        </div>
      </header>
      {tab === 'models' && <ModelsTab />}
      {tab === 'transcriber' && <TranscriberTab />}
      {tab === 'downloader' && <DownloaderTab />}
      {tab === 'service' && <ServiceTab />}
    </div>
  )
}

function ModelsTab() {
  const toast = useToast()
  const [providers, setProviders] = useState<Provider[]>([])
  const [models, setModels] = useState<Model[]>([])
  const [name, setName] = useState('')
  const [type, setType] = useState('openai')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [modelName, setModelName] = useState('')
  const [modelProviderId, setModelProviderId] = useState<number | string | ''>('')

  const refresh = useCallback(async () => {
    try {
      const [providerRows, modelRows] = await Promise.all([
        api.get<Provider[]>('/get_all_providers'), api.get<Model[]>('/model_list'),
      ])
      setProviders(providerRows || [])
      setModels(modelRows || [])
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '加载失败')
    }
  }, [toast])

  useEffect(() => { void refresh() }, [refresh])

  const addProvider = async () => {
    if (!name.trim() || !baseUrl.trim() || !apiKey.trim()) { toast('warn', '请完整填写名称、API 地址与 Key'); return }
    try {
      await api.post('/add_provider', { name: name.trim(), type, base_url: baseUrl.trim(), api_key: apiKey })
      toast('ok', '供应商已添加')
      setName(''); setBaseUrl(''); setApiKey('')
      void refresh()
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '添加失败')
    }
  }

  const addModel = async () => {
    if (!modelProviderId || !modelName.trim()) { toast('warn', '请选择供应商并填写模型名'); return }
    try {
      await api.post('/models', { provider_id: modelProviderId, model_name: modelName.trim() })
      toast('ok', '模型已添加')
      setModelName('')
      void refresh()
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '添加失败')
    }
  }

  const testProvider = async (provider: Provider) => {
    try {
      await api.post('/connect_test', { id: provider.id })
      toast('ok', `${provider.name} 连接成功`)
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '连接失败')
    }
  }

  const removeModel = async (model: Model) => {
    try {
      await api.get(`/models/delete/${model.id}`)
      toast('ok', '模型已删除')
      void refresh()
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '删除失败')
    }
  }

  return (
    <div className="stack">
      <section className="card">
        <div className="section-title">新增供应商</div>
        <div className="form-grid">
          <Field label="名称" required><input className="input" value={name} onChange={(event) => setName(event.target.value)} /></Field>
          <Field label="类型">
            <select className="input" value={type} onChange={(event) => setType(event.target.value)}>
              {['openai', 'deepseek', 'qwen'].map((item) => <option key={item} value={item}>{item}</option>)}
            </select>
          </Field>
          <Field label="API 地址" required><input className="input" value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} placeholder="https://api.example.com/v1" /></Field>
          <Field label="API Key" required><input className="input" type="password" value={apiKey} onChange={(event) => setApiKey(event.target.value)} /></Field>
        </div>
        <div className="row end"><button type="button" className="btn primary" onClick={() => void addProvider()}>添加供应商</button></div>
      </section>

      <section className="card">
        <div className="section-title">已配置供应商</div>
        {providers.length === 0 ? <EmptyState title="暂无供应商" hint="先添加一个 LLM 供应商" /> : (
          <table className="table">
            <thead><tr><th>名称</th><th>类型</th><th>接口地址</th><th>模型数</th><th className="right">操作</th></tr></thead>
            <tbody>
              {providers.map((provider) => (
                <tr key={provider.id}>
                  <td>{provider.name}</td><td><span className="pill">{provider.type}</span></td>
                  <td className="ellipsis dim">{provider.base_url}</td>
                  <td>{models.filter((model) => String(model.provider_id) === String(provider.id)).length}</td>
                  <td className="right actions"><button type="button" className="btn text" onClick={() => void testProvider(provider)}>连通性测试</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card">
        <div className="section-title">模型列表</div>
        <div className="row">
          <select className="input" value={modelProviderId} onChange={(event) => setModelProviderId(event.target.value || '')}>
            <option value="">选择供应商</option>
            {providers.map((provider) => <option key={provider.id} value={provider.id}>{provider.name}</option>)}
          </select>
          <input className="input" value={modelName} onChange={(event) => setModelName(event.target.value)} placeholder="模型名称" />
          <button type="button" className="btn primary" onClick={() => void addModel()}>添加模型</button>
        </div>
        {models.length > 0 && (
          <table className="table">
            <thead><tr><th>模型名称</th><th>供应商</th><th className="right">操作</th></tr></thead>
            <tbody>
              {models.map((model) => (
                <tr key={model.id}>
                  <td>{model.model_name}</td>
                  <td className="dim">{providers.find((provider) => String(provider.id) === String(model.provider_id))?.name || model.provider_id}</td>
                  <td className="right actions"><button type="button" className="btn text bad" onClick={() => void removeModel(model)}>删除</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}

type TranscriberConfig = {
  transcriber_type?: string
  whisper_model_size?: string
  available_types?: { value: string; label: string }[]
  whisper_model_sizes?: string[]
  whisper_custom_models?: Record<string, string>
  model_dir?: string
}

type ModelStatusRow = { model_size: string; downloaded?: boolean; downloading?: boolean }

function TranscriberTab() {
  const toast = useToast()
  const confirm = useConfirm()
  const [config, setConfig] = useState<TranscriberConfig | null>(null)
  const [modelStatus, setModelStatus] = useState<ModelStatusRow[]>([])
  const [saving, setSaving] = useState(false)
  const [importing, setImporting] = useState(false)

  const load = useCallback(async () => {
    try {
      setConfig(await api.get<TranscriberConfig>('/transcriber_config'))
      const status = await api.get<{ whisper?: ModelStatusRow[] }>('/transcriber_models_status')
      setModelStatus(status.whisper || [])
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '加载转写配置失败')
    }
  }, [toast])

  useEffect(() => { void load() }, [load])

  const save = async () => {
    if (!config?.transcriber_type || !config?.whisper_model_size) return
    setSaving(true)
    try {
      await api.post('/transcriber_config', { transcriber_type: config.transcriber_type, whisper_model_size: config.whisper_model_size })
      toast('ok', '转写配置已保存')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const downloadModel = async (modelSize: string) => {
    try {
      await api.post('/transcriber_download', { model_size: modelSize, transcriber_type: config?.transcriber_type || 'fast-whisper' })
      toast('ok', '模型下载已开始，完成后状态自动刷新')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '下载失败')
    }
  }

  const removeModel = async (name: string) => {
    const ok = await confirm({
      title: '移除该模型登记？',
      content: '只移除下拉里的模型条目，不会删除磁盘上的模型文件。',
      danger: true, okText: '移除',
    })
    if (!ok) return
    try {
      await api.delete(`/transcriber/custom_model/${encodeURIComponent(name)}`)
      toast('ok', '已移除')
      void load()
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '移除失败')
    }
  }

  const importLocalModel = async () => {
    setImporting(true)
    try {
      const picked = await desktop.pickFolder()
      if (!picked || !picked.length) return
      const result = await api.post<{ name: string }>('/transcriber/import_local_model', { path: picked[0] })
      toast('ok', result.name ? `已导入本地模型「${result.name}」` : '已导入本地模型')
      void load()
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '导入失败')
    } finally {
      setImporting(false)
    }
  }

  const openModelDir = async () => {
    if (config?.model_dir) {
      await desktop.openPath(config.model_dir)
    } else {
      await desktop.openDataDir()
    }
  }

  const customNames = Object.keys(config?.whisper_custom_models || {})

  return (
    <div className="stack">
      <section className="card">
        <div className="section-title">转写引擎</div>
        {!config ? <EmptyState title="加载中…" /> : (
          <>
            <div className="form-grid">
              <Field label="转写引擎">
                <select className="input" value={config.transcriber_type}
                  onChange={(event) => setConfig({ ...config, transcriber_type: event.target.value })}>
                  {(config.available_types || []).map((item) => (
                    <option key={item.value} value={item.value}>{item.label}</option>
                  ))}
                </select>
              </Field>
              <Field label="Whisper 模型" hint="模型越大越准、越慢；带「本地」前缀的是导入的磁盘模型">
                <select className="input" value={config.whisper_model_size}
                  onChange={(event) => setConfig({ ...config, whisper_model_size: event.target.value })}>
                  {(config.whisper_model_sizes || []).map((item) => <option key={item} value={item}>{item}</option>)}
                </select>
              </Field>
            </div>
            <div className="row end"><button type="button" className="btn primary" disabled={saving} onClick={() => void save()}>保存配置</button></div>
          </>
        )}
      </section>

      <section className="card">
        <div className="section-title">本地模型</div>
        <p className="hint">
          模型目录：<code className="mono">{config?.model_dir || '—'}</code>
          <button type="button" className="btn text" onClick={() => void openModelDir()}>打开目录</button>
        </p>
        <p className="hint">
          之前在网页版 / Docker 下载过的模型可以零拷贝迁移：点「导入本地模型」选择含有 model.bin 的模型目录
          （或 models-- 开头的 HF 缓存目录）即可，无需重新下载。
        </p>
        <div className="row" style={{ marginBottom: 12 }}>
          <button type="button" className="btn primary" disabled={importing} onClick={() => void importLocalModel()}>
            {importing ? '导入中…' : '导入本地模型'}
          </button>
          <button type="button" className="btn" onClick={() => void load()}>刷新状态</button>
        </div>
        {modelStatus.length === 0 ? <EmptyState title="暂无本地模型记录" /> : (
          <table className="table">
            <thead><tr><th>模型</th><th>状态</th><th className="right">操作</th></tr></thead>
            <tbody>
              {modelStatus.map((row) => {
                const custom = customNames.includes(row.model_size)
                return (
                  <tr key={row.model_size}>
                    <td>
                      {row.model_size}
                      {custom && <span className="pill accent" style={{ marginLeft: 8 }}>本地导入</span>}
                    </td>
                    <td>
                      {row.downloaded
                        ? <span className="status-tag ok">已下载</span>
                        : row.downloading ? <span className="status-tag busy">下载中</span> : <span className="status-tag dim">未下载</span>}
                    </td>
                    <td className="right actions">
                      {custom && (
                        <button type="button" className="btn text bad" onClick={() => void removeModel(row.model_size)}>移除</button>
                      )}
                      {!row.downloaded && !row.downloading && !custom && (
                        <button type="button" className="btn text" onClick={() => void downloadModel(row.model_size)}>下载</button>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}

function DownloaderTab() {
  const toast = useToast()
  const [platform, setPlatform] = useState('bilibili')
  const [cookie, setCookie] = useState('')
  const [saving, setSaving] = useState(false)
  const [proxyEnabled, setProxyEnabled] = useState(false)
  const [proxyUrl, setProxyUrl] = useState('')

  useEffect(() => {
    void api.get<{ platform: string; cookie: string } | null>(`/get_downloader_cookie/${platform}`)
      .then((data) => setCookie(data?.cookie || ''))
      .catch(() => setCookie(''))
  }, [platform])

  useEffect(() => {
    void api.get<{ enabled: boolean; url: string }>('/proxy_config')
      .then((data) => { setProxyEnabled(Boolean(data.enabled)); setProxyUrl(data.url || '') })
      .catch(() => undefined)
  }, [])

  const saveCookie = async () => {
    if (!cookie.trim()) { toast('warn', '请输入 Cookie'); return }
    setSaving(true)
    try {
      await api.post('/update_downloader_cookie', { platform, cookie })
      toast('ok', 'Cookie 已保存')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const saveProxy = async () => {
    try {
      await api.post('/proxy_config', { enabled: proxyEnabled, url: proxyUrl })
      toast('ok', '代理配置已保存')
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '保存失败')
    }
  }

  return (
    <div className="stack">
      <section className="card">
        <div className="section-title">平台 Cookie</div>
        <Field label="视频平台">
          <select className="input" value={platform} onChange={(event) => setPlatform(event.target.value)}>
            <option value="bilibili">哔哩哔哩</option>
            <option value="youtube">YouTube</option>
          </select>
        </Field>
        <Field label="Cookie" hint="用于需要登录的平台下载">
          <textarea className="input" rows={5} value={cookie} onChange={(event) => setCookie(event.target.value)} />
        </Field>
        <div className="row end"><button type="button" className="btn primary" disabled={saving} onClick={() => void saveCookie()}>保存 Cookie</button></div>
      </section>
      <section className="card">
        <div className="section-title">网络代理</div>
        <Field label="启用代理">
          <button type="button" className={`switch ${proxyEnabled ? 'on' : ''}`} onClick={() => setProxyEnabled((value) => !value)}><span className="switch-thumb" /></button>
        </Field>
        <Field label="代理地址"><input className="input" value={proxyUrl} onChange={(event) => setProxyUrl(event.target.value)} placeholder="http://127.0.0.1:7890" /></Field>
        <div className="row end"><button type="button" className="btn" onClick={() => void saveProxy()}>保存代理配置</button></div>
      </section>
    </div>
  )
}

function ServiceTab() {
  const toast = useToast()
  const [info, setInfo] = useState<typeof backendInfo>(null)
  const [settings, setSettings] = useState<DesktopSettings | null>(null)
  const [dataDirInput, setDataDirInput] = useState('')
  const [portInput, setPortInput] = useState('')
  const [restarting, setRestarting] = useState(false)

  const load = useCallback(async () => {
    setInfo(await desktop.getInfo())
    const current = await desktop.getSettings()
    setSettings(current)
    setDataDirInput(current.data_dir || '')
    setPortInput(current.port ? String(current.port) : '')
  }, [])

  useEffect(() => { void load() }, [load])

  const save = async (applyNow: boolean) => {
    const next: DesktopSettings = {
      port: portInput ? Number(portInput) : null,
      data_dir: dataDirInput.trim() || null,
    }
    try {
      await desktop.saveSettings(next)
      toast('ok', applyNow ? '设置已保存，正在重启后端服务…' : '设置已保存，重启应用后生效')
      if (applyNow) {
        setRestarting(true)
        await desktop.restartBackend()
        window.setTimeout(async () => {
          await load()
          setRestarting(false)
          toast('ok', '后端服务已重启')
        }, 2500)
      }
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '保存失败')
    }
  }

  const restart = async () => {
    setRestarting(true)
    try {
      await desktop.restartBackend()
      window.setTimeout(async () => {
        await load()
        setRestarting(false)
        toast('ok', '后端服务已重启')
      }, 2500)
    } catch (error) {
      setRestarting(false)
      toast('bad', error instanceof Error ? error.message : '重启失败')
    }
  }

  return (
    <div className="stack">
      <section className="card">
        <div className="section-title">数据目录（缓存与产物）</div>
        <p className="hint">
          笔记产物、截图、上传缓存、数据库、Whisper 模型与日志都存放在该目录。修改后需重启后端生效；
          更换目录不会自动迁移旧数据，需要手动搬移。
        </p>
        <Field label="数据目录">
          <div className="row">
            <input className="input" value={dataDirInput} onChange={(event) => setDataDirInput(event.target.value)} placeholder="默认为系统数据目录下的 BiliNote 文件夹" />
            <button type="button" className="btn" onClick={() => void desktop.openDataDir()}>打开目录</button>
          </div>
        </Field>
        <div className="dim">当前生效：{info?.data_dir || '—'}</div>
      </section>

      <section className="card">
        <div className="section-title">局域网服务（Android 阅读端）</div>
        <p className="hint">
          内置服务监听所有网卡，同一局域网内的 Android 客户端可直接连接阅读笔记产物。
          当前网络为明文 HTTP 且未启用鉴权，请仅在可信网络内开放。
        </p>
        <Field label="服务端口" hint="默认 8483，被占用时自动顺延">
          <input className="input" style={{ maxWidth: 160 }} value={portInput} onChange={(event) => setPortInput(event.target.value)} placeholder="8483" />
        </Field>
        <div className="address-box">
          <div className="address-row">
            <span className="pill accent">本机</span>
            <code>{info?.base_url || '—'}</code>
          </div>
          {(info?.lan_base_urls || []).map((url) => (
            <div key={url} className="address-row">
              <span className="pill">局域网</span>
              <code>{url}</code>
              <button type="button" className="btn text" onClick={() => { void navigator.clipboard.writeText(url); toast('ok', '地址已复制') }}>复制</button>
            </div>
          ))}
          {(info?.lan_base_urls || []).length === 0 && <div className="dim">未检测到局域网网卡，Android 设备暂无法连接。</div>}
        </div>
        <p className="hint">Android 端：设置 → 服务地址，填入上方局域网地址即可浏览与导出笔记。</p>
      </section>

      <section className="card">
        <div className="section-title">后端服务控制</div>
        <div className="row">
          <button type="button" className="btn primary" disabled={restarting} onClick={() => void save(true)}>
            {restarting ? '重启中…' : '保存并重启服务'}
          </button>
          <button type="button" className="btn" disabled={restarting} onClick={() => void save(false)}>仅保存</button>
          <button type="button" className="btn ghost" disabled={restarting} onClick={() => void restart()}>重启服务</button>
        </div>
      </section>
    </div>
  )
}
