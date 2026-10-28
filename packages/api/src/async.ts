/**
 * Asynchronous functions (`async: true`): what a spec author writes, and the
 * form every consumer sees (the stubs, the docs, `api.json`, the bindings).
 *
 * The author says what the function takes and the one value it gives once
 * its work is done; `asyncFunction` adds the rest the same way for every one:
 * a last, optional `callback` called with `value, err`, and the two returns
 * `value, err` a task gets when it calls without one ("Async failure" in
 * PLAN.md's decisions).
 */
import type { Fn, LuaType } from './types.ts'

/** An asynchronous function as the spec writes it: [value] in place of `returns`. */
export interface AsyncFn extends Omit<Fn, 'returns' | 'async' | 'impl'> {
  /**
   * What it gives once the work is done: its name in the callback (`world`), its type (a
   * single type, not a union or optional: on failure it's `nil` anyway), and a doc phrase.
   */
  value: { name: string; type: LuaType; doc: string }
  /**
   * Why its value arrives later, as the sentence starts it: `It works off the main thread` (the
   * default), or `It takes as many ticks as the walk does`.
   */
  later?: string
}

/** Why an asynchronous function's `err` is set, in every one's docs. */
const ERR_DOC = 'Why it failed, when it did; `nil` when it worked.'

/** An asynchronous function in the form every consumer reads: callback last, returns `value, err`. */
export function asyncFunction(fn: AsyncFn): Fn {
  const { value, later = 'It works off the main thread', ...rest } = fn
  if (/[|?]/.test(value.type))
    throw new Error(
      `${fn.name}: an async function's value is one type, neither optional nor a union`,
    )
  if (fn.params.some((it) => it.name === 'callback'))
    throw new Error(`${fn.name}: an async function gets its callback from asyncFunction`)
  const name = value.name
  return {
    ...rest,
    doc:
      `${fn.doc} ${later}, so its ${name} arrives later: give \`callback\`, ` +
      `which is called with \`${name}, err\` once it's done, or call it inside a task (\`nf.task\`) ` +
      `without one, and it waits and returns \`${name}, err\`. Without a callback outside a task, ` +
      `it's an error. When it fails, \`${name}\` is \`nil\` and \`err\` says why.`,
    params: [
      ...fn.params,
      {
        name: 'callback',
        type: `fun(${name}: ${value.type}?, err: string?)`,
        doc: `Called on the main thread once it's done, as this script's code, with ${value.doc} (or \`nil\` and why not). Not called if the script has stopped by then.`,
        optional: true,
      },
    ],
    returns: [
      {
        type: `${value.type}?`,
        doc: `Only without a callback, in a task: ${value.doc}, or \`nil\`.`,
      },
      { type: 'string?', doc: ERR_DOC },
    ],
    async: true,
  }
}

/**
 * The value type an asynchronous function gives (`World` for `World?, string?`), after
 * checking it has the form [asyncFunction] gives it.
 */
export function asyncValue(fn: Fn, where: string): LuaType {
  const callback = fn.params[fn.params.length - 1]
  const [value, err] = fn.returns
  if (
    !callback ||
    callback.name !== 'callback' ||
    !callback.optional ||
    !callback.type.startsWith('fun(') ||
    fn.returns.length !== 2 ||
    !value!.type.endsWith('?') ||
    err!.type !== 'string?'
  )
    throw new Error(`${where}: an async function is declared with asyncFunction`)
  return value!.type.slice(0, -1)
}
