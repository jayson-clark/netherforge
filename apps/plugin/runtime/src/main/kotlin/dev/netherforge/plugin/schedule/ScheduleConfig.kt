package dev.netherforge.plugin.schedule

/**
 * `schedules:` in `config.yml`, read on dev servers too: the IANA time zone
 * (`Europe/Paris`) or offset (`+02:00`) that `nf.schedule`'s times are in, or
 * null for the server's own zone. A zone Java doesn't know is reported when
 * the session starts, and the server's own is used instead.
 */
data class ScheduleConfig(val timeZone: String? = null)
