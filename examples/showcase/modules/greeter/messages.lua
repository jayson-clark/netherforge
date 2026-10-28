local messages = {}

function messages.welcome(greeting, name)
  return "<green>" .. nf.text.escape(greeting) .. ", " .. name .. "!"
end

return messages
