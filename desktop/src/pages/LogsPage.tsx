import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, formatBytes } from '../api'
import { desktop } from '../ipc'
import { EmptyState, Spinner, useToast } from '../ui'

type TailPayload = { path: string; size: number; lines: string[]; mode?: 'backfill' | 'increment' }
type LevelMode = 'all' | 'warn' | 'error'

// 首次进入回填的行数；同时是单次响应最多返回的行数（后端同样有上限）
const BACKFILL_LINES = 800
// 前端最多保留多少行，防止长时间挂机内存无限增长
const MAX_RENDER_LINES = 6000
const POLL_INTERVAL_MS = 1000

function lineLevel(line: string): string {
  const match = /\[(DEBUG|INFO|WARNING|ERROR|CRITICAL)\]/.exec(line)
  return match ? match[1] : ''
}

function levelVisible(level: string, mode: LevelMode): boolean {
  if (mode === 'all') return true
  if (mode === 'warn') return ['WARNING', 'ERROR', 'CRITICAL'].includes(level)
  return ['ERROR', 'CRITICAL'].includes(level)
}

function levelClass(line: string): string {
  const level = lineLevel(line)
  if (level === 'ERROR' || level === 'CRITICAL') return 'lv-error'
  if (level === 'WARNING') return 'lv-warning'
  if (level === 'DEBUG') return 'lv-debug'
  return ''
}

export default function LogsPage() {
  const toast = useToast()
  const [lines, setLines] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [paused, setPaused] = useState(false)
  const [pollFailed, setPollFailed] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [levelMode, setLevelMode] = useState<LevelMode>('all')
  const [follow, setFollow] = useState(true)
  const [meta, setMeta] = useState<{ path: string; size: number; updatedAt?: Date }>({ path: '', size: 0 })

  // 轮询用的游标与并发保护放 ref，避免作为 state 引发额外的重渲染
  const sizeRef = useRef(0)
  const busyRef = useRef(false)
  const viewerRef = useRef<HTMLDivElement>(null)

  const applyResult = useCallback((payload: TailPayload) => {
    sizeRef.current = payload.size || 0
    setMeta({ path: payload.path || '', size: payload.size || 0, updatedAt: new Date() })
    setPollFailed(false)
    setLoading(false)
    // 后端被清空/轮转，或单次增量过大时返回 backfill：整体替换视图
    if (payload.mode === 'backfill') {
      setLines(payload.lines || [])
      return
    }
    if (!payload.lines?.length) return
    setLines((current) => {
      const next = [...current, ...payload.lines]
      return next.length > MAX_RENDER_LINES ? next.slice(next.length - MAX_RENDER_LINES) : next
    })
  }, [])

  const poll = useCallback(async () => {
    if (busyRef.current) return
    busyRef.current = true
    try {
      const payload = await api.get<TailPayload>(`/logs/tail?lines=${BACKFILL_LINES}&after_bytes=${sizeRef.current}`)
      applyResult(payload)
    } catch {
      setPollFailed(true)
      setLoading(false)
    } finally {
      busyRef.current = false
    }
  }, [applyResult])

  useEffect(() => {
    if (paused) return
    void poll()
    const timer = window.setInterval(() => void poll(), POLL_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [paused, poll])

  // 跟随滚动到底部；用户向上翻页时自动暂停跟随
  useEffect(() => {
    if (!follow) return
    const node = viewerRef.current
    if (node) node.scrollTop = node.scrollHeight
  }, [lines, keyword, levelMode, follow])

  const handleScroll = () => {
    const node = viewerRef.current
    if (!node) return
    const atBottom = node.scrollHeight - node.scrollTop - node.clientHeight < 24
    setFollow(atBottom)
  }

  const visible = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    return lines.filter((line) => {
      if (!levelVisible(lineLevel(line), levelMode)) return false
      return !kw || line.toLowerCase().includes(kw)
    })
  }, [lines, keyword, levelMode])

  const openLogDir = async () => {
    if (!meta.path) return
    const directory = meta.path.replace(/[\\/][^\\/]+$/, '')
    if (!directory) return
    try {
      await desktop.openPath(directory)
    } catch (error) {
      toast('bad', error instanceof Error ? error.message : '打开日志目录失败')
    }
  }

  const status = pollFailed
    ? { cls: 'bad', text: '请求失败，将自动重试' }
    : paused
      ? { cls: 'warn', text: '已暂停' }
      : { cls: 'ok', text: '实时刷新中' }

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h2>服务日志</h2>
          <p>内置后端服务的完整运行日志，实时滚动；排查任务卡住时可切到「错误及以上」查看阻塞点。</p>
        </div>
        <div className="toolbar">
          <span className={`dot-line ${status.cls}`}><span className="dot" />{status.text}</span>
          <button type="button" className="btn" onClick={() => setPaused((value) => !value)}>
            {paused ? '继续刷新' : '暂停刷新'}
          </button>
          <button type="button" className="btn" onClick={() => void poll()}>拉取一次</button>
          <button type="button" className="btn"
            onClick={() => { void navigator.clipboard.writeText(visible.join('\n')); toast('ok', '已复制当前显示的日志') }}>
            复制
          </button>
          <button type="button" className="btn" onClick={() => setLines([])}>清空显示</button>
          <button type="button" className="btn" disabled={!meta.path} onClick={() => void openLogDir()}>打开日志目录</button>
        </div>
      </header>

      <section className="card logs-toolbar">
        <div className="level-chips" role="group" aria-label="日志等级过滤">
          {([
            ['all', '全部'],
            ['warn', '警告及以上'],
            ['error', '错误及以上'],
          ] as [LevelMode, string][]).map(([value, label]) => (
            <button key={value} type="button"
              className={`level-chip ${levelMode === value ? 'active' : ''}`}
              onClick={() => setLevelMode(value)}>
              {label}
            </button>
          ))}
        </div>
        <input className="input" value={keyword} placeholder="按关键字过滤行…"
          onChange={(event) => setKeyword(event.target.value)} />
        {!follow && (
          <button type="button" className="btn" onClick={() => setFollow(true)}>↓ 回到底部</button>
        )}
      </section>

      {loading ? <div className="page"><Spinner label="读取日志…" /></div> : (
        <section className="log-viewer" ref={viewerRef} onScroll={handleScroll}>
          {visible.length === 0
            ? <EmptyState title="暂无匹配的日志行" hint="调整关键字或等级过滤，或等待新日志写入" />
            : visible.map((line, index) => (
              <div key={index} className={`log-line ${levelClass(line)}`}>{line || ' '}</div>
            ))}
        </section>
      )}

      <footer className="logs-meta dim">
        <span className="ellipsis" title={meta.path}>{meta.path || '日志文件未知'}</span>
        <span>文件 {formatBytes(meta.size)}</span>
        <span>显示 {visible.length} / {lines.length} 行</span>
        {meta.updatedAt && <span>更新于 {meta.updatedAt.toLocaleTimeString()}</span>}
      </footer>
    </div>
  )
}
