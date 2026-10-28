import type { LuaClass } from '../types.ts'

/** What both interop namespaces say about being declared and about a plugin that isn't there. */
const PLUGIN_DOC =
  "The plugin has to be on the server and enabled: a package that declares it (`requires`) while it isn't is a problem on the project's problem list until it is, and calling without it is an error. A plugin that is enabled after NetherForge counts from then on."

/** `nf.economy`: the server's money, through Vault. */
export const nfEconomy: LuaClass = {
  name: 'nf.economy',
  doc: `Players' money, as the server's economy plugin keeps it, through [Vault](https://www.spigotmc.org/resources/vault.34315/): whichever economy is registered with it (EssentialsX, CMI, …) answers, so a project never names one. Needs Vault and an economy plugin. ${PLUGIN_DOC} The economy is looked up when it's asked, so one that registers late (after NetherForge starts) works from then on. Amounts are plain numbers in the economy's own currency; a player who is offline works as well as one who is online.`,
  methods: false,
  fields: [],
  functions: [
    {
      name: 'balance',
      doc: 'How much money the player has: `0` for someone the economy has no account for.',
      requires: 'plugin:vault',
      params: [{ name: 'player', type: 'Player', doc: 'Online or not.' }],
      returns: [{ type: 'number' }],
      example: 'player:send_message("You have " .. nf.economy.balance(player) .. " coins")',
    },
    {
      name: 'deposit',
      doc: "Gives the player money. Returns their new balance, or `nil` when the economy refused (it has no account for them, or can't hold that much). An amount that isn't positive and finite is an error.",
      requires: 'plugin:vault',
      params: [
        { name: 'player', type: 'Player', doc: 'Online or not.' },
        { name: 'amount', type: 'number', doc: 'More than 0.' },
      ],
      returns: [{ type: 'number?', doc: 'their balance afterwards' }],
      example:
        'local balance = nf.economy.deposit(player, 50)\nif balance then\n  player:send_message("Paid out. You have " .. balance)\nend',
    },
    {
      name: 'withdraw',
      doc: "Takes money from the player. Returns their new balance, or `nil` when the economy refused, usually because they don't have that much (nothing is taken then). An amount that isn't positive and finite is an error.",
      requires: 'plugin:vault',
      params: [
        { name: 'player', type: 'Player', doc: 'Online or not.' },
        { name: 'amount', type: 'number', doc: 'More than 0.' },
      ],
      returns: [{ type: 'number?', doc: 'their balance afterwards' }],
      example:
        'if nf.economy.withdraw(player, 100) then\n  player:send_message("<green>Bought")\nelse\n  player:send_message("<red>You can\'t afford that")\nend',
    },
  ],
}

/** `nf.placeholders`: PlaceholderAPI, both ways. */
export const nfPlaceholders: LuaClass = {
  name: 'nf.placeholders',
  doc: `Placeholders, through [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/): fill in the \`%player_name%\`-style placeholders every plugin offers, and offer your own as \`%<namespace>_<key>%\` to every plugin that reads them (scoreboards, chat formats, holograms). Needs PlaceholderAPI. ${PLUGIN_DOC}`,
  methods: false,
  fields: [],
  functions: [
    {
      name: 'parse',
      doc: 'Replaces every placeholder in the text with its value, as PlaceholderAPI does: `"Hello %player_name%"` becomes `"Hello Alex"`. A placeholder nobody answers is left as written. Placeholders registered with `nf.placeholders.register` are answered too. A value is plain text, not MiniMessage: if it comes from players, `nf.text.escape` the result before sending it as MiniMessage.',
      requires: 'plugin:placeholderapi',
      params: [
        { name: 'text', type: 'string', doc: 'Text with `%placeholders%` in it.' },
        {
          name: 'player',
          type: 'Player',
          doc: "Whom the placeholders are about (`%player_name%`); without one, only placeholders that don't need a player have a value.",
          optional: true,
        },
      ],
      returns: [{ type: 'string' }],
      example:
        'local line = nf.placeholders.parse("Welcome, %player_name%! Rank: %vault_rank%", player)\nplayer:send_message(nf.text.escape(line))',
    },
    {
      name: 'register',
      doc: 'Makes `%<namespace>_<key>%` a placeholder for the whole server: PlaceholderAPI asks `callback` for the value whenever any plugin parses it. `callback` is called with the `key` (everything after the first `_`, so `%shop_price_apple%` is `"price_apple"`) and the player it\'s for, if there is one, and returns the text (or a number) to put there, or `nil` when it has no value for that key (the placeholder is then left as written). It runs on the main thread when the server asks from the main thread; PlaceholderAPI is also asked from other threads (async chat, scoreboard plugins), where scripts can\'t run: there the placeholder answers the value `callback` last gave for that same key and player, and `nil` before it ever has. The placeholder belongs to the script that registered it and goes when that script unloads (a reload, the server stopping). A namespace another script or plugin already uses is an error.',
      requires: 'plugin:placeholderapi',
      params: [
        {
          name: 'namespace',
          type: 'string',
          doc: 'What comes before the first `_`: lowercase letters and digits, starting with a letter, at most 32 characters, like `"shop"`.',
        },
        {
          name: 'callback',
          type: 'fun(key: string, player: Player?): string|number|nil',
          doc: 'Gives the value. An error in it is logged with its file and line, and the placeholder is left as written for that request.',
        },
      ],
      returns: [],
      example:
        'nf.placeholders.register("shop", function(key, player)\n  if key == "sales" then\n    return nf.data("shop").sales or 0\n  end\n  return nil\nend)\n-- %shop_sales% now works in any plugin that reads placeholders',
    },
  ],
}
