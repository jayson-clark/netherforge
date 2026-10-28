/**
 * What NetherForge writes into a project's `.netherforge/` for the tools
 * around it, shared by the editor and `netherforge setup`.
 */
export { agentDocFiles, agentReadme } from './agentDocs.ts'
export {
  gateReason,
  tailoredApiStubs,
  versionBefore,
  type ApiGate,
  type ApiStubs,
  type ProjectLimits,
} from './api.ts'
export { agentDocPaths, loadApiStubs, loadDocPages, schemaFiles } from './bundled.ts'
export {
  lualsLibraryFiles,
  PACKAGE_STUBS,
  packageModuleStubs,
  projectNames,
  projectStub,
  type ModelReader,
  type NetworkNames,
  type PackageSource,
  type ProjectNames,
} from './luals.ts'
export {
  lualsFiles,
  modelsIn,
  packageSources,
  SCHEMA_FOLDER,
  setupFiles,
  type ProjectOnDisk,
} from './setup.ts'
