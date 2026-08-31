import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import { desktop } from '../ipc'
import { EmptyState, Spinner, StatusTag, useToast } from '../ui'

type DeployStatus = {
  backend?: { status?: string; port?: number }
  ffmpeg?: { available?: boolean; version?: string }
  cuda?: { available?: boolean; inference_ready?: boolean; gpu_name?: string; detect_backend?: string | null; torch_installed?: boolean }
  whisper?: { transcriber_type?: string; model_size?: string; downloaded?: boolean }
}

export default function MonitorPage() {
  const toast = useToast()
  const [status, setStatus] = useState<DeployStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [diag, setDiag] = useState<Record<string, unknown> | null>(null)

  const load = useCallback(async () => {
    try {
      setStatus(await api.get<DeployStatus>('/deploy_status'))
      setDiag(await desktop.diagnostics())
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '获取状态失败')
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void load()
    const timer = window.setInterval(() => void load(), 8000)
    return () => window.clearInterval(timer)
  }, [load])

  if (loading) return <div className="page"><Spinner label="检测部署状态…" /></div>

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h2>部署监控</h2>
          <p>后端、FFmpeg、转写模型与 GPU 的实时可用状态。</p>
        </div>
        <div className="toolbar">
          <button type="button" className="btn ghost" onClick={() => void load()}>刷新</button>
          <button type="button" className="btn" onClick={() => void desktop.openDataDir()}>打开数据目录</button>
        </div>
      </header>

      <div className="monitor-grid">
        <section className="card monitor-card">
          <div className="monitor-title">后端服务</div>
          <div className="monitor-value"><StatusTag value={status?.backend?.status || 'UNKNOWN'} /></div>
          <div className="dim">端口 {status?.backend?.port || '—'}</div>
        </section>
        <section className="card monitor-card">
          <div className="monitor-title">FFmpeg</div>
          <div className="monitor-value">
            {status?.ffmpeg?.available
              ? <span className="status-tag ok">可用</span>
              : <span className="status-tag bad">不可用</span>}
          </div>
          <div className="dim">{status?.ffmpeg?.version || '截图与音频转换依赖 ffmpeg'}</div>
        </section>
        <section className="card monitor-card">
          <div className="monitor-title">CUDA / GPU</div>
          <div className="monitor-value">
            {status?.cuda?.available && status?.cuda?.inference_ready
              ? <span className="status-tag ok">{status.cuda.gpu_name || '可用'}</span>
              : status?.cuda?.available
                ? <span className="status-tag warn">缺少 CUDA 运行库（CPU 转写）</span>
                : <span className="status-tag dim">不可用（CPU 转写）</span>}
          </div>
          <div className="dim">
            {status?.cuda?.available && status?.cuda?.inference_ready
              ? `GPU 转写就绪 · 检测来源 ${status.cuda.detect_backend || '—'}`
              : status?.cuda?.available
                ? '检测到 GPU，但缺少 cuBLAS/cuDNN：把 CUDA 加速包放到程序目录 cuda\\ 下并重启即可启用 GPU 转写'
                : '未检测到 NVIDIA GPU，使用 CPU 转写'}
          </div>
        </section>
        <section className="card monitor-card">
          <div className="monitor-title">转写引擎</div>
          <div className="monitor-value">{status?.whisper?.transcriber_type || '—'}</div>
          <div className="dim">
            模型 {status?.whisper?.model_size || '—'}
            {status?.whisper?.downloaded ? '（已就绪）' : '（未下载，可在设置 → 音频转写中下载或导入）'}
          </div>
        </section>
      </div>

      {diag && (
        <section className="card">
          <div className="section-title">安装环境诊断</div>
          <table className="table">
            <tbody>
              <tr><td>程序路径</td><td className="dim">{String(diag.path ?? '—')}</td></tr>
              <tr><td>路径含非 ASCII 字符</td><td>{diag.path_has_non_ascii ? '是（建议更换安装路径）' : '否'}</td></tr>
              <tr><td>路径含空格</td><td>{diag.path_has_space ? '是' : '否'}</td></tr>
              <tr><td>安装目录可写</td><td>{diag.parent_writable ? '是' : '否（数据仍安全存放在数据目录）'}</td></tr>
            </tbody>
          </table>
        </section>
      )}
      {!diag && (
        <section className="card">
          <div className="section-title">安装环境诊断</div>
          <p className="hint">仅在桌面应用内提供安装路径诊断。</p>
        </section>
      )}
    </div>
  )
}
