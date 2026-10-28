/** As the Rust side's `server/jvm_args.rs` (the list and its rules are documented there): which extra JVM arguments the dev server takes. */

const JVM_BOOLEAN_FLAGS = new Set([
  'UseG1GC',
  'UseZGC',
  'ZGenerational',
  'UseShenandoahGC',
  'UseParallelGC',
  'UseSerialGC',
  'AlwaysPreTouch',
  'DisableExplicitGC',
  'ParallelRefProcEnabled',
  'UseStringDeduplication',
  'PerfDisableSharedMem',
  'UseNUMA',
  'UseTransparentHugePages',
  'UseLargePages',
  'UnlockExperimentalVMOptions',
  'HeapDumpOnOutOfMemoryError',
  'ExitOnOutOfMemoryError',
  'CrashOnOutOfMemoryError',
  'OmitStackTraceInFastThrow',
  'UseCompressedOops',
  'UseCompressedClassPointers',
  'AlwaysActAsServerClassMachine',
  'UseFastUnorderedTimeStamps',
])

const JVM_NUMBER_FLAGS = new Set([
  'MaxGCPauseMillis',
  'G1NewSizePercent',
  'G1MaxNewSizePercent',
  'G1HeapRegionSize',
  'G1ReservePercent',
  'G1HeapWastePercent',
  'G1MixedGCCountTarget',
  'G1MixedGCLiveThresholdPercent',
  'G1RSetUpdatingPauseTimePercent',
  'InitiatingHeapOccupancyPercent',
  'SurvivorRatio',
  'MaxTenuringThreshold',
  'ParallelGCThreads',
  'ConcGCThreads',
  'MaxRAMPercentage',
  'MinRAMPercentage',
  'InitialRAMPercentage',
  'MaxMetaspaceSize',
  'ReservedCodeCacheSize',
  'SoftMaxHeapSize',
  'ZCollectionInterval',
  'MaxInlineLevel',
])

const JVM_BLOCKED_PROPERTY_PREFIXES = [
  'java.',
  'javax.',
  'jdk',
  'sun.',
  'com.sun.',
  'user.',
  'os.',
  'file.separator',
  'path.separator',
  'line.separator',
  'netherforge.',
  'log4j',
  'org.apache.logging.',
  'jna.',
  'polyglot.',
  'org.graalvm.',
]

/** As the Rust side's `jvm_args::check` (the list and its rules are documented there): why [arg] isn't allowed, or null. */
export function jvmArgumentProblem(arg: string): string | null {
  const size = (text: string) => /^[0-9]{1,12}[kKmMgG]?$/.test(text)
  if (new TextEncoder().encode(arg).length > 1024) return "it's too long"
  if (!arg || arg.trim() !== arg) return "it's empty or has spaces around it"
  const memory = /^-X(?:mx|ms|mn|ss)(.*)$/s.exec(arg)
  if (memory) return size(memory[1]!) ? null : 'give a size such as 4G, 512m or 2048'
  if (arg.startsWith('-XX:')) {
    const flag = arg.slice(4)
    if (/^[+-]/.test(flag)) {
      return JVM_BOOLEAN_FLAGS.has(flag.slice(1))
        ? null
        : "that -XX switch isn't on the editor's list"
    }
    const eq = flag.indexOf('=')
    const name = eq < 0 ? '' : flag.slice(0, eq)
    if (!JVM_NUMBER_FLAGS.has(name)) return "that -XX option isn't on the editor's list"
    const value = flag.slice(eq + 1)
    const ok = name.endsWith('Percentage')
      ? /^[0-9]{1,12}(\.[0-9]{1,6})?$/.test(value)
      : size(value)
    return ok ? null : 'the value must be a number (with k, m or g for a size)'
  }
  if (arg.startsWith('-D')) {
    const eq = arg.indexOf('=')
    const name = eq < 0 ? arg.slice(2) : arg.slice(2, eq)
    const value = eq < 0 ? '' : arg.slice(eq + 1)
    if (!/^[A-Za-z][A-Za-z0-9_.-]*$/.test(name))
      return 'a property name is letters, digits, `_`, `.` and `-`, starting with a letter'
    const lower = name.toLowerCase()
    if (JVM_BLOCKED_PROPERTY_PREFIXES.some((it) => lower.startsWith(it)))
      return 'that property belongs to the JVM, its libraries or the editor'
    // eslint-disable-next-line no-control-regex
    if (/[\u0000-\u001f\u007f-\u009f]/.test(value)) return 'the value has a control character'
    return null
  }
  if (
    ['-server', '-Xshare:auto', '-Xshare:off', '-verbose:gc', '-Xlog:gc', '-Xlog:gc*'].includes(arg)
  )
    return null
  return /^(-javaagent|-agent|-Xrun|-Xbootclasspath|-cp|-classpath|--|@)/.test(arg)
    ? 'agents, class paths, module options and argument files can run or load code'
    : "it isn't an allowed memory, -XX or -D argument"
}
