function randBase36(len: number) {
  let out = ""
  while (out.length < len) {
    out += Math.random().toString(36).slice(2)
  }
  return out.slice(0, len)
}

export function genClientMsgId(prefix = "c") {
  return `${prefix}_${Date.now()}_${randBase36(8)}`
}

