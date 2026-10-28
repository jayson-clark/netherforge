/**
 * Fuzzy matching for quick open: the query's characters in order anywhere in
 * the text, scored so that what people mean comes first (a match at the start
 * of a word or the file name, consecutive characters, a shorter text).
 */
export interface FuzzyMatch {
  score: number
  /** Indices in the text that matched, for highlighting. */
  positions: number[]
}

const isBoundary = (text: string, index: number) =>
  index === 0 || '/._- '.includes(text[index - 1]!)

/** The best match of [query] in [text], or null when its characters aren't all there in order. */
export function fuzzyMatch(query: string, text: string): FuzzyMatch | null {
  const needle = query.toLowerCase().replace(/\s+/g, '')
  if (needle.length === 0) return { score: 0, positions: [] }
  const haystack = text.toLowerCase()
  const nameStart = text.lastIndexOf('/') + 1

  // Best score for needle[i..] matched with needle[i] at text index j, memoised.
  const memo = new Map<number, { score: number; next: number } | null>()
  const best = (
    i: number,
    from: number,
    previous: number,
  ): { score: number; path: number[] } | null => {
    if (i === needle.length) return { score: 0, path: [] }
    let result: { score: number; path: number[] } | null = null
    for (let j = from; j < haystack.length; j += 1) {
      if (haystack[j] !== needle[i]) continue
      const key = i * 10_000 + j
      let tail = memo.get(key)
      if (tail === undefined) {
        const rest = best(i + 1, j + 1, j)
        tail = rest ? { score: rest.score, next: j } : null
        memo.set(key, tail)
        if (rest) restPaths.set(key, rest.path)
      }
      if (!tail) continue
      let score = 1
      if (j === previous + 1) score += 5
      if (isBoundary(text, j)) score += 4
      if (j >= nameStart) score += 2
      if (j === nameStart) score += 3
      const total = score + tail.score
      if (!result || total > result.score)
        result = { score: total, path: [j, ...restPaths.get(key)!] }
    }
    return result
  }
  const restPaths = new Map<number, number[]>()
  const found = best(0, 0, -2)
  if (!found) return null
  // Shorter texts win ties.
  return { score: found.score * 100 - text.length, positions: found.path }
}
