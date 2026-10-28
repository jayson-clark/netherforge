package dev.netherforge.format.dialog

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names
import dev.netherforge.format.validate.Rules

object DialogValidator {

    fun validate(file: DialogFile, sink: ProblemSink, files: Set<String>?, game: GameData?) {
        val type = file.type ?: DialogType.NOTICE

        if (file.columns != null) {
            if (type != DialogType.MULTI_ACTION) {
                sink.report(ProblemCodes.DIALOG_COLUMNS_TYPE, "Only a multi_action dialog lays its buttons out in columns", "$.columns")
            } else if (file.columns !in DialogType.MIN_COLUMNS..DialogType.MAX_COLUMNS) {
                sink.report(
                    ProblemCodes.DIALOG_COLUMNS,
                    "A dialog has ${DialogType.MIN_COLUMNS} to ${DialogType.MAX_COLUMNS} columns",
                    "$.columns"
                )
            }
        }

        val bodyKeys = mutableSetOf<String>()
        file.body.forEachIndexed { index, body ->
            val at = "$.body[$index]"
            body.key?.let { key ->
                if (!Names.isNodeName(key)) {
                    sink.report(ProblemCodes.DIALOG_BODY_KEY, "\"$key\" isn't a usable key (${Names.NODE_NAME_RULE})", "$at.key")
                } else if (!bodyKeys.add(key)) {
                    sink.report(ProblemCodes.DIALOG_BODY_DUPLICATE, "Two body elements are called \"$key\"", "$at.key")
                }
            }
            when (body) {
                is MessageBody -> if (body.text.isBlank()) {
                    sink.report(
                        ProblemCodes.DIALOG_BODY_EMPTY,
                        "This message has no text",
                        "$at.text"
                    )
                }
                is ItemBody -> Rules.item(body.item, "$at.item", sink, game)
            }
        }

        val inputKeys = mutableSetOf<String>()
        file.inputs.forEachIndexed { index, input ->
            val at = "$.inputs[$index]"
            if (!Names.isNodeName(input.key)) {
                sink.report(ProblemCodes.DIALOG_INPUT_KEY, "\"${input.key}\" isn't a usable key (${Names.NODE_NAME_RULE})", "$at.key")
            } else if (!inputKeys.add(input.key)) {
                sink.report(ProblemCodes.DIALOG_INPUT_DUPLICATE, "Two inputs are called \"${input.key}\"", "$at.key")
            }
            when (input) {
                is TextInput -> {
                    if (input.maxLength != null && input.maxLength < 1) {
                        sink.report(ProblemCodes.DIALOG_MAX_LENGTH, "maxLength must be at least 1", "$at.maxLength")
                    }
                    if (input.lines != null && input.lines < 1) {
                        sink.report(ProblemCodes.DIALOG_LINES, "lines must be at least 1", "$at.lines")
                    }
                }
                is OptionInput -> {
                    if (input.options.isEmpty()) {
                        sink.report(ProblemCodes.DIALOG_OPTIONS_EMPTY, "A single_option input needs options", "$at.options")
                    }
                    val ids = mutableSetOf<String>()
                    input.options.forEachIndexed { i, option ->
                        if (!ids.add(option.id)) {
                            sink.report(
                                ProblemCodes.DIALOG_OPTION_DUPLICATE,
                                "Two options are called \"${option.id}\"",
                                "$at.options[$i].id"
                            )
                        }
                    }
                    if (input.options.count { it.initial == true } > 1) {
                        sink.report(
                            ProblemCodes.DIALOG_OPTION_INITIAL,
                            "More than one option starts selected; the first wins",
                            "$at.options"
                        )
                    }
                }
                is RangeInput -> {
                    if (input.end <= input.start) {
                        sink.report(ProblemCodes.DIALOG_RANGE, "end must be greater than start", "$at.end")
                    }
                    if (input.step != null && input.step <= 0) {
                        sink.report(ProblemCodes.DIALOG_RANGE_STEP, "step must be positive", "$at.step")
                    }
                    if (input.initial != null && (input.initial < input.start || input.initial > input.end)) {
                        sink.report(ProblemCodes.DIALOG_RANGE_INITIAL, "initial must be between start and end", "$at.initial")
                    }
                }
                is BooleanInput -> Unit
            }
        }

        val buttonKeys = mutableSetOf<String>()
        file.buttons.forEachIndexed { index, button ->
            val at = "$.buttons[$index]"
            if (!Names.isNodeName(button.key)) {
                sink.report(ProblemCodes.DIALOG_BUTTON_KEY, "\"${button.key}\" isn't a usable key (${Names.NODE_NAME_RULE})", "$at.key")
            } else if (!buttonKeys.add(button.key)) {
                sink.report(ProblemCodes.DIALOG_BUTTON_DUPLICATE, "Two buttons are called \"${button.key}\"", "$at.key")
            }
        }
        val limit = type.buttonLimit
        if (limit != null && file.buttons.size > limit) {
            val what = if (type == DialogType.DIALOG_LIST) "only its exit button" else "$limit button${if (limit == 1) "" else "s"}"
            sink.report(ProblemCodes.DIALOG_BUTTON_COUNT, "A ${CanonicalJson.serialName(type)} dialog shows $what", "$.buttons")
        }
        if (type == DialogType.CONFIRMATION && file.buttons.size < 2) {
            sink.report(ProblemCodes.DIALOG_CONFIRMATION, "A confirmation dialog has a yes and a no button", "$.buttons")
        }

        if (type == DialogType.DIALOG_LIST) {
            if (file.dialogs.isEmpty()) sink.report(ProblemCodes.DIALOG_LIST_EMPTY, "This dialog_list lists no dialogs", "$.dialogs")
        } else if (file.dialogs.isNotEmpty()) {
            sink.report(ProblemCodes.DIALOG_LIST_TYPE, "Only a dialog_list lists other dialogs", "$.dialogs")
        }

        file.script?.let { Rules.script(it, "$.script", sink, files) }
    }
}
