local args = { ... }
local command = args[1] or "open"

local function setColour(colour)
    if term.isColour() then term.setTextColour(colour) end
end

local function printUsage()
    print("Usages:")
    print("code              open this computer in VS Code")
    print("code trust        list browsers waiting for approval")
    print("code trust <code> allow a browser to open the editor")
    print("code status       show the current session")
    print("code stop         end the session, revoke all browsers")
end

local function printSession(info)
    setColour(colours.lightGrey)
    print("Open this link in your browser:")
    setColour(colours.lightBlue)
    print(info.url)
    setColour(colours.lightGrey)
    if (info.notified or 0) > 0 then
        print("A clickable link was sent to your chat.")
    end
    if info.editor ~= "ready" then
        setColour(colours.yellow)
        print(info.editorMessage)
        setColour(colours.lightGrey)
    end
    if info.tunnel and info.tunnel ~= "connected" then
        setColour(colours.yellow)
        print("Cloudflare Tunnel: " .. info.tunnel)
        setColour(colours.lightGrey)
    end
    print("The page shows a code. Approve it with \"code trust <code>\".")
    setColour(colours.white)
end

local function printPending(pending)
    if #pending == 0 then
        print("No browsers are waiting for approval.")
        return
    end
    for _, request in ipairs(pending) do
        setColour(colours.yellow)
        write(request.code)
        setColour(colours.lightGrey)
        print((" %s, %s, %ds ago"):format(request.address, request.browser, request.age))
    end
    setColour(colours.white)
    print("Run \"code trust <code>\" to approve one.")
end

if ccstudio == nil then
    printError("CC: Studio is not available on this computer.")
    return
end

if command == "open" then
    local ok, info = pcall(ccstudio.open)
    if not ok then
        printError(info)
        return
    end
    printSession(info)
elseif command == "trust" then
    if not ccstudio.status() then
        print("No editor session is open. Run \"code\" to start one.")
    elseif args[2] == nil then
        printPending(ccstudio.pending())
    elseif ccstudio.trust(args[2]) then
        setColour(colours.lime)
        print("Browser approved. It opens the editor in a moment.")
        setColour(colours.white)
    else
        printError("No browser is waiting with that code.")
    end
elseif command == "status" then
    local info = ccstudio.status()
    if info then
        printSession(info)
    else
        print("No editor session is open. Run \"code\" to start one.")
    end
elseif command == "stop" or command == "close" or command == "revoke" then
    if ccstudio.close() then
        print("Editor session closed. All browsers lost access.")
    else
        print("No editor session is open.")
    end
else
    printUsage()
end
