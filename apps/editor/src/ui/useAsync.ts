import { useEffect, useState } from 'react'

/** The result of [load] for [key], or null until it settles (or when it fails). */
export function useAsync<T>(key: string, load: () => Promise<T> | null): T | null {
  return useAsyncResult(key, load)?.value ?? null
}

/** Like [useAsync], but says why it failed: null until it settles. */
export function useAsyncResult<T>(
  key: string,
  load: () => Promise<T> | null,
): { value: T | null; error: string | null } | null {
  const [state, setState] = useState<{
    key: string
    value: T | null
    error: string | null
  } | null>(null)
  useEffect(() => {
    let live = true
    load()?.then(
      (value) => live && setState({ key, value, error: null }),
      (error: unknown) =>
        live &&
        setState({
          key,
          value: null,
          error: error instanceof Error ? error.message : String(error),
        }),
    )
    return () => {
      live = false
    }
    // `load` is described by `key`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  return state?.key === key ? state : null
}
