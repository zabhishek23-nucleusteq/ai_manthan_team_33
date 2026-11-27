import { ZoomInOutlined, ZoomOutOutlined } from '@ant-design/icons'
import { Alert, Button, Space, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { isMockMode } from '../../api/client'
import type { Receipt } from '../../api/types'
import { getAccessToken } from '../auth/session'

/** Receipt preview (render with key={receipt.id} so state resets per receipt) (FR-16): fetches /api/receipts/{id}/content with the bearer token; image with zoom, PDF inline. */
export function ReceiptViewer({ receipt }: { receipt?: Receipt }) {
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [zoom, setZoom] = useState(1)

  useEffect(() => {
    if (!receipt || isMockMode()) return
    let objectUrl: string | null = null
    let cancelled = false
    const token = getAccessToken()
    fetch(`/api/receipts/${receipt.id}/content`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
      .then(async (r) => {
        if (!r.ok) throw new Error(r.status === 404 ? 'Receipt not found.' : `Could not load the receipt (${r.status}).`)
        objectUrl = URL.createObjectURL(await r.blob())
        if (!cancelled) setUrl(objectUrl)
      })
      .catch((e: Error) => !cancelled && setError(e.message || 'Could not load the receipt.'))
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [receipt])

  if (!receipt) return <Alert type="info" showIcon message="This line has no receipt." />
  const isPdf = receipt.contentType === 'application/pdf'

  return (
    <section aria-label={`Receipt ${receipt.fileName}`}>
      <Space style={{ marginBottom: 8 }} wrap>
        <Typography.Text strong>{receipt.fileName}</Typography.Text>
        {!isPdf ? (
          <>
            <Button size="small" icon={<ZoomOutOutlined />} aria-label="Zoom out" onClick={() => setZoom((z) => Math.max(0.5, z - 0.25))} />
            <span aria-live="polite">{Math.round(zoom * 100)}%</span>
            <Button size="small" icon={<ZoomInOutlined />} aria-label="Zoom in" onClick={() => setZoom((z) => Math.min(3, z + 0.25))} />
          </>
        ) : null}
      </Space>
      {isMockMode() ? (
        <Alert type="info" showIcon message="Receipt preview is available with the real backend." />
      ) : error ? (
        <Alert type="error" showIcon role="alert" message={error} />
      ) : !url ? (
        <div role="status">Loading receipt…</div>
      ) : isPdf ? (
        <object data={url} type="application/pdf" aria-label={receipt.fileName} style={{ width: '100%', height: 560 }}>
          <a href={url} target="_blank" rel="noreferrer">
            Open the PDF
          </a>
        </object>
      ) : (
        <div style={{ overflow: 'auto', maxHeight: 560, border: '1px solid rgba(0,0,0,0.1)' }}>
          <img src={url} alt={`Receipt ${receipt.fileName}`} style={{ width: `${zoom * 100}%`, display: 'block' }} />
        </div>
      )}
    </section>
  )
}
