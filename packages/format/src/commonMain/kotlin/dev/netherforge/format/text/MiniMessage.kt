package dev.netherforge.format.text

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * Marks a property whose text is MiniMessage (a string, or a list of them):
 * what players see, where `<glyph:ui/coin>` tags may appear. The reference
 * walker (`ref/RefWalker.kt`) finds every tag in every marked field, so a new
 * text field's glyphs are checked, renamed and counted as usages by its
 * being marked.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class MiniMessage
