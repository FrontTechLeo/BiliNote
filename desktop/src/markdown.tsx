import katex from 'katex'
import 'katex/dist/katex.min.css'
import { createElement, useEffect, useMemo, useRef, useState } from 'react'
import type { MouseEvent, ReactNode } from 'react'
import { absoluteAssetUrl } from './ipc'

// 自包含的 Markdown 专业阅读器：块级解析 + KaTeX 公式 + 图片/表格/目录。
// 从 admin 的 MarkdownPreview 移植，去掉组件库依赖。

type MarkdownBlock =
  | { type: 'heading'; level: number; text: string; id: string }
  | { type: 'paragraph'; text: string }
  | { type: 'math'; value: string }
  | { type: 'image'; alt: string; src: string }
  | { type: 'code'; language: string; value: string }
  | { type: 'quote'; text: string }
  | { type: 'list'; ordered: boolean; items: Array<{ text: string; depth: number }> }
  | { type: 'table'; header: string[]; rows: string[][] }
  | { type: 'rule' }

type MarkdownDocument = { blocks: MarkdownBlock[]; sourceUrl?: string }

function safeHref(href: string) {
  const value = href.trim()
  return /^(https?:\/\/|mailto:|#|\/)/i.test(value) ? value : '#'
}

function plainText(value: string) {
  return value
    .replace(/\*?Content-\[[^\]]+]\*?/gi, '')
    .replace(/!\[([^\]]*)]\([^)]+\)/g, '$1')
    .replace(/\[([^\]]+)]\([^)]+\)/g, '$1')
    .replace(/\${1,2}([^$]+)\${1,2}/g, '$1')
    .replace(/[*_~`]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

function slugify(value: string, used: Map<string, number>) {
  const base = plainText(value)
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .trim()
    .replace(/\s+/g, '-')
    || 'section'
  const count = used.get(base) || 0
  used.set(base, count + 1)
  return count ? `${base}-${count + 1}` : base
}

function splitTableRow(line: string) {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => cell.trim())
}

function isTableDivider(line: string) {
  const cells = splitTableRow(line)
  return cells.length > 0 && cells.every((cell) => /^:?-{3,}:?$/.test(cell))
}

function isBlockStart(lines: string[], index: number) {
  const line = lines[index] || ''
  if (!line.trim()) return true
  if (/^```/.test(line.trim())) return true
  if (/^\s*(\$\$|\\\[)/.test(line)) return true
  if (/^#{1,6}\s+/.test(line)) return true
  if (/^\s*([-+*]|\d+\.)\s+/.test(line)) return true
  if (/^>\s?/.test(line)) return true
  if (/^\s*(---+|\*\*\*+|___+)\s*$/.test(line)) return true
  if (/^\s*!\[[^\]]*]\([^)]+\)\*?\s*$/.test(line)) return true
  return index + 1 < lines.length && line.includes('|') && isTableDivider(lines[index + 1])
}

function parseMarkdown(markdown: string): MarkdownDocument {
  const lines = (markdown || '').replace(/\r\n?/g, '\n').split('\n')
  const blocks: MarkdownBlock[] = []
  const usedSlugs = new Map<string, number>()
  let sourceUrl: string | undefined
  let index = 0

  while (index < lines.length) {
    const line = lines[index]
    if (!line.trim()) { index += 1; continue }

    const sourceMatch = line.match(/^>\s*来源链接[：:]\s*(https?:\/\/\S+)/i)
    if (sourceMatch) { sourceUrl = sourceMatch[1]; index += 1; continue }

    const fence = line.trim().match(/^```([\w+-]*)\s*$/)
    if (fence) {
      const code: string[] = []
      index += 1
      while (index < lines.length && !/^```\s*$/.test(lines[index].trim())) {
        code.push(lines[index]); index += 1
      }
      if (index < lines.length) index += 1
      blocks.push({ type: 'code', language: fence[1] || 'text', value: code.join('\n') })
      continue
    }

    const mathStart = line.trim()
    if (mathStart.startsWith('$$') || mathStart.startsWith('\\[')) {
      const open = mathStart.startsWith('$$') ? '$$' : '\\['
      const close = open === '$$' ? '$$' : '\\]'
      const formula: string[] = []
      let current = mathStart.slice(open.length)
      if (current.endsWith(close)) {
        formula.push(current.slice(0, -close.length)); index += 1
      } else {
        if (current) formula.push(current)
        index += 1
        while (index < lines.length) {
          current = lines[index].trim()
          if (current.endsWith(close)) {
            formula.push(current.slice(0, -close.length)); index += 1; break
          }
          formula.push(lines[index]); index += 1
        }
      }
      blocks.push({ type: 'math', value: formula.join('\n').trim() })
      continue
    }

    const heading = line.match(/^(#{1,6})\s+(.+)$/)
    if (heading) {
      const text = heading[2].trim()
      blocks.push({ type: 'heading', level: heading[1].length, text, id: slugify(text, usedSlugs) })
      index += 1
      continue
    }

    if (index + 1 < lines.length && line.includes('|') && isTableDivider(lines[index + 1])) {
      const header = splitTableRow(line)
      const rows: string[][] = []
      index += 2
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
        rows.push(splitTableRow(lines[index])); index += 1
      }
      blocks.push({ type: 'table', header, rows })
      continue
    }

    const image = line.trim().match(/^!\[([^\]]*)]\(([^)]+)\)\*?$/)
    if (image) { blocks.push({ type: 'image', alt: image[1], src: image[2] }); index += 1; continue }

    if (/^\s*(---+|\*\*\*+|___+)\s*$/.test(line)) { blocks.push({ type: 'rule' }); index += 1; continue }

    if (/^>\s?/.test(line)) {
      const quote: string[] = []
      while (index < lines.length && /^>\s?/.test(lines[index])) {
        quote.push(lines[index].replace(/^>\s?/, '')); index += 1
      }
      blocks.push({ type: 'quote', text: quote.join(' ') })
      continue
    }

    const listStart = line.match(/^(\s*)([-+*]|\d+\.)\s+(.+)$/)
    if (listStart) {
      const ordered = /\d+\./.test(listStart[2])
      const items: Array<{ text: string; depth: number }> = []
      while (index < lines.length) {
        const item = lines[index].match(/^(\s*)([-+*]|\d+\.)\s+(.+)$/)
        if (!item || /\d+\./.test(item[2]) !== ordered) break
        items.push({
          text: item[3].trim(),
          depth: Math.min(Math.floor(item[1].replace(/\t/g, '  ').length / 2), 3),
        })
        index += 1
      }
      blocks.push({ type: 'list', ordered, items })
      continue
    }

    const paragraph = [line.trim()]
    index += 1
    while (index < lines.length && lines[index].trim() && !isBlockStart(lines, index)) {
      paragraph.push(lines[index].trim()); index += 1
    }
    blocks.push({ type: 'paragraph', text: paragraph.join(' ') })
  }

  return { blocks, sourceUrl }
}

function renderMath(value: string, displayMode: boolean, key: string) {
  try {
    const html = katex.renderToString(value, { displayMode, output: 'htmlAndMathml', strict: 'ignore', throwOnError: true, trust: false })
    const TagName = displayMode ? 'div' : 'span'
    return (
      <TagName
        key={key}
        className={displayMode ? 'md-math-block' : 'md-math-inline'}
        dangerouslySetInnerHTML={{ __html: html }}
      />
    )
  } catch {
    const source = displayMode ? `$$${value}$$` : `$${value}$`
    return <code key={key} className="md-math-error" title="公式语法无法解析">{source}</code>
  }
}

function renderInline(
  text: string,
  keyPrefix: string,
  onAnchorClick: (event: MouseEvent<HTMLAnchorElement>, href: string) => void,
): ReactNode[] {
  const tokenPattern = /(!\[[^\]]*]\([^)]+\)|\[[^\]]+]\([^)]+\)|`[^`]+`|\$[^$\n]+\$|\*\*[^*\n]+\*\*|~~[^~\n]+~~|\*Content-\[[^\]]+]\*|\*[^*\n]+\*)/g
  const nodes: ReactNode[] = []
  let cursor = 0
  let match: RegExpExecArray | null
  let tokenIndex = 0

  while ((match = tokenPattern.exec(text)) !== null) {
    if (match.index > cursor) nodes.push(text.slice(cursor, match.index))
    const token = match[0]
    const key = `${keyPrefix}-${tokenIndex++}`
    const image = token.match(/^!\[([^\]]*)]\(([^)]+)\)$/)
    const link = token.match(/^\[([^\]]+)]\(([^)]+)\)$/)

    if (image) {
      nodes.push(
        <img
          key={key}
          className="md-inline-image"
          src={absoluteAssetUrl(image[2])}
          alt={image[1] || '笔记图片'}
          loading="lazy"
        />,
      )
    } else if (link) {
      const href = safeHref(link[2])
      nodes.push(
        <a
          key={key}
          href={href}
          target={href.startsWith('#') ? undefined : '_blank'}
          rel={href.startsWith('#') ? undefined : 'noreferrer'}
          onClick={(event) => onAnchorClick(event, href)}
        >
          {renderInline(link[1], `${key}-link`, onAnchorClick)}
        </a>,
      )
    } else if (token.startsWith('`')) {
      nodes.push(<code key={key} className="md-inline-code">{token.slice(1, -1)}</code>)
    } else if (token.startsWith('$')) {
      nodes.push(renderMath(token.slice(1, -1), false, key))
    } else if (token.startsWith('**')) {
      nodes.push(<strong key={key}>{renderInline(token.slice(2, -2), `${key}-strong`, onAnchorClick)}</strong>)
    } else if (token.startsWith('~~')) {
      nodes.push(<del key={key}>{renderInline(token.slice(2, -2), `${key}-del`, onAnchorClick)}</del>)
    } else if (/^\*Content-\[/.test(token)) {
      nodes.push(<span key={key} className="md-time-chip">{token.replace(/^\*Content-\[|]\*$/g, '')}</span>)
    } else {
      nodes.push(<em key={key}>{renderInline(token.slice(1, -1), `${key}-em`, onAnchorClick)}</em>)
    }
    cursor = match.index + token.length
  }

  if (cursor < text.length) nodes.push(text.slice(cursor))
  return nodes
}

export default function MarkdownReader({
  content,
  title = 'AI 视频笔记',
  sourceUrl,
  exporting = false,
  onDownload,
  onCopy,
}: {
  content: string
  title?: string
  sourceUrl?: string
  exporting?: boolean
  onDownload?: () => void
  onCopy?: () => void
}) {
  const articleRef = useRef<HTMLElement>(null)
  const tocRef = useRef<HTMLElement>(null)
  const [tocVisible, setTocVisible] = useState(true)
  const [activeHeadingId, setActiveHeadingId] = useState('')
  const document_ = useMemo(() => parseMarkdown(content), [content])
  const effectiveSourceUrl = sourceUrl || document_.sourceUrl
  const headings = useMemo(
    () => document_.blocks.filter(
      (block): block is Extract<MarkdownBlock, { type: 'heading' }> =>
        block.type === 'heading' && block.level <= 3 && plainText(block.text) !== '目录',
    ),
    [document_],
  )

  useEffect(() => {
    const article = articleRef.current
    if (!article || headings.length === 0) { setActiveHeadingId(''); return }
    let animationFrame = 0
    const updateActiveHeading = () => {
      cancelAnimationFrame(animationFrame)
      animationFrame = requestAnimationFrame(() => {
        const articleTop = article.getBoundingClientRect().top
        let currentId = headings[0].id
        for (const heading of headings) {
          const target = article.querySelector<HTMLElement>(`#${CSS.escape(heading.id)}`)
          if (!target || target.getBoundingClientRect().top - articleTop > 136) break
          currentId = heading.id
        }
        setActiveHeadingId((previous) => (previous === currentId ? previous : currentId))
      })
    }
    article.addEventListener('scroll', updateActiveHeading, { passive: true })
    updateActiveHeading()
    return () => {
      article.removeEventListener('scroll', updateActiveHeading)
      cancelAnimationFrame(animationFrame)
    }
  }, [headings])

  const scrollToHeading = (id: string) => {
    const target = articleRef.current?.querySelector<HTMLElement>(`#${CSS.escape(id)}`)
    setActiveHeadingId(id)
    target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const handleAnchorClick = (event: MouseEvent<HTMLAnchorElement>, href: string) => {
    if (!href.startsWith('#')) return
    event.preventDefault()
    const rawId = decodeURIComponent(href.slice(1))
    const exact = document_.blocks.find(
      (block) => block.type === 'heading' && (block.id === rawId || plainText(block.text) === rawId),
    )
    if (exact?.type === 'heading') scrollToHeading(exact.id)
  }

  if (!content.trim()) {
    return <div className="reader-empty">暂无可阅读的笔记内容</div>
  }

  return (
    <section className={`md-reader ${tocVisible ? '' : 'md-reader-toc-hidden'}`}>
      <header className="md-toolbar">
        <div className="md-toolbar-title">
          <strong>{title}</strong>
          <span>专业阅读模式 · 图文沉浸</span>
        </div>
        <div className="md-toolbar-actions">
          {headings.length > 0 && (
            <button type="button" className="btn ghost small" onClick={() => setTocVisible((v) => !v)}>
              {tocVisible ? '隐藏目录' : '显示目录'}
            </button>
          )}
          {onCopy && <button type="button" className="btn ghost small" onClick={onCopy}>复制 Markdown</button>}
          {onDownload && (
            <button type="button" className="btn primary small" disabled={exporting} onClick={onDownload}>
              {exporting ? '导出中…' : '导出'}
            </button>
          )}
        </div>
      </header>

      <div className="md-layout">
        {headings.length > 0 && (
          <aside ref={tocRef} className="md-toc" aria-label="文档目录">
            <div className="md-toc-label">目录</div>
            <nav>
              {headings.map((heading) => (
                <button
                  key={heading.id}
                  type="button"
                  className={`md-toc-item level-${heading.level}${activeHeadingId === heading.id ? ' active' : ''}`}
                  onClick={() => scrollToHeading(heading.id)}
                  title={plainText(heading.text)}
                >
                  {plainText(heading.text)}
                </button>
              ))}
            </nav>
          </aside>
        )}

        <article ref={articleRef} className="md-article">
          <div className="md-document-header">
            {effectiveSourceUrl && (
              <a className="md-source-link" href={safeHref(effectiveSourceUrl)} target="_blank" rel="noreferrer">
                查看原视频 ↗
              </a>
            )}
          </div>

          <div className="md-content">
            {document_.blocks.map((block, blockIndex) => {
              const key = `block-${blockIndex}`
              if (block.type === 'heading') {
                const headingTag = `h${block.level}` as 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6'
                return createElement(headingTag, { id: block.id, key, className: 'md-heading' }, renderInline(block.text, key, handleAnchorClick))
              }
              if (block.type === 'paragraph') return <p key={key}>{renderInline(block.text, key, handleAnchorClick)}</p>
              if (block.type === 'math') return renderMath(block.value, true, key)
              if (block.type === 'image') {
                return (
                  <figure key={key} className="md-figure">
                    <img src={absoluteAssetUrl(block.src)} alt={block.alt || '视频关键画面'} loading="lazy" title={block.alt || undefined} />
                    {block.alt && <figcaption>{block.alt}</figcaption>}
                  </figure>
                )
              }
              if (block.type === 'code') {
                return (
                  <div key={key} className="md-code-block">
                    <div className="md-code-header">
                      <span>{block.language.toUpperCase()}</span>
                      <button
                        type="button"
                        className="btn ghost mini"
                        onClick={() => void navigator.clipboard.writeText(block.value)}
                      >复制</button>
                    </div>
                    <pre><code>{block.value}</code></pre>
                  </div>
                )
              }
              if (block.type === 'quote') return <blockquote key={key}>{renderInline(block.text, key, handleAnchorClick)}</blockquote>
              if (block.type === 'list') {
                const List = block.ordered ? 'ol' : 'ul'
                return (
                  <List key={key}>
                    {block.items.map((item, itemIndex) => (
                      <li key={`${key}-${itemIndex}`} style={{ marginLeft: item.depth * 20 }}>
                        {renderInline(item.text, `${key}-${itemIndex}`, handleAnchorClick)}
                      </li>
                    ))}
                  </List>
                )
              }
              if (block.type === 'table') {
                return (
                  <div key={key} className="md-table-wrap">
                    <table>
                      <thead><tr>{block.header.map((cell, index) => <th key={`${key}-head-${index}`}>{renderInline(cell, `${key}-head-${index}`, handleAnchorClick)}</th>)}</tr></thead>
                      <tbody>
                        {block.rows.map((row, rowIndex) => (
                          <tr key={`${key}-row-${rowIndex}`}>
                            {block.header.map((_, cellIndex) => (
                              <td key={`${key}-row-${rowIndex}-${cellIndex}`}>{renderInline(row[cellIndex] || '', `${key}-row-${rowIndex}-${cellIndex}`, handleAnchorClick)}</td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )
              }
              return <hr key={key} />
            })}
          </div>

          <footer className="md-document-footer">
            <span>— 文档阅读结束 —</span>
            <button type="button" className="btn ghost small" onClick={() => articleRef.current?.scrollTo({ top: 0, behavior: 'smooth' })}>
              返回顶部
            </button>
          </footer>
        </article>
      </div>
    </section>
  )
}
