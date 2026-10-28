package dev.netherforge.format.recipe

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.GameData
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.validate.Rules

/**
 * Checks a recipe on its own: each type's fields, the pattern, every
 * ingredient's shape (and, with game data, that its item exists), and the
 * result as any item. That a project item an ingredient or the result names
 * exists is the project's to check ([ingredients] lists them, with paths).
 */
object RecipeValidator {
    /** The most rows and columns a shaped pattern has: a crafting table's grid. */
    const val GRID = 3

    /** The most ingredients a shapeless recipe takes. */
    const val MOST_INGREDIENTS = GRID * GRID

    fun validate(file: RecipeFile, sink: ProblemSink, game: GameData?) {
        val type = file.type
        val fields = mapOf(
            "pattern" to (file.pattern != null),
            "key" to (file.key != null),
            "ingredients" to (file.ingredients != null),
            "ingredient" to (file.ingredient != null),
            "template" to (file.template != null),
            "base" to (file.base != null),
            "addition" to (file.addition != null),
            "experience" to (file.experience != null),
            "cookingTime" to (file.cookingTime != null),
            "materials" to (file.materials != null),
            "stations" to (file.stations != null),
            "stationRange" to (file.stationRange != null)
        )
        for ((field, present) in fields) {
            if (present && field !in type.requiredFields && field !in type.optionalFields) {
                sink.report(ProblemCodes.RECIPE_FIELD, "A ${type.serial} recipe has no $field", "$.$field")
            }
        }
        for (field in type.requiredFields) {
            if (fields[field] != true) sink.report(ProblemCodes.RECIPE_MISSING, "A ${type.serial} recipe needs $field", "$")
        }

        if (file.group != null && !type.takesGroup) {
            sink.report(ProblemCodes.RECIPE_FIELD, "A smithing_transform recipe has no group", "$.group")
        }
        when (type) {
            RecipeType.SHAPED -> shaped(file, sink)
            RecipeType.SHAPELESS -> file.ingredients?.let { list ->
                if (list.isEmpty() || list.size > MOST_INGREDIENTS) {
                    sink.report(
                        ProblemCodes.RECIPE_INGREDIENTS,
                        "A shapeless recipe takes 1 to $MOST_INGREDIENTS ingredients",
                        "$.ingredients"
                    )
                }
            }
            RecipeType.STATION -> station(file, sink)
            else -> Unit
        }
        if (type.cooking) {
            file.experience?.let {
                if (!it.isFinite() || it < 0) sink.report(ProblemCodes.RECIPE_EXPERIENCE, "experience can't be negative", "$.experience")
            }
            file.cookingTime?.let {
                if (it < 1) sink.report(ProblemCodes.RECIPE_COOKING_TIME, "cookingTime must be at least 1 tick", "$.cookingTime")
            }
        }
        file.category?.let { category ->
            if (category !in type.categories) {
                val allowed = type.categories.joinToString { "\"${CanonicalJson.serialName(it)}\"" }
                val message = if (allowed.isEmpty()) {
                    "A ${type.serial} recipe has no category"
                } else {
                    "A ${type.serial} recipe's category is one of $allowed"
                }
                sink.report(ProblemCodes.RECIPE_CATEGORY, message, "$.category")
            }
        }

        for ((path, ingredient) in ingredients(file)) Rules.ingredient(ingredient, path, Rules.RECIPE_INGREDIENT, sink, game)
        Rules.item(file.result, "$.result", sink, game)
    }

    /** Every ingredient in [file], with its JSON path. */
    fun ingredients(file: RecipeFile): List<Pair<String, Ingredient>> = buildList {
        file.key?.forEach { (symbol, ingredient) -> add(CanonicalJson.childPath("$.key", symbol) to ingredient) }
        file.ingredients?.forEachIndexed { index, ingredient -> add("$.ingredients[$index]" to ingredient) }
        file.ingredient?.let { add("$.ingredient" to it) }
        file.template?.let { add("$.template" to it) }
        file.base?.let { add("$.base" to it) }
        file.addition?.let { add("$.addition" to it) }
        file.materials?.forEachIndexed { index, material -> add("$.materials[$index].ingredient" to material.ingredient) }
    }

    private fun station(file: RecipeFile, sink: ProblemSink) {
        val materials = file.materials ?: return
        if (materials.isEmpty()) sink.report(ProblemCodes.RECIPE_INGREDIENTS, "A station recipe takes at least one material", "$.materials")
        materials.forEachIndexed { index, material ->
            val count = material.count
            if (count != null &&
                count < 1
            ) {
                sink.report(ProblemCodes.RECIPE_INGREDIENTS, "count must be at least 1", "$.materials[$index].count")
            }
        }
        file.stations?.forEachIndexed { index, station ->
            if (station.split('|').any { it.isBlank() }) {
                sink.report(
                    ProblemCodes.RECIPE_STATION,
                    "A station is a block id, a #tag or a project block, or several of them between | (any of them)",
                    "$.stations[$index]"
                )
            }
        }
        val range = file.stationRange
        if (range != null && range !in 1..Material.MAX_STATION_RANGE) {
            sink.report(ProblemCodes.RECIPE_STATION, "stationRange must be 1 to ${Material.MAX_STATION_RANGE}", "$.stationRange")
        }
    }

    private fun shaped(file: RecipeFile, sink: ProblemSink) {
        val pattern = file.pattern ?: return
        val key = file.key.orEmpty()
        if (pattern.isEmpty() || pattern.size > GRID) {
            sink.report(ProblemCodes.RECIPE_PATTERN, "A pattern has 1 to $GRID rows", "$.pattern")
        }
        val width = pattern.firstOrNull()?.length ?: 0
        pattern.forEachIndexed { index, row ->
            if (row.isEmpty() || row.length > GRID) {
                sink.report(ProblemCodes.RECIPE_PATTERN, "A pattern row has 1 to $GRID characters", "$.pattern[$index]")
            } else if (row.length != width) {
                sink.report(ProblemCodes.RECIPE_PATTERN, "Every row of a pattern is as wide as the first ($width)", "$.pattern[$index]")
            }
        }
        if (pattern.isNotEmpty() && pattern.all { it.isBlank() }) {
            sink.report(ProblemCodes.RECIPE_PATTERN, "A pattern needs at least one ingredient", "$.pattern")
        }
        val used = pattern.flatMap { it.toList() }.filter { it != ' ' }.toSet()
        for (symbol in used) {
            if (symbol.toString() !in key) {
                sink.report(ProblemCodes.RECIPE_PATTERN, "\"$symbol\" in the pattern isn't in key", "$.pattern")
            }
        }
        for (symbol in key.keys) {
            val at = CanonicalJson.childPath("$.key", symbol)
            when {
                symbol.length != 1 || symbol == " " -> sink.report(ProblemCodes.RECIPE_KEY, "A key is one character other than a space", at)
                symbol[0] !in used -> sink.report(ProblemCodes.RECIPE_KEY, "\"$symbol\" isn't in the pattern", at)
            }
        }
    }

    private val RecipeType.serial: String get() = CanonicalJson.serialName(this)
}
