package dev.netherforge.format.codegen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * The name the contract generator gives this class's TypeScript type, when
 * the one it would derive from the serial name isn't right (a sealed
 * subclass's serial name is its discriminator, `"posed"`, not a type name).
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.CLASS)
annotation class TsName(val name: String)
