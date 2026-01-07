type EnvVersion = "develop" | "trial" | "release"

function getEnvVersion(): EnvVersion {
  try {
    const info = wx.getAccountInfoSync?.()
    const v = info?.miniProgram?.envVersion as EnvVersion | undefined
    return v ?? "develop"
  } catch {
    return "develop"
  }
}

function getOverride(key: string): string | undefined {
  try {
    const v = wx.getStorageSync(key)
    return typeof v === "string" && v.trim() ? v.trim() : undefined
  } catch {
    return undefined
  }
}

const env = getEnvVersion()

const DEFAULT_HTTP_BASE: Record<EnvVersion, string> = {
  develop: "http://127.0.0.1:8080",
  trial: "http://127.0.0.1:8080",
  // release 环境请通过本地覆盖（im:httpBase / im:wsUrl）或改为你的线上域名
  release: "http://127.0.0.1:8080",
}

const DEFAULT_WS_URL: Record<EnvVersion, string> = {
  develop: "ws://127.0.0.1:9001/ws",
  trial: "ws://127.0.0.1:9001/ws",
  // release 环境请通过本地覆盖（im:httpBase / im:wsUrl）或改为你的线上域名
  release: "ws://127.0.0.1:9001/ws",
}

export const HTTP_BASE = getOverride("im:httpBase") ?? DEFAULT_HTTP_BASE[env]
export const WS_URL = getOverride("im:wsUrl") ?? DEFAULT_WS_URL[env]
