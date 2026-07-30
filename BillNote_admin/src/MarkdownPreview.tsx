import { createElement, useEffect, useMemo, useRef, useState } from 'react'
import type { MouseEvent, ReactNode } from 'react'
import { Button, Empty, Image, Message, Space, Tag, Tooltip } from '@arco-design/web-react'
import {
  IconBook, IconCopy, IconDownload, IconLink, IconMenu, IconUp,
} from '@arco-design/web-react/icon'

type MarkdownBlock =
  | { type: 'heading'; level: number; text: string; id: string }
  | { type: 'paragraph'; text: string }
  | { type: 'image'; alt: string; src: string }
  | { type: 'code'; language: string; value: string }
  | { type: 'quote'; text: string }
  | { type: 'list'; ordered: boolean; items: Array<{ text: string; depth: number }> }
  | { type: 'table'; header: string[]; rows: string[][] }
  | { type: 'rule' }

type MarkdownDocument = {
  blocks: MarkdownBlock[]
  sourceUrl?: string
}

export type MarkdownPreviewProps = {
  content: string
  title?: string
  sourceUrl?: string
  className?: string
  onDownload?: () => void
}

const apiBaseUrl = String(import.meta.env.VITE_API_BASE_URL || '/api')

function resolveAssetUrl(src: string) {
  if (!src.startsWith('/') || !/^https?:\/\//i.test(apiBaseUrl)) return src
  try {
    return new URL(src, new URL(apiBaseUrl).origin).toString()
  } catch {
    return src
  }
}

function safeHref(href: string) {
  const value = href.trim()
  return /^(https?:\/\/|mailto:|#|\/)/i.test(value) ? value : '#'
}

function plainText(value: string) {
  return value
    .replace(/\*?Content-\[[^\]]+]\*?/gi, '')
    .replace(/!\[([^\]]*)]\([^)]+\)/g, '$1')
    .replace(/\[([^\]]+)]\([^)]+\)/g, '$1')
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
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim())
}

function isTableDivider(line: string) {
  const cells = splitTableRow(line)
  return cells.length > 0 && cells.every((cell) => /^:?-{3,}:?$/.test(cell))
}

function isBlockStart(lines: string[], index: number) {
  const line = lines[index] || ''
  if (!line.trim()) return true
  if (/^```/.test(line.trim())) return true
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
    if (!line.trim()) {
      index += 1
      continue
    }

    const sourceMatch = line.match(/^>\s*来源链接[：:]\s*(https?:\/\/\S+)/i)
    if (sourceMatch) {
      sourceUrl = sourceMatch[1]
      index += 1
      continue
    }

    const fence = line.trim().match(/^```([\w+-]*)\s*$/)
    if (fence) {
      const code: string[] = []
      index += 1
      while (index < lines.length && !/^```\s*$/.test(lines[index].trim())) {
        code.push(lines[index])
        index += 1
      }
      if (index < lines.length) index += 1
      blocks.push({ type: 'code', language: fence[1] || 'text', value: code.join('\n') })
      continue
    }

    const heading = line.match(/^(#{1,6})\s+(.+)$/)
    if (heading) {
      const text = heading[2].trim()
      blocks.push({
        type: 'heading',
        level: heading[1].length,
        text,
        id: slugify(text, usedSlugs),
      })
      index += 1
      continue
    }

    if (index + 1 < lines.length && line.includes('|') && isTableDivider(lines[index + 1])) {
      const header = splitTableRow(line)
      const rows: string[][] = []
      index += 2
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
        rows.push(splitTableRow(lines[index]))
        index += 1
      }
      blocks.push({ type: 'table', header, rows })
      continue
    }

    const image = line.trim().match(/^!\[([^\]]*)]\(([^)]+)\)\*?$/)
    if (image) {
      blocks.push({ type: 'image', alt: image[1], src: image[2] })
      index += 1
      continue
    }

    if (/^\s*(---+|\*\*\*+|___+)\s*$/.test(line)) {
      blocks.push({ type: 'rule' })
      index += 1
      continue
    }

    if (/^>\s?/.test(line)) {
      const quote: string[] = []
      while (index < lines.length && /^>\s?/.test(lines[index])) {
        quote.push(lines[index].replace(/^>\s?/, ''))
        index += 1
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
      paragraph.push(lines[index].trim())
      index += 1
    }
    blocks.push({ type: 'paragraph', text: paragraph.join(' ') })
  }

  return { blocks, sourceUrl }
}

function downloadMarkdown(content: string, title: string) {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `${title.replace(/[\\/:*?"<>|]/g, '-').trim() || 'note'}.md`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(link.href)
}

function renderInline(
  text: string,
  keyPrefix: string,
  onAnchorClick: (event: MouseEvent<HTMLAnchorElement>, href: string) => void,
): ReactNode[] {
  const tokenPattern = /(!\[[^\]]*]\([^)]+\)|\[[^\]]+]\([^)]+\)|`[^`]+`|\*\*[^*\n]+\*\*|~~[^~\n]+~~|\*Content-\[[^\]]+]\*|\*[^*\n]+\*)/g
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
        <Image
          key={key}
          className="markdown-inline-image"
          src={resolveAssetUrl(image[2])}
          alt={image[1] || '笔记图片'}
          lazyload
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
          {!href.startsWith('#') && <IconLink className="markdown-link-icon" />}
        </a>,
      )
    } else if (token.startsWith('`')) {
      nodes.push(<code key={key} className="markdown-inline-code">{token.slice(1, -1)}</code>)
    } else if (token.startsWith('**')) {
      nodes.push(<strong key={key}>{renderInline(token.slice(2, -2), `${key}-strong`, onAnchorClick)}</strong>)
    } else if (token.startsWith('~~')) {
      nodes.push(<del key={key}>{renderInline(token.slice(2, -2), `${key}-del`, onAnchorClick)}</del>)
    } else if (/^\*Content-\[/.test(token)) {
      nodes.push(<span key={key} className="markdown-time-chip">{token.replace(/^\*Content-\[|]\*$/g, '')}</span>)
    } else {
      nodes.push(<em key={key}>{renderInline(token.slice(1, -1), `${key}-em`, onAnchorClick)}</em>)
    }
    cursor = match.index + token.length
  }

  if (cursor < text.length) nodes.push(text.slice(cursor))
  return nodes
}

export default function MarkdownPreview({
  content,
  title = 'AI 视频笔记',
  sourceUrl,
  className = '',
  onDownload,
}: MarkdownPreviewProps) {
  const articleRef = useRef<HTMLElement>(null)
  const tocRef = useRef<HTMLElement>(null)
  const [tocVisible, setTocVisible] = useState(true)
  const [activeHeadingId, setActiveHeadingId] = useState('')
  const document = useMemo(() => parseMarkdown(content), [content])
  const effectiveSourceUrl = sourceUrl || document.sourceUrl
  const headings = useMemo(
    () => document.blocks.filter(
      (block): block is Extract<MarkdownBlock, { type: 'heading' }> =>
        block.type === 'heading' && block.level <= 3 && plainText(block.text) !== '目录',
    ),
    [document],
  )

  useEffect(() => {
    const article = articleRef.current
    if (!article || headings.length === 0) {
      setActiveHeadingId('')
      return
    }

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

        setActiveHeadingId((previous) => previous === currentId ? previous : currentId)
      })
    }

    article.addEventListener('scroll', updateActiveHeading, { passive: true })
    updateActiveHeading()
    return () => {
      article.removeEventListener('scroll', updateActiveHeading)
      cancelAnimationFrame(animationFrame)
    }
  }, [headings])

  useEffect(() => {
    const toc = tocRef.current
    const activeItem = activeHeadingId
      ? toc?.querySelector<HTMLElement>(`[data-heading-id="${CSS.escape(activeHeadingId)}"]`)
      : null
    if (!toc || !activeItem) return

    const tocRect = toc.getBoundingClientRect()
    const itemRect = activeItem.getBoundingClientRect()
    if (itemRect.top >= tocRect.top + 8 && itemRect.bottom <= tocRect.bottom - 8) return

    toc.scrollBy({
      top: itemRect.top - tocRect.top - (toc.clientHeight - itemRect.height) / 2,
      behavior: 'smooth',
    })
  }, [activeHeadingId])

  const scrollToHeading = (id: string) => {
    const target = articleRef.current?.querySelector<HTMLElement>(`#${CSS.escape(id)}`)
    setActiveHeadingId(id)
    target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const handleAnchorClick = (event: MouseEvent<HTMLAnchorElement>, href: string) => {
    if (!href.startsWith('#')) return
    event.preventDefault()
    const rawId = decodeURIComponent(href.slice(1))
    const exact = document.blocks.find(
      (block) => block.type === 'heading' && (block.id === rawId || plainText(block.text) === rawId),
    )
    if (exact?.type === 'heading') scrollToHeading(exact.id)
  }

  const copyMarkdown = async () => {
    try {
      await navigator.clipboard.writeText(content)
      Message.success('Markdown 已复制')
    } catch {
      Message.error('复制失败，请检查浏览器剪贴板权限')
    }
  }

  if (!content.trim()) {
    return <div className={`markdown-reader markdown-reader-empty ${className}`}><Empty description="暂无可预览内容" /></div>
  }

  return (
    <section className={`markdown-reader ${tocVisible ? '' : 'markdown-reader-toc-hidden'} ${className}`}>
      <header className="markdown-reader-toolbar">
        <div className="markdown-reader-toolbar-title">
          <span className="markdown-reader-toolbar-icon"><IconBook /></span>
          <div>
            <strong>专业阅读模式</strong>
            <span>结构化 Markdown · 图文沉浸阅读</span>
          </div>
        </div>
        <Space size={8} wrap>
          {headings.length > 0 && (
            <Tooltip content={tocVisible ? '收起目录' : '展开目录'}>
              <Button
                className="markdown-toc-toggle"
                aria-label={tocVisible ? '收起目录' : '展开目录'}
                icon={<IconMenu />}
                onClick={() => setTocVisible((visible) => !visible)}
              >
                目录
              </Button>
            </Tooltip>
          )}
          <Button icon={<IconCopy />} onClick={() => void copyMarkdown()}>复制 Markdown</Button>
          <Button
            type="primary"
            icon={<IconDownload />}
            onClick={() => onDownload ? onDownload() : downloadMarkdown(content, title)}
          >
            下载
          </Button>
        </Space>
      </header>

      <div className="markdown-reader-layout">
        {headings.length > 0 && (
          <aside ref={tocRef} className="markdown-reader-toc" aria-label="文档目录">
            <div className="markdown-reader-toc-label">CONTENTS</div>
            <nav>
              {headings.map((heading) => (
                <button
                  key={heading.id}
                  type="button"
                  className={`markdown-reader-toc-item level-${heading.level}${activeHeadingId === heading.id ? ' active' : ''}`}
                  onClick={() => scrollToHeading(heading.id)}
                  title={plainText(heading.text)}
                  data-heading-id={heading.id}
                  aria-current={activeHeadingId === heading.id ? 'location' : undefined}
                >
                  {plainText(heading.text)}
                </button>
              ))}
            </nav>
          </aside>
        )}

        <article ref={articleRef} className="markdown-article">
          <div className="markdown-document-header">
            <div className="markdown-document-eyebrow">
              <Tag color="arcoblue">AI NOTE</Tag>
              <span>适合直接阅读与归档</span>
            </div>
            <h1>{title}</h1>
            {effectiveSourceUrl && (
              <a className="markdown-source-link" href={safeHref(effectiveSourceUrl)} target="_blank" rel="noreferrer">
                <IconLink />
                查看原视频
              </a>
            )}
          </div>

          <div className="markdown-document-content">
            {document.blocks.map((block, blockIndex) => {
              const key = `block-${blockIndex}`
              if (block.type === 'heading') {
                const headingTag = `h${block.level}` as 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6'
                return createElement(
                  headingTag,
                  { id: block.id, key, className: 'markdown-heading' },
                  renderInline(block.text, key, handleAnchorClick),
                )
              }
              if (block.type === 'paragraph') {
                return <p key={key}>{renderInline(block.text, key, handleAnchorClick)}</p>
              }
              if (block.type === 'image') {
                return (
                  <figure key={key} className="markdown-figure">
                    <Image
                      src={resolveAssetUrl(block.src)}
                      alt={block.alt || '视频关键画面'}
                      width="100%"
                      lazyload
                      title={block.alt || undefined}
                    />
                    <figcaption>{block.alt || '视频关键画面'}</figcaption>
                  </figure>
                )
              }
              if (block.type === 'code') {
                return (
                  <div key={key} className="markdown-code-block">
                    <div className="markdown-code-header">
                      <span>{block.language.toUpperCase()}</span>
                      <Button
                        type="text"
                        size="mini"
                        icon={<IconCopy />}
                        onClick={() => void navigator.clipboard.writeText(block.value).then(
                          () => Message.success('代码已复制'),
                          () => Message.error('代码复制失败'),
                        )}
                      >
                        复制
                      </Button>
                    </div>
                    <pre><code>{block.value}</code></pre>
                  </div>
                )
              }
              if (block.type === 'quote') {
                return <blockquote key={key}>{renderInline(block.text, key, handleAnchorClick)}</blockquote>
              }
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
                  <div key={key} className="markdown-table-wrap">
                    <table>
                      <thead><tr>{block.header.map((cell, index) => <th key={`${key}-head-${index}`}>{renderInline(cell, `${key}-head-${index}`, handleAnchorClick)}</th>)}</tr></thead>
                      <tbody>
                        {block.rows.map((row, rowIndex) => (
                          <tr key={`${key}-row-${rowIndex}`}>
                            {block.header.map((_, cellIndex) => (
                              <td key={`${key}-row-${rowIndex}-${cellIndex}`}>
                                {renderInline(row[cellIndex] || '', `${key}-row-${rowIndex}-${cellIndex}`, handleAnchorClick)}
                              </td>
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

          <footer className="markdown-document-footer">
            <span>— 文档阅读结束 —</span>
            <Button type="text" icon={<IconUp />} onClick={() => articleRef.current?.scrollTo({ top: 0, behavior: 'smooth' })}>
              返回顶部
            </Button>
          </footer>
        </article>
      </div>
    </section>
  )
}
