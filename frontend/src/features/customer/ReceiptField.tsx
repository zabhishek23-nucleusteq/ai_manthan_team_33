import { CameraOutlined, DeleteOutlined, PaperClipOutlined, UploadOutlined } from '@ant-design/icons'
import { Button, Flex, Progress, Typography, Upload } from 'antd'
import { useId, useRef, useState } from 'react'
import type { Receipt } from '../../api/types'
import { checkReceiptFile, RECEIPT_TYPES, uploadReceipt } from './claimsApi'

interface Props {
  value?: Receipt
  onChange?: (r: Receipt | undefined) => void
  /** e.g. "Line 1" - used in accessible names */
  lineLabel: string
}

const kb = (n: number) => (n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(n / 1024)} KB`)

/**
 * Receipt picker for one claim line: "Take photo" opens the phone camera (capture=environment),
 * "Upload file" uses Ant Upload. JPG/PNG/PDF up to 5 MB, uploaded immediately (POST /api/receipts).
 */
export function ReceiptField({ value, onChange, lineLabel }: Props) {
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [lastFile, setLastFile] = useState<File | null>(null)
  const cameraRef = useRef<HTMLInputElement>(null)
  const errorId = useId()

  const start = async (file: File) => {
    setLastFile(file)
    const problem = checkReceiptFile(file)
    if (problem) {
      setError(problem)
      return
    }
    setError(null)
    setUploading(true)
    try {
      onChange?.(await uploadReceipt(file))
    } catch (e) {
      const code = (e as { code?: string }).code
      setError(
        code === 'RECEIPT_TOO_LARGE'
          ? 'The file is larger than 5 MB.'
          : code === 'UNSUPPORTED_FILE_TYPE'
            ? 'Only JPG, PNG or PDF files can be used as a receipt.'
            : 'Upload failed. Check your connection and retry.',
      )
    } finally {
      setUploading(false)
    }
  }

  if (value) {
    return (
      <Flex align="center" gap={8} wrap>
        <PaperClipOutlined aria-hidden />
        <Typography.Text ellipsis style={{ maxWidth: 200 }}>
          {value.fileName}
        </Typography.Text>
        <Typography.Text type="secondary">{kb(value.size)}</Typography.Text>
        <Button
          type="text"
          danger
          icon={<DeleteOutlined />}
          aria-label={`Remove receipt from ${lineLabel}`}
          onClick={() => onChange?.(undefined)}
        />
      </Flex>
    )
  }

  return (
    <div>
      <Flex gap={8} wrap>
        <Button icon={<CameraOutlined />} onClick={() => cameraRef.current?.click()} disabled={uploading}>
          Take photo
        </Button>
        <input
          ref={cameraRef}
          type="file"
          accept="image/*"
          capture="environment"
          hidden
          aria-label={`Take a receipt photo for ${lineLabel}`}
          onChange={(e) => {
            const f = e.target.files?.[0]
            e.target.value = ''
            if (f) void start(f)
          }}
        />
        <Upload
          accept={RECEIPT_TYPES.join(',')}
          showUploadList={false}
          beforeUpload={(f) => {
            void start(f)
            return false
          }}
          disabled={uploading}
        >
          <Button icon={<UploadOutlined />} disabled={uploading} aria-describedby={error ? errorId : undefined}>
            Upload file
          </Button>
        </Upload>
      </Flex>
      {uploading ? <Progress percent={60} status="active" size="small" showInfo={false} aria-label="Uploading receipt" /> : null}
      <div aria-live="polite">
        {error ? (
          <Typography.Text type="danger" id={errorId}>
            {error}{' '}
            {lastFile && !checkReceiptFile(lastFile) ? (
              <Button type="link" size="small" onClick={() => void start(lastFile)} style={{ padding: 0 }}>
                Retry
              </Button>
            ) : null}
          </Typography.Text>
        ) : (
          <Typography.Text type="secondary">JPG, PNG or PDF, up to 5 MB.</Typography.Text>
        )}
      </div>
    </div>
  )
}
