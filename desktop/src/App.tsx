import { useCallback, useEffect, useState } from 'react'
import { backendInfo, initBackend, onBackendEvent } from './ipc'
import { ConfirmProvider, ReaderProvider, ToastProvider } from './ui'
import SingleTaskPage from './pages/SingleTaskPage'
import BatchTaskPage from './pages/BatchTaskPage'
import SettingsPage from './pages/SettingsPage'
import MonitorPage from './pages/MonitorPage'

type PageKey = 'single' | 'batch' | 'settings' | 'monitor'

const navItems: { key: PageKey; label: string; icon: string }[] = [
  { key: 'single', label: '单视频任务', icon: '🎬' },
  { key: 'batch', label: '批量合集任务', icon: '🗂' },
  { key: 'settings', label: '设置', icon: '⚙️' },
  { key: 'monitor', label: '部署监控', icon: '📡' },
]

export default function App() {
  return (
    <ToastProvider>
      <ConfirmProvider>
        <ReaderProvider>
          <Shell />
        </ReaderProvider>
      </ConfirmProvider>
    </ToastProvider>
  )
}

function Shell() {
  const [booting, setBooting] = useState(true)
  const [bootMessage, setBootMessage] = useState('正在启动内置服务…')
  const [bootFailed, setBootFailed] = useState<string | null>(null)
  const [warning, setWarning] = useState<string | null>(null)
  const [page, setPage] = useState<PageKey>('single')
  const [restarting, setRestarting] = useState(false)

  const boot = useCallback(async () => {
    setBooting(true)
    setBootFailed(null)
    try {
      await initBackend()
      setBooting(false)
    } catch (error) {
      setBootFailed(error instanceof Error ? error.message : String(error))
      setBooting(false)
    }
  }, [])

  useEffect(() => { void boot() }, [boot])

  useEffect(() => onBackendEvent({
    ready: () => {
      setRestarting(false)
      if (bootFailed) { setBootFailed(null); void boot() }
    },
    failed: (message) => {
      setBootFailed(message)
      setRestarting(false)
    },
    warning: (payload) => {
      const data = payload as { missing_ffmpeg?: boolean; suggestions?: string[]; path_has_non_ascii?: boolean; path_has_space?: boolean; parent_writable?: boolean }
      if (data?.missing_ffmpeg) {
        setWarning(data.suggestions?.[0] || '未检测到 ffmpeg')
      } else if (data?.path_has_non_ascii || data?.path_has_space || data?.parent_writable === false) {
        setWarning('安装路径可能影响后端运行：' + (data.suggestions?.[0] || ''))
      }
    },
  }), [boot, bootFailed])

  const restarting_backend = restarting

  if (booting || restarting_backend) {
    return (
      <div className="boot-screen">
        <div className="boot-logo">BN</div>
        <div className="boot-title">BiliNote 工作台</div>
        <div className="boot-message">{restarting_backend ? '正在重启内置服务…' : bootMessage}</div>
        <div className="boot-bar"><span /></div>
        <p className="boot-hint">本地视频转写与笔记生成需要几秒钟初始化</p>
      </div>
    )
  }

  if (bootFailed) {
    return (
      <div className="boot-screen">
        <div className="boot-logo bad">!</div>
        <div className="boot-title">内置服务启动失败</div>
        <div className="boot-message">{bootFailed}</div>
        <div className="row center">
          <button type="button" className="btn primary" onClick={() => void boot()}>重试</button>
        </div>
      </div>
    )
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-logo">BN</span>
          <div>
            <div className="brand-name">BiliNote</div>
            <div className="brand-sub">AI 视频笔记工作站</div>
          </div>
        </div>
        <nav className="nav">
          {navItems.map((item) => (
            <button
              key={item.key}
              type="button"
              className={`nav-item ${page === item.key ? 'active' : ''}`}
              onClick={() => setPage(item.key)}
            >
              <span className="nav-icon">{item.icon}</span>
              {item.label}
            </button>
          ))}
        </nav>
        <footer className="sidebar-footer">
          <div className="dim small-text">服务端口 {backendInfo?.port}</div>
          <div className={`dot-line ${bootFailed ? 'bad' : 'ok'}`}>
            <span className="dot" />
            {bootFailed ? '服务异常' : '内置服务运行中'}
          </div>
        </footer>
      </aside>

      <main className="content">
        {warning && (
          <div className="banner warn">
            <span>{warning}</span>
            <button type="button" className="icon-btn" aria-label="关闭提示" onClick={() => setWarning(null)}>✕</button>
          </div>
        )}
        {page === 'single' && <SingleTaskPage />}
        {page === 'batch' && <BatchTaskPage />}
        {page === 'settings' && <SettingsPage />}
        {page === 'monitor' && <MonitorPage />}
      </main>
    </div>
  )
}
