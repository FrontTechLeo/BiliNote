import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import test from 'node:test'

const projectRoot = resolve(import.meta.dirname, '..')
const readSource = (file) => readFileSync(resolve(projectRoot, 'src', file), 'utf8')

test('image compression options expose the three supported levels and high is the default', () => {
  const types = readSource('types.ts')
  const mobileUtils = readSource('mobile/utils.ts')
  const mobileApp = readSource('MobileApp.tsx')

  assert.match(types, /ImageCompression = 'high' \| 'medium' \| 'original'/)
  assert.match(types, /高压缩（JPEG\/720p）/)
  assert.match(types, /中等（JPEG）/)
  assert.match(types, /最高\/原图（PNG）/)
  assert.match(types, /return 'high'/)
  assert.match(mobileUtils, /image_compression: 'high'/)
  assert.match(mobileApp, /imageCompression: 'high'/)
})

test('desktop and mobile requests carry image_compression', () => {
  const desktop = readSource('App.tsx')
  const mobile = readSource('MobileApp.tsx')
  const mobileBatch = readSource('mobile/MobileBatchWorkspace.tsx')

  assert.match(desktop, /image_compression: normaliseImageCompression\(values\.image_compression\)/)
  assert.match(desktop, /image_compression: 'high'/)
  assert.match(mobile, /image_compression: draft\.imageCompression/)
  assert.match(mobileBatch, /configForApi\(config\)/)
  assert.match(readSource('mobile/utils.ts'), /image_compression: normaliseImageCompression\(config\?\.image_compression\)/)
})
