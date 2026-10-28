import { useEffect } from 'react'
import { useApp, useWorkspace } from '@/state/providers'
import { parseJsonPath, type JsonPathSegment } from '@/core/paths'
import { fieldPath, focusField } from './focus'

/**
 * Applies focus requests for [path] (from Problems) in a visual editor:
 * [route] selects whatever the JSON path is about (a slot, a button) and
 * returns the field path to focus, or null to stop. The field is looked for
 * in the inspector first, then in the editor itself (a recipe's slots), and
 * the Inspector panel shows (its tab, and its dock) if it didn't.
 */
export function useFocusRequests(
  path: string,
  route: (segments: JsonPathSegment[]) => JsonPathSegment[] | null,
) {
  const { layout } = useApp()
  const focus = useWorkspace((s) => (s.focus?.path === path ? s.focus : null))
  useEffect(() => {
    if (!focus?.jsonPath) return
    const target = route(parseJsonPath(focus.jsonPath))
    if (target === null) return
    layout.getState().reveal('inspector')
    // Wait for the inspector to show what was selected.
    const timer = setTimeout(
      () =>
        focusField(
          [document.querySelector('[data-inspector]'), document.querySelector('[data-editor]')],
          fieldPath(target),
        ),
      30,
    )
    return () => clearTimeout(timer)
    // `route` is a fresh closure each render; the request is what matters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focus, path])
}
