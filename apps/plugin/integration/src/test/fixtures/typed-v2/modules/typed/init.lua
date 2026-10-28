nf.commands.register(
  "it-typed",
  { arguments = { { name = "word", type = "word" } } },
  function(event)
    log("typed again " .. event.arguments.word)
  end
)
nf.commands.register(
  "it-renamed",
  { arguments = { { name = "n", type = "integer" } } },
  function(event)
    log("renamed " .. event.arguments.n)
  end
)
