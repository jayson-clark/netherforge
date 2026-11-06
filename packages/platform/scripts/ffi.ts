/**
 * The FFM glue between the Kotlin adapter and the Rust runtime, both halves, from the spec.
 *
 * Every call across is one entry point each way: an object's id, a method's number, and its
 * arguments in a flat buffer; the answer comes back the same way. The wire (see the
 * rust-runtime skill, "The FFM boundary"):
 *
 *  - numbers little-endian at their width, `Boolean` a byte, a `String` its UTF-8 bytes after
 *    their count (u32), a `UUID` its two halves (most significant first);
 *  - `T?` a byte (0 none, 1 some) then the value; a list, set, array or map its count (u32) then
 *    its items (a map's key then value); a pair or triple its parts;
 *  - a record its fields in order, an enum its entry's number (u32), a union its case's number
 *    (u32) then that case's fields, an object (`CommandSender`) its fields then its reference;
 *  - `Result<T>` a byte (0 ok then the value, 1 failed then the error), an error its message and
 *    maybe its cause's text, `Any` a tagged JSON-like value;
 *  - an interface or a function a reference: whose it is (0 the JVM's, 1 Rust's, a byte) and its
 *    id (u64). Each side keeps what it handed out by id until the other side lets go;
 *  - format's types as the file text each side reads them as (canonical JSON), but `Vec3`,
 *    `Box` and `Matrix4`, which are their numbers.
 *
 * Which side implements an interface follows from the spec: the `Platform` is the JVM's and
 * `PlatformEvents` Rust's; what one side's method returns is that side's, what it takes is the
 * other's ([sides]).
 */
import {
  EXTERNAL,
  type Decl,
  type EnumDecl,
  type InterfaceDecl,
  type Param,
  type PlatformFile,
  type RecordDecl,
  type UnionDecl,
} from '../src/index.ts'
import { parseType, type TypeNode } from '../src/type.ts'
import { GENERATED_BY, HAND_WRITTEN, RUST_EXTERNAL, rustType, snake, type Context } from './rust.ts'

export type Side = 'java' | 'rust'

/** Where each external type lives in Kotlin. */
const KOTLIN_EXTERNAL: Record<string, string> = {
  ArgumentType: 'dev.netherforge.plugin.platform.ArgumentType',
  GameEvents: 'dev.netherforge.plugin.platform.GameEvents',
  WatchedEvent: 'dev.netherforge.plugin.platform.WatchedEvent',
  'dev.netherforge.plugin.datapack.DatapackRefusal':
    'dev.netherforge.plugin.datapack.DatapackRefusal',
  BlockCarrier: 'dev.netherforge.format.block.BlockCarrier',
  Vec3: 'dev.netherforge.format.math.Vec3',
  Matrix4: 'dev.netherforge.format.math.Matrix4',
  Box: 'dev.netherforge.format.game.Box',
  GameData: 'dev.netherforge.format.game.GameData',
  GameDataBundle: 'dev.netherforge.format.game.GameDataBundle',
  GameIds: 'dev.netherforge.format.game.GameIds',
  ItemDef: 'dev.netherforge.format.item.ItemDef',
  AttributeOperation: 'dev.netherforge.format.item.AttributeOperation',
  TerrainScripts: 'dev.netherforge.format.terrain.TerrainScripts',
  StructureTemplate: 'dev.netherforge.format.terrain.StructureTemplate',
  CompiledTerrain: 'dev.netherforge.format.terrain.CompiledTerrain',
  ScreenFacing: 'dev.netherforge.format.screen.ScreenFacing',
  RecipeFile: 'dev.netherforge.format.recipe.RecipeFile',
  SpawnCategory: 'dev.netherforge.format.project.SpawnCategory',
  SpawnData: 'dev.netherforge.format.particle.SpawnData',
  MenuType: 'dev.netherforge.format.menu.MenuType',
  DialogFile: 'dev.netherforge.format.dialog.DialogFile',
  GameDatapack: 'dev.netherforge.format.datapack.GameDatapack',
  DisplayDef: 'dev.netherforge.format.centity.DisplayDef',
  Billboard: 'dev.netherforge.format.centity.Billboard',
  BotState: 'dev.netherforge.format.bridge.BotState',
  BotPosition: 'dev.netherforge.format.bridge.BotPosition',
  BotPackAnswer: 'dev.netherforge.format.bridge.BotPackAnswer',
  BotInfo: 'dev.netherforge.format.bridge.BotInfo',
  BotEvents: 'dev.netherforge.format.bridge.BotEvents',
  BotAction: 'dev.netherforge.format.bridge.BotAction',
  BotActResult: 'dev.netherforge.format.bridge.BotActResult',
  Path: 'java.nio.file.Path',
}

/** How an external crosses: its numbers (hand-written on both sides), an enum of the Lua API's by entry, or its file text. */
const EXTERNAL_WIRE: Record<string, 'numbers' | 'entry' | 'placeholder' | 'interface'> = {
  Vec3: 'numbers',
  Box: 'numbers',
  Matrix4: 'numbers',
  Path: 'numbers',
  ArgumentType: 'entry',
  WatchedEvent: 'entry',
  GameData: 'interface',
  GameEvents: 'interface',
}

/** How [name] (an external) crosses: `json` unless said otherwise; a placeholder when Rust has no type for it yet. */
function externalWire(name: string): 'numbers' | 'entry' | 'placeholder' | 'interface' | 'json' {
  if (EXTERNAL_WIRE[name]) return EXTERNAL_WIRE[name]!
  if (!(name in RUST_EXTERNAL)) return 'placeholder'
  return 'json'
}

/** One member of an interface as it crosses: its number, both languages' names, its types. */
export interface Member {
  id: number
  /** The Kotlin name (a property's or a method's). */
  name: string
  /** The Rust trait method's name (overloads renamed as rust.ts does). */
  rust: string
  property: boolean
  params: { name: string; type: TypeNode; text: string }[]
  returns: TypeNode
  returnsText: string
  /** The interface that declares it. */
  owner: string
}

export interface Model {
  all: Decl[]
  interfaces: Map<string, InterfaceDecl>
  records: Map<string, RecordDecl>
  enums: Map<string, EnumDecl>
  unions: Map<string, UnionDecl>
  sides: Map<string, Side>
  cx: Context
}

/** Every declared or external name in [node] that's an interface (a spec one, or `GameData`/`GameEvents`). */
function interfaceNames(node: TypeNode, m: { interfaces: Map<string, InterfaceDecl> }): string[] {
  switch (node.kind) {
    case 'named':
      return [
        ...(m.interfaces.has(node.name) || EXTERNAL_WIRE[node.name] === 'interface'
          ? [node.name]
          : []),
        ...node.args.flatMap((a) => interfaceNames(a, m)),
      ]
    case 'optional':
      return interfaceNames(node.inner, m)
    case 'function':
      return []
    case 'star':
      return []
  }
}

/** Whose each interface is: the Platform the JVM's, PlatformEvents Rust's, and what follows from them. */
function sides(interfaces: Map<string, InterfaceDecl>): Map<string, Side> {
  const side = new Map<string, Side>([
    ['Platform', 'java'],
    ['PlatformEvents', 'rust'],
    ['GameData', 'java'],
    ['GameEvents', 'rust'],
  ])
  const m = { interfaces }
  let changed = true
  while (changed) {
    changed = false
    for (const [name, d] of interfaces) {
      const own = side.get(name)
      if (!own) continue
      const other: Side = own === 'java' ? 'rust' : 'java'
      const set = (x: string, s: Side, why: string) => {
        const was = side.get(x)
        if (was && was !== s) throw new Error(`${x} is both sides' (${why})`)
        if (!was) {
          side.set(x, s)
          changed = true
        }
      }
      if (d.extends) set(d.extends, own, `${name} extends it`)
      for (const p of [...d.properties, ...(d.fields ?? [])])
        for (const x of interfaceNames(parseType(p.type), m)) set(x, own, `${name}.${p.name}`)
      for (const method of d.methods) {
        for (const x of interfaceNames(parseType(method.returns), m))
          set(x, own, `${name}.${method.name}`)
        for (const p of method.params)
          for (const x of interfaceNames(parseType(p.type), m))
            set(x, other, `${name}.${method.name}(${p.name})`)
      }
    }
  }
  for (const name of interfaces.keys())
    if (!side.has(name)) throw new Error(`${name}: no side implements it`)
  return side
}

export function model(spec: PlatformFile[]): Model {
  const all = spec.flatMap((f) => f.decls)
  const interfaces = new Map<string, InterfaceDecl>()
  const records = new Map<string, RecordDecl>()
  const enums = new Map<string, EnumDecl>()
  const unions = new Map<string, UnionDecl>()
  for (const d of all) {
    if (d.kind === 'interface' || d.kind === 'object') interfaces.set(d.name, d)
    else if (d.kind === 'record') records.set(d.name, d)
    else if (d.kind === 'enum') enums.set(d.name, d)
    else if (d.kind === 'union') unions.set(d.name, d)
  }
  const cx: Context = { interfaces: new Set(interfaces.keys()), unions: new Set(unions.keys()) }
  return { all, interfaces, records, enums, unions, sides: sides(interfaces), cx }
}

/**
 * [d]'s members as they cross, its ancestors' first (so an interface's numbers are its parent's
 * and then its own). An object's fields cross by value, so only its methods are numbered.
 */
export function membersOf(d: InterfaceDecl, m: Model): Member[] {
  const parent = d.extends ? membersOf(m.interfaces.get(d.extends)!, m) : []
  const out = [...parent]
  let id = parent.length
  if (d.kind === 'interface')
    for (const p of d.properties)
      out.push({
        id: id++,
        name: p.name,
        rust: snake(p.name),
        property: true,
        params: [],
        returns: parseType(p.type),
        returnsText: p.type,
        owner: d.name,
      })
  const seen = new Set<string>()
  for (const method of d.methods) {
    let rust = snake(method.name)
    if (seen.has(rust))
      rust = `${rust}_with_${snake(method.params[method.params.length - 1]!.name).replace(/^r#/, '')}`
    seen.add(rust)
    out.push({
      id: id++,
      name: method.name,
      rust,
      property: false,
      params: method.params.map((p) => ({ name: p.name, type: parseType(p.type), text: p.type })),
      returns: parseType(method.returns),
      returnsText: method.returns,
      owner: d.name,
    })
  }
  return out
}

/** Every function type in the spec, with the side that implements it (the other side of the method that takes it, or the side of what returns it). */
function functionTypes(
  m: Model,
): Map<string, { node: TypeNode & { kind: 'function' }; side: Side }> {
  const out = new Map<string, { node: TypeNode & { kind: 'function' }; side: Side }>()
  const walk = (node: TypeNode, side: Side) => {
    if (node.kind === 'function') {
      const key = rustType(node, m.cx, 'in')
      const was = out.get(key)
      if (was && was.side !== side) throw new Error(`${key} is both sides'`)
      out.set(key, { node, side })
      return
    }
    if (node.kind === 'optional') walk(node.inner, side)
    if (node.kind === 'named') node.args.forEach((a) => walk(a, side))
  }
  for (const [name, d] of m.interfaces) {
    const own = m.sides.get(name)!
    const other: Side = own === 'java' ? 'rust' : 'java'
    for (const member of membersOf(d, m)) {
      walk(member.returns, own)
      for (const p of member.params) walk(p.type, other)
    }
  }
  return out
}

// ---------------------------------------------------------------------------------------------
// Rust

const rustOf = (node: TypeNode, m: Model, position: 'in' | 'out' | 'field') =>
  rustType(node, m.cx, position)

/** The Rust value of a record's or union case's fields, read in order: `Name { a: Take::take(r), ... }`. */
function rustTakeFields(path: string, fields: Param[], m: Model): string {
  if (!fields.length) return `${path} {}`
  return `${path} { ${fields
    .map((f) => `${snake(f.name)}: <${rustOf(parseType(f.type), m, 'field')} as Take>::take(r)`)
    .join(', ')} }`
}

function rustCodecs(m: Model, fns: ReturnType<typeof functionTypes>): string[] {
  const lines: string[] = []
  for (const d of m.records.values()) {
    if (HAND_WRITTEN[d.name]) continue
    lines.push(
      `impl Put for ${d.name} {`,
      `    fn put(self, w: &mut Writer) {`,
      ...d.fields.map((f) => `        self.${snake(f.name)}.put(w);`),
      ...(d.fields.length ? [] : ['        let _ = w;']),
      '    }',
      '}',
      '',
      `impl Take for ${d.name} {`,
      `    fn take(r: &mut Reader) -> Self {`,
      ...(d.fields.length ? [] : ['        let _ = r;']),
      `        ${rustTakeFields(d.name, d.fields, m)}`,
      '    }',
      '}',
      '',
    )
  }
  for (const d of m.enums.values()) {
    lines.push(
      `impl Put for ${d.name} {`,
      '    fn put(self, w: &mut Writer) {',
      '        w.u32(self as u32);',
      '    }',
      '}',
      '',
      `impl Take for ${d.name} {`,
      '    fn take(r: &mut Reader) -> Self {',
      `        r.entry(${d.name}::ALL, "${d.name}")`,
      '    }',
      '}',
      '',
    )
  }
  for (const d of m.unions.values()) {
    lines.push(
      `impl Put for ${d.name} {`,
      '    fn put(self, w: &mut Writer) {',
      '        match self {',
      ...d.cases.map(
        (c, k) =>
          `            ${d.name}::${c.name}(${c.fields.length ? 'case' : '_'}) => { w.u32(${k}); ${c.fields.map((f) => `case.${snake(f.name)}.put(w);`).join(' ')} }`,
      ),
      '        }',
      '    }',
      '}',
      '',
      `impl Take for ${d.name} {`,
      '    fn take(r: &mut Reader) -> Self {',
      '        match r.u32() {',
      ...d.cases.map(
        (c, k) =>
          `            ${k} => ${d.name}::${c.name}(${rustTakeFields(`${d.name}${c.name}`, c.fields, m)}),`,
      ),
      `            other => panic!("${d.name} has no case {other}"),`,
      '        }',
      '    }',
      '}',
      '',
    )
  }
  for (const [name] of Object.entries(EXTERNAL)) {
    const wire = externalWire(name)
    const short = name.split('.').pop()!
    if (wire === 'json') {
      const path = RUST_EXTERNAL[name]!.path
      lines.push(
        `impl Put for ${path} {`,
        '    fn put(self, w: &mut Writer) {',
        '        w.json(&self);',
        '    }',
        '}',
        '',
        `impl Take for ${path} {`,
        '    fn take(r: &mut Reader) -> Self {',
        `        r.json("${short}")`,
        '    }',
        '}',
        '',
      )
    } else if (wire === 'entry') {
      const path = RUST_EXTERNAL[name]!.path
      lines.push(
        `impl Put for ${path} {`,
        '    fn put(self, w: &mut Writer) {',
        `        w.u32(${path}::ALL.iter().position(|e| *e == self).unwrap() as u32);`,
        '    }',
        '}',
        '',
        `impl Take for ${path} {`,
        '    fn take(r: &mut Reader) -> Self {',
        `        r.entry(${path}::ALL, "${short}")`,
        '    }',
        '}',
        '',
      )
    } else if (wire === 'placeholder') {
      lines.push(
        `impl Put for external::${short} {`,
        '    fn put(self, w: &mut Writer) {',
        '        self.0.put(w);',
        '    }',
        '}',
        '',
        `impl Take for external::${short} {`,
        '    fn take(r: &mut Reader) -> Self {',
        `        external::${short}(Dynamic::take(r))`,
        '    }',
        '}',
        '',
      )
    }
  }
  // Interfaces: the JVM's are taken (a proxy), Rust's are put (handed out by id).
  for (const [name, d] of m.interfaces) {
    if (m.sides.get(name) === 'java') {
      const proxy = `Java${name}`
      if (d.kind === 'object') {
        lines.push(
          `impl Take for Rc<dyn ${name}> {`,
          '    fn take(r: &mut Reader) -> Self {',
          ...(d.fields ?? []).map(
            (f) =>
              `        let ${snake(f.name)} = <${rustOf(parseType(f.type), m, 'field')} as Take>::take(r);`,
          ),
          `        Rc::new(${proxy} { ${(d.fields ?? []).map((f) => `${snake(f.name)}, `).join('')}obj: JavaRef::take(r) })`,
          '    }',
          '}',
          '',
        )
        continue
      }
      for (const holder of ['Rc', 'Box'])
        lines.push(
          `impl Take for ${holder}<dyn ${name}> {`,
          '    fn take(r: &mut Reader) -> Self {',
          `        ${holder}::new(${proxy}::new(JavaRef::take(r)))`,
          '    }',
          '}',
          '',
        )
    } else {
      lines.push(
        `impl Put for Rc<dyn ${name}> {`,
        '    fn put(self, w: &mut Writer) {',
        `        w.export(Rc::new(move |method, r: &mut Reader, w: &mut Writer| dispatch_${snake(name).replace(/^r#/, '')}(&self, method, r, w)));`,
        '    }',
        '}',
        '',
      )
    }
  }
  // Functions: Rust's are handed out by id, the JVM's called through a proxy.
  for (const [key, { node, side }] of fns) {
    const params = node.params.map((p, k) => ({ name: `a${k}`, type: rustOf(p.type, m, 'in') }))
    const returns = rustOf(node.returns, m, 'out')
    if (side === 'rust') {
      lines.push(
        `impl Put for ${key} {`,
        '    fn put(self, w: &mut Writer) {',
        `        w.export(Rc::new(move |_, r: &mut Reader, w: &mut Writer| {`,
        ...params.map((p) => `            let ${p.name} = <${p.type} as Take>::take(r);`),
        `            (self)(${params.map((p) => p.name).join(', ')}).put(w);`,
        '        }));',
        '    }',
        '}',
        '',
      )
    } else {
      lines.push(
        `impl Take for ${key} {`,
        '    fn take(r: &mut Reader) -> Self {',
        '        let obj = JavaRef::take(r);',
        `        Box::new(move |${params.map((p) => `${p.name}: ${p.type}`).join(', ')}| obj.call::<${returns}>("a function", 0, move |w| { ${params.map((p) => `${p.name}.put(w);`).join(' ')} let _ = w; }))`,
        '    }',
        '}',
        '',
      )
    }
  }
  return lines
}

/** Whether [node] is `&dyn X` or `Option<&dyn X>` in a return position: a thing the platform keeps. */
function keptInterface(node: TypeNode, m: Model): { name: string; optional: boolean } | undefined {
  const inner = node.kind === 'optional' ? node.inner : node
  if (inner.kind !== 'named' || inner.args.length) return undefined
  if (!m.interfaces.has(inner.name) && EXTERNAL_WIRE[inner.name] !== 'interface') return undefined
  return { name: inner.name, optional: node.kind === 'optional' }
}

/** A JVM interface as Rust: a proxy calling through its reference, a method a call each. */
function rustProxy(d: InterfaceDecl, m: Model): string[] {
  const name = `Java${d.name}`
  const all = membersOf(d, m)
  const keeps = all.some((x) => !x.params.length && keptInterface(x.returns, m))
  const lines: string[] = []
  if (d.kind === 'object') {
    lines.push(
      `/// A \`${d.name}\` the server made: its values, and its methods through \`obj\`.`,
      `pub struct ${name} {`,
      ...(d.fields ?? []).map(
        (f) => `    pub ${snake(f.name)}: ${rustOf(parseType(f.type), m, 'field')},`,
      ),
      '    pub obj: JavaRef,',
      '}',
      '',
    )
  } else {
    lines.push(
      `/// The server's \`${d.name}\`, called through its reference.`,
      `pub struct ${name} {`,
      '    pub obj: JavaRef,',
      ...(keeps ? ['    kept: Kept,'] : []),
      '}',
      '',
      `impl ${name} {`,
      `    pub fn new(obj: JavaRef) -> ${name} {`,
      `        ${name} { obj${keeps ? ', kept: Kept::default()' : ''} }`,
      '    }',
      '}',
      '',
    )
  }
  // One impl per trait in its chain.
  const chain: InterfaceDecl[] = []
  for (
    let at: InterfaceDecl | undefined = d;
    at;
    at = at.extends ? m.interfaces.get(at.extends) : undefined
  )
    chain.unshift(at)
  for (const trait of chain) {
    lines.push(`impl ${trait.name} for ${name} {`)
    if (trait.kind === 'object')
      for (const f of trait.fields ?? [])
        lines.push(
          `    fn ${snake(f.name)}(&self) -> ${rustOf(parseType(f.type), m, 'out')} {`,
          `        self.${snake(f.name)}.clone()`,
          '    }',
          '',
        )
    for (const x of all.filter((x) => x.owner === trait.name)) {
      const params = x.params.map((p) => ({ name: snake(p.name), type: rustOf(p.type, m, 'in') }))
      let returns = rustOf(x.returns, m, 'out')
      if (params.length) returns = returns.replace(/&dyn ([\w:]+)/, 'Box<dyn $1>')
      const signature = `fn ${x.rust}(&self${params.map((p) => `, ${p.name}: ${p.type}`).join('')})${returns === '()' ? '' : ` -> ${returns}`}`
      const what = `"${trait.name}::${x.rust.replace(/^r#/, '')}"`
      const args = `move |w| { ${params.map((p) => `${p.name}.put(w);`).join(' ')} let _ = w; }`
      const kept = !params.length ? keptInterface(x.returns, m) : undefined
      lines.push(`    ${signature} {`)
      if (kept?.name === 'GameData') {
        // The game's facts are read once, as the bundle the server exports.
        lines.push(
          `        self.kept.game(|| self.obj.call::<netherforge_format::game::GameDataBundle>(${what}, ${all.find((y) => y.name === 'exportGameData')!.id}, |_| {}))`,
        )
      } else if (kept && x.property && !kept.optional) {
        lines.push(
          `        self.kept.once::<Java${kept.name}>(${x.id}, || Java${kept.name}::new(self.obj.call::<JavaRef>(${what}, ${x.id}, |_| {})))`,
        )
      } else if (kept) {
        const call = `self.obj.call::<${kept.optional ? 'Option<JavaRef>' : 'JavaRef'}>(${what}, ${x.id}, |_| {})`
        lines.push(
          kept.optional
            ? `        ${call}.map(|obj| self.kept.by_id(obj, Java${kept.name}::new) as &dyn ${kept.name})`
            : `        self.kept.by_id(${call}, Java${kept.name}::new)`,
        )
      } else if (x.returns.kind === 'named' && x.returns.name === 'Result') {
        lines.push(`        self.obj.try_call(${what}, ${x.id}, ${args})`)
      } else {
        lines.push(`        self.obj.call(${what}, ${x.id}, ${args})`)
      }
      lines.push('    }', '')
    }
    if (lines[lines.length - 1] === '') lines.pop()
    lines.push('}', '')
  }
  return lines
}

/** A Rust interface's dispatcher: what the JVM's call by number runs. */
function rustDispatcher(d: InterfaceDecl, m: Model): string[] {
  const fn = `dispatch_${snake(d.name).replace(/^r#/, '')}`
  const lines = [
    `/// Runs \`${d.name}\`'s method number \`method\` on \`o\`, its arguments read from \`r\` and its answer written to \`w\`.`,
    `pub fn ${fn}(o: &Rc<dyn ${d.name}>, method: u32, r: &mut Reader, w: &mut Writer) {`,
    '    let _ = (&r, &w);',
    '    match method {',
  ]
  for (const x of membersOf(d, m)) {
    if (keptInterface(x.returns, m)) {
      const kept = keptInterface(x.returns, m)!
      if (kept.name === 'GameEvents') {
        lines.push(`        ${x.id} => w.export(game_events_dispatch(o.clone())),`)
        continue
      }
      throw new Error(
        `${d.name}.${x.name}: a Rust interface can't hand out a borrowed ${kept.name}`,
      )
    }
    const takes = x.params.map(
      (p, k) => `let a${k} = <${rustOf(p.type, m, 'in')} as Take>::take(r);`,
    )
    lines.push(
      `        ${x.id} => { ${takes.join(' ')} o.${x.rust}(${x.params.map((_, k) => `a${k}`).join(', ')}).put(w); }`,
    )
  }
  lines.push(`        other => panic!("${d.name} has no method {other}"),`, '    }', '}', '')
  return lines
}

export function ffiRust(spec: PlatformFile[]): Map<string, string> {
  const m = model(spec)
  const fns = functionTypes(m)
  const head = (what: string) => [
    `// ${GENERATED_BY}`,
    '',
    `//! ${what}`,
    '',
    '#![allow(clippy::all, unused_imports, unused_variables, unused_mut)]',
    '',
    'use std::rc::Rc;',
    'use std::collections::HashMap;',
    '',
    'use netherforge_platform::*;',
    'use netherforge_platform::api::*;',
    '',
    'use crate::objects::{JavaRef, Kept};',
    'use crate::wire::{Put, Reader, Take, Writer};',
    'use super::*;',
    '',
  ]
  const out = new Map<string, string>()
  const text = (lines: string[]) =>
    `${lines
      .join('\n')
      .replaceAll('crate::api::', 'netherforge_platform::api::')
      .replace(/\n{3,}/g, '\n\n')
      .trimEnd()}\n`
  out.set(
    'codecs.rs',
    text([...head("Every spec type's wire codec: `Put` and `Take`."), ...rustCodecs(m, fns)]),
  )
  out.set(
    'proxies.rs',
    text([
      ...head("The server's interfaces as Rust: each a proxy calling through its reference."),
      ...[...m.interfaces.values()]
        .filter((d) => m.sides.get(d.name) === 'java')
        .flatMap((d) => rustProxy(d, m)),
    ]),
  )
  out.set(
    'dispatch.rs',
    text([
      ...head("Rust's interfaces as the JVM calls them: a dispatcher each."),
      ...[...m.interfaces.values()]
        .filter((d) => m.sides.get(d.name) === 'rust')
        .flatMap((d) => rustDispatcher(d, m)),
    ]),
  )
  out.set(
    'mod.rs',
    [
      `// ${GENERATED_BY}`,
      '',
      "//! The FFM glue, Rust half: codecs, the server's interfaces as proxies, and dispatchers for Rust's.",
      '',
      'mod codecs;',
      'mod dispatch;',
      'mod proxies;',
      '',
      'pub use dispatch::*;',
      'pub use proxies::*;',
      '',
      'pub use crate::game_events::game_events_dispatch;',
      '',
    ].join('\n'),
  )
  return out
}

// ---------------------------------------------------------------------------------------------
// Kotlin

const KOTLIN_KEYWORDS = new Set([
  'object',
  'fun',
  'val',
  'var',
  'in',
  'is',
  'as',
  'when',
  'typealias',
  'interface',
])

const kname = (name: string) => (KOTLIN_KEYWORDS.has(name) ? `\`${name}\`` : name)

/** The Kotlin spelling of [node]: the spec's, externals by their full names. */
function kotlinType(node: TypeNode): string {
  switch (node.kind) {
    case 'star':
      return '*'
    case 'optional':
      return `${kotlinType(node.inner)}?`
    case 'function':
      return `(${node.params.map((p) => `${p.name ? `${p.name}: ` : ''}${kotlinType(p.type)}`).join(', ')}) -> ${kotlinType(node.returns)}`
    case 'named': {
      const name = KOTLIN_EXTERNAL[node.name] ?? node.name
      return node.args.length ? `${name}<${node.args.map(kotlinType).join(', ')}>` : name
    }
  }
}

const PRIMITIVE_KOTLIN: Record<string, [put: string, take: string]> = {
  String: ['string', 'string'],
  Int: ['int', 'int'],
  Long: ['long', 'long'],
  Short: ['short', 'short'],
  Byte: ['byte', 'byte'],
  Double: ['double', 'double'],
  Float: ['float', 'float'],
  Boolean: ['bool', 'bool'],
  UUID: ['uuid', 'uuid'],
  ByteArray: ['bytes', 'bytes'],
  IntArray: ['ints', 'ints'],
  ShortArray: ['shorts', 'shorts'],
  BooleanArray: ['bools', 'bools'],
  Any: ['dynamic', 'dynamic'],
  Throwable: ['error', 'error'],
}

/** A name for a function type's codec: `fn` and its number in [fns]. */
function kotlinFnName(
  node: TypeNode & { kind: 'function' },
  m: Model,
  fns: ReturnType<typeof functionTypes>,
): string {
  const key = rustType(node, m.cx, 'in')
  return `Fn${[...fns.keys()].indexOf(key)}`
}

interface KotlinCx {
  m: Model
  fns: ReturnType<typeof functionTypes>
}

/** A statement writing [v] (of [node]) to `w`. */
function ktPut(node: TypeNode, v: string, k: KotlinCx, depth = 0): string {
  const x = `x${depth}`
  switch (node.kind) {
    case 'star':
      return `w.dynamic(${v})`
    case 'optional':
      return `w.opt(${v}) { ${x} -> ${ktPut(node.inner, x, k, depth + 1)} }`
    case 'function':
      return `put${kotlinFnName(node, k.m, k.fns)}(w, ${v})`
    case 'named': {
      const [a, b, c] = node.args
      switch (node.name) {
        case 'Unit':
          return 'Unit'
        case 'List':
        case 'Set':
          return `w.list(${v}) { ${x} -> ${ktPut(a!, x, k, depth + 1)} }`
        case 'Map':
          return `w.map(${v}, { ${x} -> ${ktPut(a!, x, k, depth + 1)} }) { ${x} -> ${ktPut(b!, x, k, depth + 1)} }`
        case 'Pair':
          return `${v}.let { ${x} -> ${ktPut(a!, `${x}.first`, k, depth + 1)}; ${ktPut(b!, `${x}.second`, k, depth + 1)} }`
        case 'Triple':
          return `${v}.let { ${x} -> ${ktPut(a!, `${x}.first`, k, depth + 1)}; ${ktPut(b!, `${x}.second`, k, depth + 1)}; ${ktPut(c!, `${x}.third`, k, depth + 1)} }`
        case 'Result':
          return `w.result(${v}) { ${x} -> ${ktPut(a!, x, k, depth + 1)} }`
      }
      const primitive = PRIMITIVE_KOTLIN[node.name]
      if (primitive) return `w.${primitive[0]}(${v})`
      return `put${codecName(node.name)}(w, ${v})`
    }
  }
}

/** An expression reading a value of [node] from `r`. */
function ktTake(node: TypeNode, k: KotlinCx): string {
  switch (node.kind) {
    case 'star':
      return 'r.dynamic()'
    case 'optional':
      return `r.opt { ${ktTake(node.inner, k)} }`
    case 'function':
      return `take${kotlinFnName(node, k.m, k.fns)}(r)`
    case 'named': {
      const [a, b, c] = node.args
      switch (node.name) {
        case 'Unit':
          return 'Unit'
        case 'List':
          return `r.list { ${ktTake(a!, k)} }`
        case 'Set':
          return `r.list { ${ktTake(a!, k)} }.toSet()`
        case 'Map':
          return `r.map({ ${ktTake(a!, k)} }) { ${ktTake(b!, k)} }`
        case 'Pair':
          return `Pair(${ktTake(a!, k)}, ${ktTake(b!, k)})`
        case 'Triple':
          return `Triple(${ktTake(a!, k)}, ${ktTake(b!, k)}, ${ktTake(c!, k)})`
        case 'Result':
          return `r.result { ${ktTake(a!, k)} }`
      }
      if (node.name === 'Any') return 'r.dynamic()!!'
      const primitive = PRIMITIVE_KOTLIN[node.name]
      if (primitive) return `r.${primitive[1]}()`
      return `take${codecName(node.name)}(r)`
    }
  }
}

/** `InventoryRef.Player` → `InventoryRefPlayer`; `dev.x.DatapackRefusal` → `DatapackRefusal`. */
const codecName = (name: string) =>
  name.startsWith('dev.') ? name.split('.').pop()! : name.replace('.', '')

function kotlinCodecs(k: KotlinCx): string[] {
  const { m } = k
  const lines: string[] = []
  const fields = (fs: Param[]) => fs.map((f) => ({ ...f, node: parseType(f.type) }))
  for (const d of m.records.values()) {
    if (d.name === 'ChunkGrid') continue
    lines.push(
      `internal fun put${d.name}(w: Writer, v: ${d.name}) {`,
      ...fields(d.fields).map((f) => `    ${ktPut(f.node, `v.${kname(f.name)}`, k)}`),
      '}',
      '',
      `internal fun take${d.name}(r: Reader): ${d.name} = ${d.name}(${fields(d.fields)
        .map((f) => `${kname(f.name)} = ${ktTake(f.node, k)}`)
        .join(', ')})`,
      '',
    )
  }
  for (const d of m.enums.values()) {
    lines.push(
      `internal fun put${d.name}(w: Writer, v: ${d.name}) = w.int(v.ordinal)`,
      '',
      `internal fun take${d.name}(r: Reader): ${d.name} = r.entry(${d.name}.entries, "${d.name}")`,
      '',
    )
  }
  for (const d of m.unions.values()) {
    lines.push(`internal fun put${d.name}(w: Writer, v: ${d.name}) {`, '    when (v) {')
    d.cases.forEach((c, n) => {
      lines.push(
        `        is ${d.name}.${c.name} -> {`,
        `            w.int(${n})`,
        ...fields(c.fields).map((f) => `            ${ktPut(f.node, `v.${kname(f.name)}`, k)}`),
        '        }',
      )
    })
    lines.push('    }', '}', '')
    lines.push(`internal fun take${d.name}(r: Reader): ${d.name} = when (val case = r.int()) {`)
    d.cases.forEach((c, n) => {
      lines.push(
        c.fields.length
          ? `    ${n} -> ${d.name}.${c.name}(${fields(c.fields)
              .map((f) => `${kname(f.name)} = ${ktTake(f.node, k)}`)
              .join(', ')})`
          : `    ${n} -> ${d.name}.${c.name}`,
      )
    })
    lines.push(`    else -> throw IllegalArgumentException("${d.name} has no case $case")`, '}', '')
  }
  for (const name of Object.keys(EXTERNAL)) {
    const wire = externalWire(name)
    const type = KOTLIN_EXTERNAL[name]!
    const short = codecName(name)
    if (wire === 'json') {
      lines.push(
        `internal fun put${short}(w: Writer, v: ${type}) = w.string(CanonicalJson.json.encodeToString(${type}.serializer(), v))`,
        '',
        `internal fun take${short}(r: Reader): ${type} = CanonicalJson.json.decodeFromString(${type}.serializer(), r.string())`,
        '',
      )
    } else if (wire === 'entry') {
      lines.push(
        `internal fun put${short}(w: Writer, v: ${type}) = w.int(v.ordinal)`,
        '',
        `internal fun take${short}(r: Reader): ${type} = r.entry(${type}.entries, "${short}")`,
        '',
      )
    } else if (wire === 'placeholder') {
      lines.push(
        `/** \`${short}\` has no Rust type yet: it crosses as nothing, and can't come back. */`,
        `@Suppress("UNUSED_PARAMETER")`,
        `internal fun put${short}(w: Writer, v: ${type}) = w.dynamic(null)`,
        '',
        `internal fun take${short}(r: Reader): ${type} {`,
        '    r.dynamic()',
        `    throw UnsupportedOperationException("${short} doesn't cross from the Rust runtime yet")`,
        '}',
        '',
      )
    }
  }
  for (const [name, d] of m.interfaces) {
    if (m.sides.get(name) === 'java') {
      if (d.kind === 'object')
        lines.push(
          `internal fun put${name}(w: Writer, v: ${name}) {`,
          ...fields(d.fields ?? []).map((f) => `    ${ktPut(f.node, `v.${kname(f.name)}`, k)}`),
          `    w.java(Objects.export(v, ::dispatch${name}))`,
          '}',
          '',
        )
      else
        lines.push(
          `internal fun put${name}(w: Writer, v: ${name}) = w.java(Objects.export(v, ::dispatch${name}))`,
          '',
        )
    } else {
      lines.push(
        `internal fun take${name}(r: Reader): ${KOTLIN_EXTERNAL[name] ?? name} = Rust${name}(r.rust())`,
        '',
      )
    }
  }
  for (const [key, { node, side }] of k.fns) {
    const name = kotlinFnName(node, m, k.fns)
    const type = kotlinType(node)
    const params = node.params.map((p, n) => ({ name: `a${n}`, node: p.type }))
    if (side === 'java') {
      lines.push(
        `/** \`${key}\`, the JVM's, handed to Rust. */`,
        `internal fun put${name}(w: Writer, v: ${type}) = w.java(`,
        `    Objects.export(v) { f, _, r, w -> ${params.map((p) => `val ${p.name} = ${ktTake(p.node, k)}; `).join('')}val result = f(${params.map((p) => p.name).join(', ')}); ${ktPut(node.returns, 'result', k)} }`,
        ')',
        '',
      )
    } else {
      lines.push(
        `/** \`${key}\`, Rust's, called from the JVM. */`,
        `internal fun take${name}(r: Reader): ${type} {`,
        '    val id = r.rust()',
        `    val f: ${type} = { ${params.map((p) => p.name).join(', ')}${params.length ? ' ->' : ''} Native.call(id, 0, { w -> ${params.map((p) => ktPut(p.node, p.name, k)).join('; ') || 'Unit'} }) { r -> ${ktTake(node.returns, k)} } }`,
        '    Native.track(f, id)',
        '    return f',
        '}',
        '',
      )
    }
  }
  return lines
}

/** A JVM interface's dispatcher: what Rust's calls by number run. */
function kotlinDispatcher(d: InterfaceDecl, k: KotlinCx): string[] {
  const type = KOTLIN_EXTERNAL[d.name] ?? d.name
  const lines = [
    `internal fun dispatch${d.name}(o: ${type}, method: Int, r: Reader, w: Writer) {`,
    '    when (method) {',
  ]
  for (const x of membersOf(d, k.m)) {
    if (x.returns.kind === 'named' && x.returns.name === 'GameData') {
      // Rust reads the game's facts once, as the bundle exportGameData makes.
      lines.push(
        `        ${x.id} -> throw UnsupportedOperationException("Rust reads ${d.name}.${x.name} through exportGameData")`,
      )
      continue
    }
    const takes = x.params.map((p, n) => `val a${n} = ${ktTake(p.type, k)}`)
    const call = x.property
      ? `o.${kname(x.name)}`
      : `o.${kname(x.name)}(${x.params.map((_, n) => `a${n}`).join(', ')})`
    lines.push(
      `        ${x.id} -> {`,
      ...takes.map((t) => `            ${t}`),
      `            val result = ${call}`,
      `            ${ktPut(x.returns, 'result', k)}`,
      '        }',
    )
  }
  lines.push(
    `        else -> throw IllegalArgumentException("${d.name} has no method $method")`,
    '    }',
    '}',
    '',
  )
  return lines
}

/** A Rust interface as Kotlin: a proxy calling through its id. */
function kotlinProxy(d: InterfaceDecl, k: KotlinCx): string[] {
  const type = KOTLIN_EXTERNAL[d.name] ?? d.name
  const lines = [
    `/** Rust's \`${d.name}\`, called through its id. */`,
    `internal class Rust${d.name}(private val rustId: Long) : ${type} {`,
    '    init {',
    '        Native.track(this, rustId)',
    '    }',
    '',
  ]
  for (const x of membersOf(d, k.m)) {
    const ret = kotlinType(x.returns)
    const put = x.params.map((p) => ktPut(p.type, kname(p.name), k)).join('; ') || 'Unit'
    const call = `Native.call(rustId, ${x.id}, { w -> ${put} }) { r -> ${ktTake(x.returns, k)} }`
    if (x.property) {
      lines.push(`    override val ${kname(x.name)}: ${ret} by lazy { ${call} }`, '')
    } else {
      lines.push(
        `    override fun ${kname(x.name)}(${x.params.map((p) => `${kname(p.name)}: ${kotlinType(p.type)}`).join(', ')}): ${ret} = ${call}`,
        '',
      )
    }
  }
  if (lines[lines.length - 1] === '') lines.pop()
  lines.push('}', '')
  return lines
}

export function ffiKotlin(spec: PlatformFile[]): Map<string, string> {
  const m = model(spec)
  const k: KotlinCx = { m, fns: functionTypes(m) }
  const head = (what: string) => [
    `// ${GENERATED_BY}`,
    '',
    `// ${what}`,
    '',
    '@file:Suppress("ktlint", "UNUSED_VARIABLE", "UNUSED_ANONYMOUS_PARAMETER", "RedundantUnitExpression", "UNUSED_EXPRESSION", "RemoveRedundantQualifierName")',
    '',
    'package dev.netherforge.plugin.ffi',
    '',
    'import dev.netherforge.format.json.CanonicalJson',
    'import dev.netherforge.plugin.platform.*',
    'import java.util.UUID',
    '',
  ]
  const text = (lines: string[]) =>
    `${lines
      .join('\n')
      .replace(/\n{3,}/g, '\n\n')
      .trimEnd()}\n`
  const out = new Map<string, string>()
  out.set(
    'Codecs.kt',
    text([
      ...head("Every spec type's wire codec: `put<Type>` and `take<Type>`."),
      ...kotlinCodecs(k),
    ]),
  )
  out.set(
    'Dispatch.kt',
    text([
      ...head("The JVM's interfaces as Rust calls them: a dispatcher each."),
      ...[...m.interfaces.values()]
        .filter((d) => m.sides.get(d.name) === 'java')
        .flatMap((d) => kotlinDispatcher(d, k)),
      // GameData isn't in the spec: Rust reads the game's facts once, through exportGameData.
    ]),
  )
  out.set(
    'Proxies.kt',
    text([
      ...head("Rust's interfaces as Kotlin: each a proxy calling through its id."),
      ...[...m.interfaces.values()]
        .filter((d) => m.sides.get(d.name) === 'rust')
        .flatMap((d) => kotlinProxy(d, k)),
    ]),
  )
  return out
}
