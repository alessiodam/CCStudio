local completion = require "cc.shell.completion"

shell.setCompletionFunction("rom/programs/code.lua", completion.build({ completion.choice, { "open", "trust ", "status", "stop" } }))
