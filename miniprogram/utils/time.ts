export function formatTime(tsOrIso?: string | number | null) {
  if (tsOrIso == null) return ""
  let d: Date
  if (typeof tsOrIso === "number") {
    d = new Date(tsOrIso)
  } else {
    const t = Date.parse(tsOrIso)
    d = new Date(Number.isFinite(t) ? t : Date.now())
  }
  const pad = (n: number) => (n < 10 ? `0${n}` : String(n))
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

