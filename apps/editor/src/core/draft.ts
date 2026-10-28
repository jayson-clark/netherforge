/**
 * The one way an editor sets an optional key on a draft (`workspace.edit`
 * hands recipes an Immer draft). A file leaves out what isn't set, and the
 * canonical writer keeps whatever is written, so a cleared field deletes its
 * key rather than writing a default or an empty string.
 */

/** Sets [key] on [target], or deletes it for `undefined` or an empty string. */
export function setKey<T extends object, K extends keyof T>(
  target: T,
  key: K,
  value: T[K] | undefined,
): void {
  if (value === undefined || value === '') delete target[key]
  else target[key] = value
}
