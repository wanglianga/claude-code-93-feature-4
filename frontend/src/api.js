const TOKEN_KEY = 'ccb_qb_token'
const USER_KEY = 'ccb_qb_user'

export function getToken() { return localStorage.getItem(TOKEN_KEY) || '' }
export function saveAuth(token, user) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}
export function getUser() {
  try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null') } catch { return null }
}
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

async function request(method, path, body, extraHeaders) {
  const res = await fetch('/api' + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(getToken() ? { Authorization: 'Bearer ' + getToken() } : {}),
      ...(extraHeaders || {})
    },
    body: body !== undefined ? JSON.stringify(body) : undefined
  })
  let data = null
  const text = await res.text()
  if (text) { try { data = JSON.parse(text) } catch { data = text } }
  if (!res.ok) {
    const msg = (data && data.error) || ('请求失败 (' + res.status + ')')
    const err = new Error(msg)
    err.status = res.status
    throw err
  }
  return data
}

/** 生成幂等键：同一逻辑操作的重试/双击共用同一键，服务端保证只执行一次 */
export function newIdemKey() {
  return (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + '-' + Math.random().toString(36).slice(2))
}

export const api = {
  get: (p) => request('GET', p),
  post: (p, b) => request('POST', p, b ?? {}),
  /** 带幂等键的 POST（重复提交/双击/重试安全） */
  postIdem: (p, b, key) => request('POST', p, b ?? {}, key ? { 'Idempotency-Key': key } : {}),
  put: (p, b) => request('PUT', p, b ?? {})
}
