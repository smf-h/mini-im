export type Unsubscribe = () => void

export class Emitter<T> {
  private listeners: Array<(payload: T) => void> = []

  on(fn: (payload: T) => void): Unsubscribe {
    this.listeners.push(fn)
    return () => {
      this.listeners = this.listeners.filter((x) => x !== fn)
    }
  }

  emit(payload: T) {
    const list = this.listeners.slice()
    for (const fn of list) {
      try {
        fn(payload)
      } catch {
        // ignore
      }
    }
  }
}

