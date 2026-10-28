nf.recipes.register("it_planks", {
  type = "shapeless",
  ingredients = { "#minecraft:planks", { item = "ruby" } },
  result = { kind = "minecraft:paper", count = 3 },
})
log("recipes", table.concat(nf.recipes.all(), ","))
