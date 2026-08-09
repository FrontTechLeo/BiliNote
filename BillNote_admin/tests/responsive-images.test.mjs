import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import test from 'node:test'
import { pathToFileURL } from 'node:url'

const chromeCandidates = process.platform === 'win32'
  ? [
      'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
      'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
    ]
  : ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser']

const chrome = chromeCandidates.find(existsSync)

test('reader images shrink to the article without stretching smaller images', { skip: !chrome }, () => {
  const projectRoot = resolve(import.meta.dirname, '..')
  const component = readFileSync(resolve(projectRoot, 'src/MarkdownPreview.tsx'), 'utf8')
  const forcesFullWidth = /className="markdown-figure"[\s\S]*?<Image[\s\S]*?width="100%"/.test(component)
  const fixtureDir = mkdtempSync(join(tmpdir(), 'bilinote-responsive-images-'))
  const fixturePath = join(fixtureDir, 'fixture.html')
  const stylesUrl = pathToFileURL(resolve(projectRoot, 'src/styles.css')).href
  const arcoImageStylesUrl = pathToFileURL(resolve(
    projectRoot,
    'node_modules/@arco-design/web-react/es/Image/style/index.css',
  )).href
  const widthMarkup = forcesFullWidth ? ' style="width:100%" width="100%"' : ''

  writeFileSync(fixturePath, `<!doctype html>
    <link rel="stylesheet" href="${arcoImageStylesUrl}">
    <link rel="stylesheet" href="${stylesUrl}">
    <style>.article-fixture { width: 600px; }</style>
    <div class="markdown-document-content article-fixture">
      <figure class="markdown-figure">
        <span id="large-wrap" class="arco-image"${widthMarkup}>
          <img id="large" class="arco-image-img"${widthMarkup} src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='1600' height='900'/%3E">
        </span>
      </figure>
      <figure class="markdown-figure">
        <span id="small-wrap" class="arco-image"${widthMarkup}>
          <img id="small" class="arco-image-img"${widthMarkup} src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='320' height='180'/%3E">
        </span>
      </figure>
    </div>
    <script>
      addEventListener('load', () => {
        const result = {
          article: document.querySelector('.article-fixture').getBoundingClientRect().width,
          large: document.querySelector('#large').getBoundingClientRect().width,
          largeWrap: document.querySelector('#large-wrap').getBoundingClientRect().width,
          small: document.querySelector('#small').getBoundingClientRect().width,
          smallWrap: document.querySelector('#small-wrap').getBoundingClientRect().width,
        }
        document.body.textContent = JSON.stringify(result)
      })
    </script>`, 'utf8')

  try {
    const output = execFileSync(chrome, [
      '--headless',
      '--no-sandbox',
      '--disable-gpu',
      '--allow-file-access-from-files',
      '--dump-dom',
      pathToFileURL(fixturePath).href,
    ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    const result = JSON.parse(output.match(/<body>(.*?)<\/body>/s)?.[1] ?? '{}')

    assert.ok(result.large <= result.article, 'large images must not overflow the article')
    assert.equal(result.small, 320, 'small images must keep their intrinsic width')
    assert.ok(result.smallWrap <= 322, 'the image wrapper must follow the intrinsic width')
  } finally {
    rmSync(fixtureDir, { recursive: true, force: true })
  }
})
