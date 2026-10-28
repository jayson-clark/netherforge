package dev.netherforge.plugin.paper

import dev.netherforge.format.item.AttributeOperation
import org.bukkit.attribute.AttributeModifier

// How a modifier's amount applies, in NetherForge's (vanilla's) words and Bukkit's: one mapping for
// items' `attribute_modifiers` (PaperItems) and entities' own modifiers (PaperMobs).

/** Bukkit's name for this operation. */
internal fun AttributeOperation.paper(): AttributeModifier.Operation = when (this) {
    AttributeOperation.ADD_VALUE -> AttributeModifier.Operation.ADD_NUMBER
    AttributeOperation.ADD_MULTIPLIED_BASE -> AttributeModifier.Operation.ADD_SCALAR
    AttributeOperation.ADD_MULTIPLIED_TOTAL -> AttributeModifier.Operation.MULTIPLY_SCALAR_1
}

/** NetherForge's name for this operation. */
internal fun AttributeModifier.Operation.netherforge(): AttributeOperation = when (this) {
    AttributeModifier.Operation.ADD_NUMBER -> AttributeOperation.ADD_VALUE
    AttributeModifier.Operation.ADD_SCALAR -> AttributeOperation.ADD_MULTIPLIED_BASE
    AttributeModifier.Operation.MULTIPLY_SCALAR_1 -> AttributeOperation.ADD_MULTIPLIED_TOTAL
}
