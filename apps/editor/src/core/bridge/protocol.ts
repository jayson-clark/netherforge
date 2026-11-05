/**
 * The dev bridge's messages, as the editor's UI speaks them: generated from
 * format's Rust definition (`crates/format/src/bridge`) into the backend's
 * bindings by tauri-specta (`pnpm generate`). Import them from here.
 *
 * `BridgeRequests` maps each method to what it takes and answers,
 * `BridgeEvents` each notification to its params (a stream's carry a batch of
 * items). A shape whose reading and writing differ (a field read with a
 * default, written only when it says something) is the union of both.
 */
export type {
  BotAction,
  BotEvent,
  BotInfo,
  BridgeEvents,
  BridgeRequests,
  ConsoleEntry,
  HandlerTime,
  InstanceInfo,
  LoadedWorld,
  PackageSettings,
  PlayerPosition,
  ProfileSample,
  ProfileTick,
  ReloadedResource,
  ReloadResult,
  ServerSettings,
  SettingState,
  SourceRef,
} from '@/core/backend/generated/bindings'
