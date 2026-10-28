/**
 * The console's lines: a ring of fixed capacity, so a chatty server costs
 * O(1) per line rather than an array copied per line. The run store mutates
 * one buffer and bumps `consoleVersion`; readers take a snapshot with
 * `lines()`, which is cached until the next change.
 */
export interface Line {
  /** Increasing: newer lines have larger ids. */
  id: number
}

export class ConsoleBuffer<T extends Line> {
  private readonly slots: (T | undefined)[]
  private start = 0
  private count = 0
  private snapshot: T[] | null = null

  constructor(readonly capacity: number) {
    this.slots = new Array<T | undefined>(capacity)
  }

  get size(): number {
    return this.count
  }

  /** The newest line. */
  get last(): T | undefined {
    return this.count === 0 ? undefined : this.slots[(this.start + this.count - 1) % this.capacity]
  }

  /** Adds [line], dropping the oldest when full. */
  push(line: T) {
    if (this.count < this.capacity) {
      this.slots[(this.start + this.count) % this.capacity] = line
      this.count += 1
    } else {
      this.slots[this.start] = line
      this.start = (this.start + 1) % this.capacity
    }
    this.snapshot = null
  }

  clear() {
    this.slots.fill(undefined)
    this.start = 0
    this.count = 0
    this.snapshot = null
  }

  /** Every line, oldest first. The same array until the next change; don't mutate it. */
  lines(): readonly T[] {
    if (!this.snapshot) {
      const lines: T[] = []
      for (let i = 0; i < this.count; i += 1)
        lines.push(this.slots[(this.start + i) % this.capacity]!)
      this.snapshot = lines
    }
    return this.snapshot
  }

  /** The lines newer than [id], oldest first. */
  since(id: number): T[] {
    const lines = this.lines()
    // Ids increase, so the first newer one is found by bisection.
    let lo = 0
    let hi = lines.length
    while (lo < hi) {
      const mid = (lo + hi) >> 1
      if (lines[mid]!.id > id) hi = mid
      else lo = mid + 1
    }
    return lines.slice(lo)
  }
}
