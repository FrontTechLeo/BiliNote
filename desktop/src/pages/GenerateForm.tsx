import { useCallback, useEffect, useState } from 'react'
import { api, formats, imageCompressionOptions, styles } from '../api'
import type { Model, Provider } from '../api'
import { Checkbox, Field } from '../ui'

export function normaliseOptions(values: Record<string, unknown>) {
  const rawGrid = values.grid_size
  const grid = typeof rawGrid === 'string'
    ? String(rawGrid).split(',').map((part) => Number(part.trim())).filter(Boolean)
    : rawGrid
  return {
    ...values,
    grid_size: Array.isArray(grid) && grid.length === 2 ? grid : [2, 2],
  }
}

export function useProviders() {
  const [providers, setProviders] = useState<Provider[]>([])
  const [models, setModels] = useState<Model[]>([])
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      const [providerRows, modelRows] = await Promise.all([
        api.get<Provider[]>('/get_all_providers'),
        api.get<Model[]>('/model_list'),
      ])
      setProviders(providerRows || [])
      setModels(modelRows || [])
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载模型配置失败')
    }
  }, [])

  useEffect(() => { void refresh() }, [refresh])
  return { providers, models, refresh, error }
}

export function ModelFields({ providerId, modelName, onChange, providers, models }: {
  providerId: number | string | ''
  modelName: string
  onChange: (patch: { providerId?: number | string | ''; modelName?: string }) => void
  providers: Provider[]
  models: Model[]
}) {
  const modelOptions = models.filter((model) => String(model.provider_id) === String(providerId))
  return (
    <>
      <Field label="模型供应商" required>
        <select
          className="input"
          value={providerId}
          onChange={(event) => onChange({ providerId: event.target.value || '', modelName: '' })}
        >
          <option value="">选择供应商</option>
          {providers.map((provider) => (
            <option key={provider.id} value={provider.id}>{provider.name}</option>
          ))}
        </select>
      </Field>
      <Field label="AI 模型" required>
        <select
          className="input"
          value={modelName}
          disabled={!providerId}
          onChange={(event) => onChange({ modelName: event.target.value })}
        >
          <option value="">{providerId ? '选择模型' : '先选择供应商'}</option>
          {modelOptions.map((model) => (
            <option key={model.id} value={model.model_name}>{model.model_name}</option>
          ))}
        </select>
      </Field>
    </>
  )
}

export function NoteOptions({ values, onChange }: {
  values: {
    quality: string
    style: string
    format: string[]
    video_understanding: boolean
    image_compression: string
    video_interval: number
    grid_size: string
    extras: string
  }
  onChange: (patch: Partial<typeof values>) => void
}) {
  const selected = new Set(values.format)
  const toggleFormat = (value: string, next: boolean) => {
    const set = new Set(values.format)
    if (next) set.add(value)
    else set.delete(value)
    onChange({ format: formats.filter((item) => set.has(item.value)).map((item) => item.value) })
  }
  return (
    <>
      <Field label="下载质量">
        <select className="input" value={values.quality} onChange={(event) => onChange({ quality: event.target.value })}>
          {['fast', 'medium', 'slow'].map((item) => <option key={item} value={item}>{item}</option>)}
        </select>
      </Field>
      <Field label="笔记风格">
        <select className="input" value={values.style} onChange={(event) => onChange({ style: event.target.value })}>
          {styles.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
      </Field>
      <Field label="笔记格式">
        <div className="check-group">
          {formats.map((item) => {
            const disabled = item.value === 'screenshot' && !values.video_understanding
            return (
              <Checkbox
                key={item.value}
                checked={selected.has(item.value)}
                disabled={disabled}
                onChange={(next) => toggleFormat(item.value, next)}
              >
                {item.label}
              </Checkbox>
            )
          })}
        </div>
      </Field>
      <Field label="视频理解" hint="开启后截取画面辅助理解，速度变慢">
        <button
          type="button"
          className={`switch ${values.video_understanding ? 'on' : ''}`}
          onClick={() => {
            const next = !values.video_understanding
            const patch: Partial<typeof values> = { video_understanding: next }
            if (!next && selected.has('screenshot')) {
              patch.format = values.format.filter((item) => item !== 'screenshot')
            }
            onChange(patch)
          }}
        >
          <span className="switch-thumb" />
        </button>
      </Field>
      {values.video_understanding && (
        <>
          <Field label="画面压缩">
            <select className="input" value={values.image_compression} onChange={(event) => onChange({ image_compression: event.target.value })}>
              {imageCompressionOptions.map((item) => <option key={item} value={item}>{item}</option>)}
            </select>
          </Field>
          <Field label="抽帧间隔（秒）">
            <input className="input" type="number" min={1} value={values.video_interval}
              onChange={(event) => onChange({ video_interval: Number(event.target.value) || 6 })} />
          </Field>
          <Field label="拼图网格" hint="例如 2,2">
            <input className="input" value={values.grid_size} placeholder="2,2"
              onChange={(event) => onChange({ grid_size: event.target.value })} />
          </Field>
        </>
      )}
      <Field label="备注（可选）">
        <textarea className="input" rows={3} value={values.extras} placeholder="追加给 AI 的笔记要求"
          onChange={(event) => onChange({ extras: event.target.value })} />
      </Field>
    </>
  )
}

export const SINGLE_DEFAULTS = {
  quality: 'medium',
  style: 'minimal',
  format: [] as string[],
  video_understanding: false,
  image_compression: 'high',
  video_interval: 6,
  grid_size: '2,2',
  extras: '',
}

export const BATCH_DEFAULTS = {
  ...SINGLE_DEFAULTS,
  style: 'tutorial',
  format: ['toc', 'summary'],
}
