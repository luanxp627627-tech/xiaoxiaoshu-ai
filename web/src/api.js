export async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options
  })
  if (!response.ok) {
    throw new Error('HTTP ' + response.status)
  }
  return response.json()
}

export async function uploadImage(file) {
  const form = new FormData()
  form.append('file', file)
  const response = await fetch('/api/files/images', { method: 'POST', body: form })
  if (!response.ok) {
    let detail = 'HTTP ' + response.status
    try {
      const body = await response.json()
      detail = body.message || body.detail || detail
    } catch (e) {
      // 非 JSON 错误响应
    }
    throw new Error(detail)
  }
  return response.json()
}

export async function streamChat(payload, onEvent) {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  if (!response.ok) {
    throw new Error('HTTP ' + response.status)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let split
    while ((split = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, split)
      buffer = buffer.slice(split + 2)
      const event = parseEvent(raw)
      if (event) onEvent(event.name, event.data)
    }
  }
}

function parseEvent(raw) {
  let name = 'message'
  const dataLines = []
  raw.split('\n').forEach((line) => {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  })
  if (!dataLines.length) return null
  try {
    return { name, data: JSON.parse(dataLines.join('\n')) }
  } catch (e) {
    return null
  }
}
