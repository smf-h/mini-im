const defaultHost = (() => {
  if (typeof window !== 'undefined' && window.location?.hostname) {
    return window.location.hostname
  }
  return '127.0.0.1'
})()

const defaultWsScheme =
  typeof window !== 'undefined' && window.location?.protocol === 'https:' ? 'wss' : 'ws'

export const HTTP_BASE =
  (import.meta.env.VITE_HTTP_BASE as string | undefined) ?? `http://${defaultHost}:8080`
export const WS_URL =
  (import.meta.env.VITE_WS_URL as string | undefined) ?? `${defaultWsScheme}://${defaultHost}:9001/ws`
