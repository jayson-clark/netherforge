/** The settings tab's pages, in the order the outline lists them. */
import type { IconName } from '@/ui/Icon'

export type SettingsPage = 'minecraft' | 'server' | 'owner' | 'updates' | 'agents'

export const SETTINGS_PAGES: { id: SettingsPage; title: string; icon: IconName; hint: string }[] = [
  {
    id: 'minecraft',
    title: 'Minecraft',
    icon: 'cube',
    hint: 'Minecraft installs and the versions imported from them',
  },
  {
    id: 'server',
    title: 'Server',
    icon: 'play',
    hint: "The dev server's memory, port and JVM arguments",
  },
  {
    id: 'owner',
    title: 'Server-owner settings',
    icon: 'gear',
    hint: "The values the dev server runs the project's settings with",
  },
  { id: 'updates', title: 'Updates', icon: 'refresh', hint: 'NetherForge versions and updates' },
  {
    id: 'agents',
    title: 'Agents',
    icon: 'sparkles',
    hint: 'Files and the MCP server for coding agents',
  },
]

/** The page [selected] names (what the settings tab's view selects), else the first. */
export function settingsPageOf(selected: unknown): SettingsPage {
  return SETTINGS_PAGES.find((it) => it.id === selected)?.id ?? 'minecraft'
}
