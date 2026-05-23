package github.xitadoptus.macro.engine

import github.xitadoptus.macro.util.ClientUtils
import github.xitadoptus.macro.util.KeyboardUtils
import github.xitadoptus.macro.util.MinecraftInstance
import net.minecraft.block.Block
import net.minecraft.client.Minecraft
import net.minecraft.client.audio.SoundCategory
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.client.settings.GameSettings
import net.minecraft.client.settings.KeyBinding
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.C10PacketCreativeInventoryAction
import net.minecraft.potion.Potion
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumChatFormatting
import net.minecraft.util.IChatComponent
import net.minecraft.util.MathHelper
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.Display
import org.lwjgl.opengl.DisplayMode
import java.io.File
import java.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MacroScriptEngine(
    private val taskId: String,
    private val runningTasks: MutableSet<String>,
    locals: Map<String, String>
) : MinecraftInstance() {

    private val localVariables = locals.mapKeys { it.key.toUpperCase(Locale.ROOT) }.toMutableMap()
    private val random = Random()

    fun execute(rawScript: String) {
        val script = unwrapScript(expandParameters(expandInlineIncludes(rawScript)))
        val statements = parseStatements(script)
        if (statements.isEmpty()) {
            val plain = replaceVars(script.trim())
            if (plain.isNotBlank()) sendChat(plain)
            return
        }

        try {
            executeRange(statements, 0, statements.size)
        } catch (_: StopMacro) {
        }
    }

    private fun executeRange(statements: List<Statement>, start: Int, end: Int): Int {
        var index = start

        while (index < end) {
            if (!runningTasks.contains(taskId)) throw StopMacro()
            val statement = statements[index]

            when (statement.name) {
                "IF", "IFCONTAINS", "IFBEGINSWITH", "IFENDSWITH", "IFMATCHES" -> {
                    index = executeIf(statements, index, end)
                }

                "DO" -> {
                    index = executeDo(statements, index, end)
                }

                "FOR", "FOREACH" -> {
                    index = executeFor(statements, index, end)
                }

                "UNSAFE" -> {
                    index = executeUnsafe(statements, index, end)
                }

                "ELSE", "ELSEIF", "ENDIF", "WHILE", "UNTIL", "LOOP", "NEXT", "ENDUNSAFE" -> return index
                "BREAK" -> throw BreakLoop()
                else -> {
                    executeAction(statement)
                    index++
                }
            }
        }

        return index
    }

    private fun executeIf(statements: List<Statement>, ifIndex: Int, end: Int): Int {
        val branches = mutableListOf<Branch>()
        var depth = 0
        var branchToken = statements[ifIndex]
        var branchStart = ifIndex + 1
        var index = ifIndex + 1
        var endif = end

        loop@ while (index < end) {
            val st = statements[index]
            when (st.name) {
                "IF", "IFCONTAINS", "IFBEGINSWITH", "IFENDSWITH", "IFMATCHES" -> depth++
                "ENDIF" -> {
                    if (depth == 0) {
                        branches += Branch(branchToken, branchStart, index)
                        endif = index
                        break@loop
                    }
                    depth--
                }

                "ELSE", "ELSEIF" -> {
                    if (depth == 0) {
                        branches += Branch(branchToken, branchStart, index)
                        branchToken = st
                        branchStart = index + 1
                    }
                }
            }
            index++
        }

        for (branch in branches) {
            val shouldRun = when (branch.token.name) {
                "ELSE" -> true
                "ELSEIF" -> evalCondition(branch.token.args.getOrNull(0).orEmpty())
                else -> evalIfStatement(branch.token)
            }
            if (shouldRun) {
                executeRange(statements, branch.start, branch.end)
                break
            }
        }

        return if (endif < end) endif + 1 else end
    }

    private fun executeDo(statements: List<Statement>, doIndex: Int, end: Int): Int {
        var depth = 0
        var loopEnd = end
        var loopTerminator: Statement? = null
        var index = doIndex + 1

        loop@ while (index < end) {
            val st = statements[index]
            when (st.name) {
                "DO" -> depth++
                "WHILE", "UNTIL", "LOOP" -> {
                    if (depth == 0) {
                        loopEnd = index
                        loopTerminator = st
                        break@loop
                    }
                    depth--
                }
            }
            index++
        }

        val count = doCount(statements[doIndex])
        var loops = 0
        val maxLoops = if (count > 0) count else 10000

        while (loops < maxLoops) {
            loops++
            try {
                executeRange(statements, doIndex + 1, loopEnd)
            } catch (_: BreakLoop) {
                break
            }

            val terminator = loopTerminator ?: break
            if (terminator.name == "LOOP") {
                continue
            }
            val condition = evalCondition(terminator.args.getOrNull(0).orEmpty())
            if (terminator.name == "WHILE" && !condition) break
            if (terminator.name == "UNTIL" && condition) break
        }

        return if (loopEnd < end) loopEnd + 1 else end
    }

    private fun executeFor(statements: List<Statement>, forIndex: Int, end: Int): Int {
        var depth = 0
        var loopEnd = end
        var index = forIndex + 1

        loop@ while (index < end) {
            val st = statements[index]
            when (st.name) {
                "FOR", "FOREACH" -> depth++
                "NEXT" -> {
                    if (depth == 0) {
                        loopEnd = index
                        break@loop
                    }
                    depth--
                }
            }
            index++
        }

        val statement = statements[forIndex]
        if (statement.name == "FOR") {
            val counter = statement.args.getOrNull(0) ?: return if (loopEnd < end) loopEnd + 1 else end
            val startValue = evalValue(statement.args.getOrNull(1).orEmpty()).toIntOrNull() ?: 0
            val endValue = evalValue(statement.args.getOrNull(2).orEmpty()).toIntOrNull() ?: startValue
            val step = if (startValue <= endValue) 1 else -1
            var value = startValue

            while ((step > 0 && value <= endValue) || (step < 0 && value >= endValue)) {
                setVar(counter, value.toString())
                try {
                    executeRange(statements, forIndex + 1, loopEnd)
                } catch (_: BreakLoop) {
                    break
                }
                value += step
            }
        } else {
            val rows = iteratorRows(statement.args.getOrNull(0).orEmpty())
            for (row in rows) {
                row.forEach { (key, value) -> setLocalVar(key, value) }
                try {
                    executeRange(statements, forIndex + 1, loopEnd)
                } catch (_: BreakLoop) {
                    break
                }
            }
        }

        return if (loopEnd < end) loopEnd + 1 else end
    }

    private fun executeUnsafe(statements: List<Statement>, unsafeIndex: Int, end: Int): Int {
        var depth = 0
        var blockEnd = end
        var index = unsafeIndex + 1

        loop@ while (index < end) {
            when (statements[index].name) {
                "UNSAFE" -> depth++
                "ENDUNSAFE" -> {
                    if (depth == 0) {
                        blockEnd = index
                        break@loop
                    }
                    depth--
                }
            }
            index++
        }

        executeRange(statements, unsafeIndex + 1, blockEnd)
        return if (blockEnd < end) blockEnd + 1 else end
    }

    private fun executeAction(statement: Statement) {
        val args = statement.args.map { replaceVars(unquote(it.trim())) }

        when (statement.name) {
            "ECHO" -> if (args.isNotEmpty()) sendChat(args.joinToString(","))
            "LOG" -> if (args.isNotEmpty()) ClientUtils.displayChatMessage(color(args.joinToString(",")))
            "LOGRAW" -> logRaw(args.joinToString(","))
            "WAIT" -> sleepInterruptibly(parseWait(args.firstOrNull() ?: "1"))
            "EXEC" -> execFile(args)
            "STOP" -> stopMacro(args.firstOrNull())
            "ASSIGN" -> assignVar(statement.args)
            "SET" -> setVar(statement.args.getOrNull(0), args.getOrNull(1) ?: "true")
            "UNSET" -> unsetVar(statement.args.getOrNull(0))
            "TOGGLE" -> toggleVar(statement.args.getOrNull(0) ?: "flag")
            "INC" -> incVar(statement.args.getOrNull(0), args.getOrNull(1)?.toIntOrNull() ?: 1)
            "DEC" -> incVar(statement.args.getOrNull(0), -(args.getOrNull(1)?.toIntOrNull() ?: 1))
            "ARRAYSIZE" -> arraySize(statement.args)
            "PUSH" -> pushArray(statement.args, args)
            "POP" -> popArray(statement.args)
            "PUT" -> putArray(statement.args, args)
            "INDEXOF" -> indexOfArray(statement.args, args)
            "JOIN" -> joinArray(statement.args, args)
            "SPLIT" -> splitArray(statement.args, args)
            "RANDOM" -> randomVar(statement.args.getOrNull(0), args.getOrNull(1), args.getOrNull(2))
            "LCASE" -> transformString(statement.args, args) { it.toLowerCase(Locale.ROOT) }
            "UCASE" -> transformString(statement.args, args) { it.toUpperCase(Locale.ROOT) }
            "ENCODE" -> transformString(statement.args, args) { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
            "DECODE" -> transformString(statement.args, args) {
                runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrDefault("")
            }
            "STRIP" -> setVar(statement.args.getOrNull(0), EnumChatFormatting.getTextWithoutFormattingCodes(args.getOrNull(1).orEmpty()) ?: "")
            "REPLACE" -> replaceVar(statement.args, regex = false)
            "REGEXREPLACE" -> replaceVar(statement.args, regex = true)
            "MATCH" -> matchVar(statement.args)
            "IIF" -> inlineIf(statement.args, args)
            "TIME" -> timeVar(statement.args, args)
            "SQRT" -> setVar(statement.args.getOrNull(1), sqrt((args.firstOrNull()?.toDoubleOrNull() ?: 0.0)).toInt().toString())
            "ABS" -> unaryNumber(statement.args, args) { abs(it) }
            "FLOOR" -> unaryNumber(statement.args, args) { floor(it) }
            "CEIL", "CEILING" -> unaryNumber(statement.args, args) { ceil(it) }
            "ROUND" -> unaryNumber(statement.args, args) { it.roundToInt().toDouble() }
            "MIN" -> minMaxNumber(statement.args, args, chooseMax = false)
            "MAX" -> minMaxNumber(statement.args, args, chooseMax = true)
            "CLAMP" -> clampNumber(statement.args, args)
            "LEN", "LENGTH" -> setVar(statement.args.getOrNull(1), args.firstOrNull().orEmpty().length.toString())
            "TRIM" -> transformString(statement.args, args) { it.trim() }
            "SUBSTR", "SUBSTRING" -> substringVar(statement.args, args)
            "CONTAINS" -> stringTest(statement.args, args) { value, search, ignoreCase -> value.contains(search, ignoreCase) }
            "BEGINSWITH", "STARTSWITH" -> stringTest(statement.args, args) { value, search, ignoreCase -> value.startsWith(search, ignoreCase) }
            "ENDSWITH" -> stringTest(statement.args, args) { value, search, ignoreCase -> value.endsWith(search, ignoreCase) }
            "ITEMID" -> itemId(statement.args, args)
            "ITEMNAME" -> itemName(statement.args, args)
            "GETITEMINFO" -> getItemInfo(statement.args, args)
            "TRACE" -> trace(args)
            "KEY" -> key(args.firstOrNull(), pulse = true)
            "KEYDOWN" -> key(args.firstOrNull(), down = true)
            "KEYUP" -> key(args.firstOrNull(), down = false)
            "TOGGLEKEY" -> toggleKey(args.firstOrNull())
            "PRESS" -> press(args.firstOrNull())
            "TYPE" -> typeText(args.joinToString(","))
            "SPRINT" -> schedule { mc.thePlayer?.isSprinting = true }
            "UNSPRINT" -> schedule { mc.thePlayer?.isSprinting = false }
            "SLOT" -> slot(args.firstOrNull())
            "INVENTORYUP" -> scrollSlot(-(args.firstOrNull()?.toIntOrNull() ?: 1))
            "INVENTORYDOWN" -> scrollSlot(args.firstOrNull()?.toIntOrNull() ?: 1)
            "PICK" -> pick(args)
            "GETSLOT" -> getSlot(statement.args, args)
            "GETSLOTITEM" -> getSlotItem(statement.args, args)
            "SLOTCLICK" -> slotClick(args)
            "SLOTCLICKITEM", "CLICKITEM" -> slotClickItem(args)
            "SETSLOTITEM" -> setSlotItem(args)
            "CLEARCRAFTING" -> Unit
            "CRAFT", "CRAFTANDWAIT" -> craftFallback(args, wait = statement.name == "CRAFTANDWAIT")
            "GETID" -> getId(statement.args, args, relative = false)
            "GETIDREL" -> getId(statement.args, args, relative = true)
            "GETBLOCKINFO" -> getBlockInfo(statement.args, args, relative = false)
            "GETBLOCKINFOREL" -> getBlockInfo(statement.args, args, relative = true)
            "CALCYAWTO" -> calcYawTo(statement.args, args)
            "CALCPITCHTO" -> calcPitchTo(statement.args, args)
            "CALCAIMTO" -> calcAimTo(statement.args, args)
            "DISTANCE" -> distanceTo(statement.args, args)
            "LOOK" -> look(args, smooth = false)
            "LOOKS" -> look(args, smooth = true)
            "AIMTO" -> aimTo(args)
            "FOV" -> setOption(args.firstOrNull(), "FOV")
            "GAMMA" -> setOption(args.firstOrNull(), "GAMMA")
            "SENSITIVITY" -> setOption(args.firstOrNull(), "SENSITIVITY")
            "VOLUME", "MUSIC" -> setVolume(args)
            "CHATHEIGHT" -> setFloatGameSetting("chatHeightUnfocused", args.firstOrNull())
            "CHATHEIGHTFOCUSED" -> setFloatGameSetting("chatHeightFocused", args.firstOrNull())
            "CHATOPACITY" -> setFloatGameSetting("chatOpacity", args.firstOrNull())
            "CHATSCALE" -> setFloatGameSetting("chatScale", args.firstOrNull())
            "CHATWIDTH" -> setFloatGameSetting("chatWidth", args.firstOrNull())
            "CHATVISIBLE" -> chatVisible(args.firstOrNull())
            "FOG" -> fog(args.firstOrNull())
            "RELOADRESOURCES" -> schedule { mc.refreshResources() }
            "RESOURCEPACKS" -> resourcePacks(args)
            "SETRES" -> setResolution(args)
            "SHADERGROUP" -> shaderGroup(args.firstOrNull())
            "BIND" -> bindKey(args)
            "CAMERA" -> camera(args.firstOrNull())
            "CLEARCHAT" -> schedule { mc.ingameGUI?.chatGUI?.clearChatMessages() }
            "TITLE" -> title(args)
            "POPUPMESSAGE", "TOAST" -> if (args.isNotEmpty()) ClientUtils.displayChatMessage(color(args.joinToString(" ")))
            "GUI" -> gui(args.firstOrNull())
            "SHOWGUI" -> showGui(args.firstOrNull())
            "BINDGUI" -> bindGui(args)
            "DISCONNECT" -> schedule { mc.theWorld?.sendQuittingDisconnectingPacket(); mc.loadWorld(null) }
            "RESPAWN" -> schedule { mc.thePlayer?.respawnPlayer() }
            "PLACESIGN" -> placeSign(args)
            "PLAYSOUND" -> playSound(args)
            "ISRUNNING" -> setVar(statement.args.getOrNull(1), MacroRuntime.isRunning(args.firstOrNull().orEmpty()).toString())
            "CONFIG" -> config(args.firstOrNull())
            "IMPORT" -> MacroRuntime.stores.computeIfAbsent("IMPORTS") { mutableListOf() }.add(args.firstOrNull().orEmpty())
            "UNIMPORT" -> MacroRuntime.stores["IMPORTS"]?.remove(args.firstOrNull().orEmpty())
            "LOGTO" -> logTo(args)
            "PROMPT" -> prompt(statement.args, args)
            "STORE" -> store(args, overwrite = false)
            "STOREOVER" -> store(args, overwrite = true)
            "GETPROPERTY" -> getProperty(statement.args, args)
            "SETPROPERTY" -> setProperty(args)
            "SETLABEL" -> setLabel(args)
            "CHATFILTER", "FILTER", "PASS", "MODIFY", "REPL", "UNSAFE" -> Unit
            else -> executeUnknown(statement)
        }
    }

    private fun executeUnknown(statement: Statement) {
        val raw = replaceVars(statement.raw.trim())
        if (raw.isBlank()) return
        if (raw.startsWith("/")) {
            sendChat(raw)
        } else {
            ClientUtils.displayChatMessage("§7[MacroEngine] Unsupported action §f${statement.name}§7.")
        }
    }

    private fun evalIfStatement(statement: Statement): Boolean {
        val args = statement.args.map { replaceVars(unquote(it.trim())) }
        return when (statement.name) {
            "IFCONTAINS" -> args.size >= 2 && args[0].contains(args[1])
            "IFBEGINSWITH" -> args.size >= 2 && args[0].startsWith(args[1])
            "IFENDSWITH" -> args.size >= 2 && args[0].endsWith(args[1])
            "IFMATCHES" -> {
                if (args.size < 2) return false
                val match = runCatching { Regex(args[1]).find(args[0]) }.getOrNull() ?: return false
                val target = statement.args.getOrNull(2)
                val group = args.getOrNull(3)?.toIntOrNull() ?: 0
                val groupValue = if (group >= 0 && group < match.groups.size) match.groups[group]?.value else null
                if (target != null) setVar(target, groupValue ?: match.value)
                true
            }
            else -> evalCondition(statement.args.getOrNull(0).orEmpty())
        }
    }

    private fun evalCondition(input: String): Boolean {
        val raw = input.trim()
        if (raw.startsWith("!") && !raw.startsWith("!=")) return !evalCondition(raw.substring(1))
        if (isWrapped(raw, '(', ')')) return evalCondition(raw.substring(1, raw.length - 1))
        val quoted = raw.length >= 2 &&
            ((raw.first() == '"' && raw.last() == '"') || (raw.first() == '\'' && raw.last() == '\''))
        val expr = replaceVars(unquote(raw)).trim()
        if (expr.isBlank()) return false

        splitBoolean(expr, "||")?.let { return it.any { part -> evalCondition(part) } }
        splitBoolean(expr, "&&")?.let { return it.all { part -> evalCondition(part) } }

        val ops = listOf("<=", ">=", "!=", "==", "=", "<", ">")
        for (op in ops) {
            val pair = splitOperator(expr, op) ?: continue
            val left = evalValue(pair.first)
            val right = evalValue(pair.second)
            val lNum = left.toDoubleOrNull()
            val rNum = right.toDoubleOrNull()

            if (lNum != null && rNum != null) {
                return when (op) {
                    "<=" -> lNum <= rNum
                    ">=" -> lNum >= rNum
                    "!=" -> lNum != rNum
                    "=", "==" -> lNum == rNum
                    "<" -> lNum < rNum
                    ">" -> lNum > rNum
                    else -> false
                }
            }

            return when (op) {
                "!=" -> !left.equals(right, ignoreCase = true)
                "=", "==" -> left.equals(right, ignoreCase = true)
                "<" -> left < right
                ">" -> left > right
                "<=" -> left <= right
                ">=" -> left >= right
                else -> false
            }
        }

        readVar(expr)?.let { return truthy(it) }
        if (quoted) return truthy(expr)

        val evaluated = evalValue(expr)
        if (evaluated != expr) return truthy(evaluated)

        if (expr.equals("true", ignoreCase = true) || expr.equals("on", ignoreCase = true) || expr.equals("yes", ignoreCase = true)) {
            return true
        }
        if (expr.equals("false", ignoreCase = true) || expr.equals("off", ignoreCase = true) || expr.equals("no", ignoreCase = true)) {
            return false
        }

        expr.toDoubleOrNull()?.let { return it != 0.0 }

        return false
    }

    private fun parseStatements(script: String): List<Statement> {
        val result = mutableListOf<Statement>()
        for (part in splitStatements(script)) {
            val raw = part.trim()
            if (raw.isBlank()) continue

            val assignment = directAssignment(raw)
            if (assignment != null) {
                result += Statement("ASSIGN", listOf(assignment.first, assignment.second), raw)
                continue
            }

            val match = ACTION_PATTERN.matcher(raw)
            if (match.matches()) {
                val name = match.group(1).toUpperCase(Locale.ROOT)
                val args = splitArgs(match.group(2) ?: "")
                result += Statement(name, args, raw)
            } else {
                result += Statement(raw.substringBefore(' ').toUpperCase(Locale.ROOT), emptyList(), raw)
            }
        }
        return result
    }

    private fun splitStatements(input: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var depth = 0
        var escape = false

        fun flush() {
            val value = current.toString().trim()
            if (value.isNotBlank()) out += value
            current.setLength(0)
        }

        for (c in input) {
            if (escape) {
                current.append(c)
                escape = false
                continue
            }
            if (c == '\\') {
                current.append(c)
                escape = true
                continue
            }
            if (quote != null) {
                current.append(c)
                if (c == quote) quote = null
                continue
            }

            when (c) {
                '"', '\'' -> {
                    quote = c
                    current.append(c)
                }
                '(', '[', '{' -> {
                    depth++
                    current.append(c)
                }
                ')', ']', '}' -> {
                    if (depth > 0) depth--
                    current.append(c)
                }
                ';', '\n', '\r' -> {
                    if (depth == 0) flush() else current.append(c)
                }
                else -> current.append(c)
            }
        }

        flush()
        return out
    }

    private fun splitArgs(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        return splitTopLevel(input, ',').map { it.trim() }
    }

    private fun splitTopLevel(input: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var depth = 0
        var escape = false

        for (c in input) {
            if (escape) {
                current.append(c)
                escape = false
                continue
            }
            if (c == '\\') {
                current.append(c)
                escape = true
                continue
            }
            if (quote != null) {
                current.append(c)
                if (c == quote) quote = null
                continue
            }
            when (c) {
                '"', '\'' -> {
                    quote = c
                    current.append(c)
                }
                '(', '[', '{' -> {
                    depth++
                    current.append(c)
                }
                ')', ']', '}' -> {
                    if (depth > 0) depth--
                    current.append(c)
                }
                delimiter -> {
                    if (depth == 0) {
                        out += current.toString()
                        current.setLength(0)
                    } else {
                        current.append(c)
                    }
                }
                else -> current.append(c)
            }
        }

        out += current.toString()
        return out
    }

    private fun replaceVars(text: String): String {
        val out = StringBuilder()
        var index = 0

        while (index < text.length) {
            val c = text[index]
            if (c == '%') {
                val end = findVariableEnd(text, index + 1)
                if (end > index) {
                    val rawName = text.substring(index + 1, end)
                    out.append(resolveVariable(replaceVars(rawName)))
                    index = end + 1
                    continue
                }
            }

            out.append(c)
            index++
        }

        return out.toString()
    }

    private fun resolveVariable(name: String): String {
        val key = name.toUpperCase(Locale.ROOT)
        if (key == "RESOURCEPACKS[]") return resourcePackList().joinToString(",")
        if (key == "SHADERGROUPS[]") return ""
        resolveArrayRead(name)?.let { return it }
        readVar(name)?.let { return it }
        localVariables[key]?.let { return it }

        val player = mc.thePlayer
        val world = mc.theWorld

        return when {
            key.startsWith("KEY_") -> KeyboardUtils.isInputPressed(key.removePrefix("KEY_")).toString()
            key.startsWith("~KEY_") -> KeyboardUtils.isInputPressed(key.removePrefix("~KEY_")).toString()
            key == "~ALT" -> (KeyboardUtils.isInputPressed("LALT") || KeyboardUtils.isInputPressed("RALT")).toString()
            key == "~CTRL" -> (KeyboardUtils.isInputPressed("LCTRL") || KeyboardUtils.isInputPressed("RCTRL")).toString()
            key == "~SHIFT" -> (KeyboardUtils.isInputPressed("LSHIFT") || KeyboardUtils.isInputPressed("RSHIFT")).toString()
            key == "~LMOUSE" -> KeyboardUtils.isInputPressed("MOUSE1").toString()
            key == "~RMOUSE" -> KeyboardUtils.isInputPressed("MOUSE2").toString()
            key == "~MIDDLEMOUSE" -> KeyboardUtils.isInputPressed("MOUSE3").toString()
            key == "PLAYER" -> player?.name ?: ""
            key == "DISPLAYNAME" -> player?.displayName?.unformattedText ?: player?.name ?: ""
            key == "UUID" -> player?.uniqueID?.toString() ?: ""
            key == "HEALTH" -> (player?.health ?: 0f).toInt().toString()
            key == "HUNGER" -> (player?.foodStats?.foodLevel ?: 0).toString()
            key == "SATURATION" -> (player?.foodStats?.saturationLevel ?: 0f).toString()
            key == "ARMOUR" || key == "ARMOR" -> (player?.totalArmorValue ?: 0).toString()
            key == "LEVEL" -> (player?.experienceLevel ?: 0).toString()
            key == "XP" -> (player?.experience ?: 0f).toString()
            key == "TOTALXP" -> (player?.experienceTotal ?: 0).toString()
            key == "LIGHT" -> player?.let { world?.getLight(BlockPos(it.posX, it.posY, it.posZ))?.toString() } ?: "0"
            key == "XPOS" -> (player?.posX ?: 0.0).toInt().toString()
            key == "YPOS" -> (player?.posY ?: 0.0).toInt().toString()
            key == "ZPOS" -> (player?.posZ ?: 0.0).toInt().toString()
            key == "XPOSF" -> "%.3f".format(Locale.ROOT, player?.posX ?: 0.0)
            key == "YPOSF" -> "%.3f".format(Locale.ROOT, player?.posY ?: 0.0)
            key == "ZPOSF" -> "%.3f".format(Locale.ROOT, player?.posZ ?: 0.0)
            key == "MOTIONX" -> (player?.motionX ?: 0.0).toString()
            key == "MOTIONY" -> (player?.motionY ?: 0.0).toString()
            key == "MOTIONZ" -> (player?.motionZ ?: 0.0).toString()
            key == "SPEED" -> player?.let { sqrt(it.motionX * it.motionX + it.motionZ * it.motionZ).toString() } ?: "0"
            key == "YAW" -> (player?.rotationYaw ?: 0f).toString()
            key == "CARDINALYAW" -> ((player?.rotationYaw ?: 0f) + 180f).toString()
            key == "PITCH" -> (player?.rotationPitch ?: 0f).toString()
            key == "DIRECTION" -> player?.horizontalFacing?.name?.take(1) ?: ""
            key == "MODE" -> (player?.capabilities?.let { if (it.isCreativeMode) 1 else 0 } ?: 0).toString()
            key == "GAMEMODE" -> if (player?.capabilities?.isCreativeMode == true) "Creative" else "Survival"
            key == "CANFLY" -> (player?.capabilities?.allowFlying ?: false).toString()
            key == "FLYING" -> (player?.capabilities?.isFlying ?: false).toString()
            key == "ONGROUND" -> (player?.onGround ?: false).toString()
            key == "INWATER" -> (player?.isInWater ?: false).toString()
            key == "ISBURNING" -> (player?.isBurning ?: false).toString()
            key == "ISRIDING" -> (player?.isRiding ?: false).toString()
            key == "OXYGEN" -> (player?.air ?: 0).toString()
            key == "VEHICLE" -> player?.ridingEntity?.name ?: ""
            key == "VEHICLEHEALTH" -> ((player?.ridingEntity as? net.minecraft.entity.EntityLivingBase)?.health ?: 0f).toString()
            key == "ITEM" -> heldItem()?.let { Item.getIdFromItem(it.item).toString() } ?: "0"
            key == "ITEMCODE" -> heldItem()?.item?.let { Item.itemRegistry.getNameForObject(it)?.toString() } ?: ""
            key == "ITEMIDDMG" -> heldItem()?.let { "${Item.getIdFromItem(it.item)}:${it.itemDamage}" } ?: "0:0"
            key == "ITEMNAME" -> heldItem()?.displayName ?: ""
            key == "ITEMDAMAGE" -> heldItem()?.maxDamage?.toString() ?: "0"
            key == "DURABILITY" -> heldItem()?.let { (it.maxDamage - it.itemDamage).toString() } ?: "0"
            key == "ITEMMAXDAMAGE" || key == "MAXDAMAGE" -> heldItem()?.maxDamage?.toString() ?: "0"
            key == "DURABILITYPCT" || key == "ITEMDURABILITYPCT" -> heldItem()?.let { durabilityPercent(it).toString() } ?: "0"
            key == "STACKSIZE" -> heldItem()?.stackSize?.toString() ?: "0"
            key == "ATTACKPOWER" -> "1"
            key == "ATTACKSPEED" -> "1"
            key == "BOWCHARGE" -> player?.itemInUseDuration?.toString() ?: "0"
            key == "COOLDOWN" -> "0"
            key == "ITEMUSEPCT" -> itemUsePercent().toString()
            key == "ITEMUSETICKS" -> player?.itemInUseDuration?.toString() ?: "0"
            key.startsWith("OFFHAND") -> offhandVar(key)
            key == "INVSLOT" -> ((player?.inventory?.currentItem ?: 0) + 1).toString()
            key == "GUI" -> mc.currentScreen?.javaClass?.simpleName ?: ""
            key == "CONTAINERSLOTS" -> (player?.openContainer?.inventorySlots?.size ?: 0).toString()
            key == "DISPLAYWIDTH" -> mc.displayWidth.toString()
            key == "DISPLAYHEIGHT" -> mc.displayHeight.toString()
            key == "SERVER" -> mc.currentServerData?.serverIP ?: "Singleplayer"
            key == "SERVERNAME" -> mc.currentServerData?.serverName ?: world?.worldInfo?.worldName ?: "Singleplayer"
            key == "SERVERMOTD" -> mc.currentServerData?.serverMOTD ?: ""
            key == "ONLINEPLAYERS" -> (player?.sendQueue?.playerInfoMap?.size ?: 0).toString()
            key == "MAXPLAYERS" -> "0"
            key == "FPS" -> Minecraft.getDebugFPS().toString()
            key == "GAMMA" -> mc.gameSettings.gammaSetting.toString()
            key == "FOV" -> mc.gameSettings.fovSetting.toString()
            key == "SENSITIVITY" -> mc.gameSettings.mouseSensitivity.toString()
            key == "CAMERA" -> mc.gameSettings.thirdPersonView.toString()
            key == "SOUND" -> mc.gameSettings.getSoundLevel(SoundCategory.MASTER).toString()
            key == "MUSIC" -> mc.gameSettings.getSoundLevel(SoundCategory.MUSIC).toString()
            key == "RECORDVOLUME" -> mc.gameSettings.getSoundLevel(SoundCategory.RECORDS).toString()
            key == "WEATHERVOLUME" -> mc.gameSettings.getSoundLevel(SoundCategory.WEATHER).toString()
            key == "BLOCKVOLUME" -> mc.gameSettings.getSoundLevel(SoundCategory.BLOCKS).toString()
            key == "HOSTILEVOLUME" -> mc.gameSettings.getSoundLevel(SoundCategory.MOBS).toString()
            key == "NEUTRALVOLUME" -> mc.gameSettings.getSoundLevel(SoundCategory.ANIMALS).toString()
            key == "PLAYERVOLUME" -> mc.gameSettings.getSoundLevel(SoundCategory.PLAYERS).toString()
            key == "AMBIENTVOLUME" -> mc.gameSettings.getSoundLevel(SoundCategory.AMBIENT).toString()
            key == "DATE" -> SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            key == "TIME" -> SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
            key == "DATETIME" -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            key == "TIMESTAMP" -> (System.currentTimeMillis() / 1000L).toString()
            key == "BIOME" -> player?.let { world?.getBiomeGenForCoords(BlockPos(it.posX, it.posY, it.posZ))?.biomeName } ?: ""
            key == "DIMENSION" -> (player?.dimension ?: 0).toString()
            key == "DIFFICULTY" -> world?.difficulty?.name ?: ""
            key == "LOCALDIFFICULTY" -> player?.let { world?.getDifficultyForLocation(BlockPos(it.posX, it.posY, it.posZ))?.additionalDifficulty?.toString() } ?: "0"
            key == "POTIONCOUNT" -> (player?.activePotionEffects?.size ?: 0).toString()
            key == "RAIN" -> (world?.getRainStrength(1f) ?: 0f).toString()
            key == "TICKS" -> (world?.worldTime ?: 0L).toString()
            key == "TOTALTICKS" -> (world?.totalWorldTime ?: 0L).toString()
            key == "DAY" -> ((world?.totalWorldTime ?: 0L) / 24000L).toString()
            key == "DAYTICKS" -> ((world?.worldTime ?: 0L) % 24000L).toString()
            key == "DAYTIME" -> dayTime(world?.worldTime ?: 0L)
            key == "SEED" -> runCatching { world?.seed?.toString() }.getOrNull() ?: ""
            key == "CHUNKUPDATES" -> "0"
            key == "CONFIG" -> MacroRuntime.currentConfigName
            key == "SCREEN" -> MacroRuntime.guiProperties["SCREEN"] ?: ""
            key == "SCREENNAME" -> MacroRuntime.guiProperties["SCREENNAME"] ?: ""
            key == "SHADERGROUP" -> MacroRuntime.guiProperties["SHADERGROUP"] ?: ""
            key == "UNIQUEID" -> java.util.UUID.randomUUID().toString()
            key == "LMOUSE" -> KeyboardUtils.isInputPressed("MOUSE1").toString()
            key == "RMOUSE" -> KeyboardUtils.isInputPressed("MOUSE2").toString()
            key == "MIDDLEMOUSE" -> KeyboardUtils.isInputPressed("MOUSE3").toString()
            key == "SHIFT" -> (KeyboardUtils.isInputPressed("LSHIFT") || KeyboardUtils.isInputPressed("RSHIFT")).toString()
            key == "CTRL" -> (KeyboardUtils.isInputPressed("LCTRL") || KeyboardUtils.isInputPressed("RCTRL")).toString()
            key == "ALT" -> (KeyboardUtils.isInputPressed("LALT") || KeyboardUtils.isInputPressed("RALT")).toString()
            else -> armorVar(key) ?: hitVar(key) ?: signTextVar(key) ?: ""
        }
    }

    private fun readVar(name: String): String? {
        resolveArrayRead(name)?.let { return it }
        val key = cleanVarName(name)
        return MacroRuntime.variables[key] ?: localVariables[key]
    }

    private fun setVar(name: String?, value: String) {
        val raw = name ?: return
        if (setArrayValue(raw, value)) return
        val key = cleanVarName(raw)
        if (key.isBlank()) return
        MacroRuntime.variables[key] = value
    }

    private fun setLocalVar(name: String, value: String) {
        val key = cleanVarName(name)
        if (key.isNotBlank()) localVariables[key] = value
    }

    private fun assignVar(rawArgs: List<String>) {
        val target = rawArgs.getOrNull(0) ?: return
        val value = rawArgs.getOrNull(1) ?: return
        setVar(target, evalValue(value))
    }

    private fun unsetVar(name: String?) {
        val raw = name ?: return
        val arrayRef = parseArrayRef(raw)
        if (arrayRef != null) {
            val (arrayName, index) = arrayRef
            if (index == null) {
                MacroRuntime.arrays.remove(arrayName)
            } else {
                MacroRuntime.arrays[arrayName]?.let { if (index in it.indices) it.removeAt(index) }
            }
            return
        }

        val key = cleanVarName(raw)
        MacroRuntime.variables.remove(key)
        if (key == "MACRO") {
            runningTasks.removeIf { it != taskId }
        }
    }

    private fun toggleVar(name: String) {
        val key = cleanVarName(name)
        MacroRuntime.variables[key] = (!truthy(MacroRuntime.variables[key])).toString()
    }

    private fun incVar(name: String?, amount: Int) {
        val raw = name ?: return
        val current = readVar(raw)?.toIntOrNull() ?: 0
        setVar(raw, (current + amount).toString())
    }

    private fun randomVar(target: String?, maxArg: String?, minArg: String?) {
        val raw = target ?: return
        val max = maxArg?.toIntOrNull() ?: 100
        val min = minArg?.toIntOrNull() ?: 0
        val low = min.coerceAtMost(max)
        val high = min.coerceAtLeast(max)
        setVar(raw, (low + random.nextInt(high - low + 1)).toString())
    }

    private fun replaceVar(rawArgs: List<String>, regex: Boolean) {
        val target = rawArgs.getOrNull(0) ?: return
        val subject = readVar(target) ?: replaceVars(target)
        val search = replaceVars(unquote(rawArgs.getOrNull(1).orEmpty()))
        val replacement = replaceVars(unquote(rawArgs.getOrNull(2).orEmpty()))
        val result = if (regex) subject.replace(Regex(search), replacement) else subject.replace(search, replacement)
        setVar(target, result)
    }

    private fun matchVar(rawArgs: List<String>) {
        val subject = replaceVars(unquote(rawArgs.getOrNull(0).orEmpty()))
        val pattern = replaceVars(unquote(rawArgs.getOrNull(1).orEmpty()))
        val target = rawArgs.getOrNull(2)
        val group = replaceVars(rawArgs.getOrNull(3).orEmpty()).toIntOrNull() ?: 0
        val default = replaceVars(unquote(rawArgs.getOrNull(4).orEmpty()))
        val match = runCatching { Regex(pattern).find(subject) }.getOrNull()
        val groupValue = match?.let {
            if (group >= 0 && group < it.groups.size) it.groups[group]?.value else null
        }
        setVar(target, groupValue ?: default)
    }

    private fun timeVar(rawArgs: List<String>, args: List<String>) {
        val target = rawArgs.getOrNull(0) ?: return
        val format = args.getOrNull(1) ?: "HH:mm:ss"
        setVar(target, runCatching { SimpleDateFormat(format, Locale.ROOT).format(Date()) }.getOrDefault(resolveVariable("TIME")))
    }

    private fun unaryNumber(rawArgs: List<String>, args: List<String>, transform: (Double) -> Double) {
        val value = args.firstOrNull()?.toDoubleOrNull() ?: return
        setVar(rawArgs.getOrNull(1), formatNumber(transform(value)))
    }

    private fun minMaxNumber(rawArgs: List<String>, args: List<String>, chooseMax: Boolean) {
        setVar(rawArgs.lastOrNull(), minMaxValue(args.dropLast(1), chooseMax) ?: return)
    }

    private fun clampNumber(rawArgs: List<String>, args: List<String>) {
        val value = args.getOrNull(0)?.toDoubleOrNull() ?: return
        val min = args.getOrNull(1)?.toDoubleOrNull() ?: return
        val max = args.getOrNull(2)?.toDoubleOrNull() ?: return
        val low = min.coerceAtMost(max)
        val high = min.coerceAtLeast(max)
        setVar(rawArgs.getOrNull(3), formatNumber(value.coerceIn(low, high)))
    }

    private fun substringVar(rawArgs: List<String>, args: List<String>) {
        setVar(rawArgs.getOrNull(3), substringValue(args))
    }

    private fun stringTest(rawArgs: List<String>, args: List<String>, test: (String, String, Boolean) -> Boolean) {
        val value = args.getOrNull(0).orEmpty()
        val search = args.getOrNull(1).orEmpty()
        val ignoreCase = truthy(args.getOrNull(3) ?: "false")
        setVar(rawArgs.getOrNull(2), test(value, search, ignoreCase).toString())
    }

    private fun minMaxValue(args: List<String>, chooseMax: Boolean): String? {
        val numbers = args.mapNotNull { it.toDoubleOrNull() }
        if (numbers.isEmpty()) return null
        var result = numbers.first()
        numbers.drop(1).forEach { number ->
            if ((chooseMax && number > result) || (!chooseMax && number < result)) {
                result = number
            }
        }
        return formatNumber(result)
    }

    private fun substringValue(args: List<String>): String {
        val value = args.firstOrNull().orEmpty()
        val start = (args.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, value.length)
        val requestedLength = args.getOrNull(2)?.toIntOrNull()
        val end = if (requestedLength == null) value.length else (start + requestedLength.coerceAtLeast(0)).coerceAtMost(value.length)
        return value.substring(start, end)
    }

    private fun stopMacro(name: String?) {
        val target = name?.trim().orEmpty()
        if (target.isBlank()) throw StopMacro()
        MacroRuntime.stopMatching(target)
        if (taskId.startsWith(target, ignoreCase = true) || taskId.contains(target, ignoreCase = true)) {
            throw StopMacro()
        }
    }

    private fun transformString(rawArgs: List<String>, args: List<String>, transform: (String) -> String) {
        val inputRaw = rawArgs.getOrNull(0) ?: return
        val input = args.firstOrNull().orEmpty()
        val target = rawArgs.getOrNull(1) ?: inputRaw.takeIf { isVariableRef(it) }
        val result = transform(input)
        if (target != null) {
            setVar(target, result)
        } else {
            ClientUtils.displayChatMessage(color(result))
        }
    }

    private fun inlineIf(rawArgs: List<String>, args: List<String>) {
        val condition = rawArgs.getOrNull(0) ?: return
        val result = if (evalCondition(condition)) args.getOrNull(1).orEmpty() else args.getOrNull(2).orEmpty()
        val target = rawArgs.getOrNull(3)
        if (target != null) setVar(target, result) else if (result.isNotBlank()) ClientUtils.displayChatMessage(color(result))
    }

    private fun arraySize(rawArgs: List<String>) {
        val array = parseArrayRef(rawArgs.getOrNull(0).orEmpty())?.first ?: return
        setVar(rawArgs.getOrNull(1), (MacroRuntime.arrays[array]?.size ?: 0).toString())
    }

    private fun pushArray(rawArgs: List<String>, args: List<String>) {
        val array = mutableArray(rawArgs.getOrNull(0) ?: return)
        array += args.getOrNull(1).orEmpty()
    }

    private fun popArray(rawArgs: List<String>) {
        val array = mutableArray(rawArgs.getOrNull(0) ?: return)
        val value = if (array.isNotEmpty()) array.removeAt(array.lastIndex) else ""
        setVar(rawArgs.getOrNull(1), value)
    }

    private fun putArray(rawArgs: List<String>, args: List<String>) {
        val array = mutableArray(rawArgs.getOrNull(0) ?: return)
        val value = args.getOrNull(1).orEmpty()
        val emptyIndex = array.indexOfFirst { it.isEmpty() }
        if (emptyIndex >= 0) array[emptyIndex] = value else array += value
    }

    private fun indexOfArray(rawArgs: List<String>, args: List<String>) {
        val array = mutableArray(rawArgs.getOrNull(0) ?: return)
        val target = rawArgs.getOrNull(1)
        val search = args.getOrNull(2).orEmpty()
        val caseSensitive = truthy(args.getOrNull(3) ?: "true")
        val index = array.indexOfFirst {
            if (caseSensitive) it == search else it.equals(search, ignoreCase = true)
        }
        setVar(target, index.toString())
    }

    private fun joinArray(rawArgs: List<String>, args: List<String>) {
        val glue = args.firstOrNull().orEmpty()
        val array = mutableArray(rawArgs.getOrNull(1) ?: return)
        val result = array.joinToString(glue)
        val target = rawArgs.getOrNull(2)
        if (target != null) setVar(target, result) else ClientUtils.displayChatMessage(color(result))
    }

    private fun splitArray(rawArgs: List<String>, args: List<String>) {
        val delimiter = args.firstOrNull().orEmpty()
        val source = args.getOrNull(1).orEmpty()
        val target = rawArgs.getOrNull(2) ?: return
        val array = mutableArray(target)
        array.clear()
        if (delimiter.isEmpty()) {
            source.forEach { array += it.toString() }
        } else {
            array += source.split(delimiter)
        }
    }

    private fun itemId(rawArgs: List<String>, args: List<String>) {
        val raw = args.firstOrNull().orEmpty()
        val item = itemFromDescriptor(raw)
        val id = item?.let { Item.getIdFromItem(it).toString() } ?: parseItemId(raw)?.toString().orEmpty()
        val target = rawArgs.getOrNull(1)
        if (target != null) setVar(target, id) else if (id.isNotBlank()) ClientUtils.displayChatMessage(id)
    }

    private fun itemName(rawArgs: List<String>, args: List<String>) {
        val id = parseItemId(args.firstOrNull().orEmpty()) ?: return
        val item = Item.getItemById(id) ?: return
        val descriptor = Item.itemRegistry.getNameForObject(item)?.toString() ?: item.unlocalizedName
        val target = rawArgs.getOrNull(1)
        if (target != null) setVar(target, descriptor) else ClientUtils.displayChatMessage(descriptor)
    }

    private fun getItemInfo(rawArgs: List<String>, args: List<String>) {
        val item = itemFromDescriptor(args.firstOrNull().orEmpty()) ?: return
        val stack = ItemStack(item, 1, parseItemDamage(args.firstOrNull().orEmpty()))
        setVar(rawArgs.getOrNull(1), stack.displayName ?: item.unlocalizedName)
        setVar(rawArgs.getOrNull(2), itemStackLimit(item).toString())
        setVar(rawArgs.getOrNull(3), item.javaClass.simpleName)
        setVar(rawArgs.getOrNull(4), "${Item.getIdFromItem(item)}:${stack.itemDamage}")
    }

    private fun trace(args: List<String>) {
        val distance = args.firstOrNull()?.toDoubleOrNull() ?: mc.playerController.blockReachDistance.toDouble()
        val hit = mc.thePlayer?.rayTrace(distance, 1f) ?: mc.objectMouseOver
        val world = mc.theWorld
        val pos = hit?.blockPos
        setVar("HIT", hit?.typeOfHit?.name ?: "")
        setVar("HITX", pos?.x?.toString() ?: "")
        setVar("HITY", pos?.y?.toString() ?: "")
        setVar("HITZ", pos?.z?.toString() ?: "")
        setVar("HITSIDE", hit?.sideHit?.name ?: "")
        setVar("HITID", pos?.let { Block.getIdFromBlock(world?.getBlockState(it)?.block ?: Blocks.air).toString() } ?: "0")
        setVar("HITDATA", pos?.let { blockPos ->
            world?.getBlockState(blockPos)?.let { state -> state.block.getMetaFromState(state).toString() }
        } ?: "0")
        setVar("HITNAME", pos?.let { world?.getBlockState(it)?.block?.localizedName ?: "" } ?: "")
        setVar("HITUUID", hit?.entityHit?.uniqueID?.toString() ?: "")
    }

    private fun logRaw(json: String) {
        if (json.isBlank()) return
        runCatching {
            val component = IChatComponent.Serializer.jsonToComponent(json)
            schedule { mc.thePlayer?.addChatMessage(component) }
        }.onFailure {
            ClientUtils.displayChatMessage(json)
        }
    }

    private fun evalValue(raw: String): String {
        val value = replaceVars(unquote(raw.trim())).trim()
        if (value.isBlank()) return ""
        readVar(value)?.let { return it }
        evalFunctionValue(value)?.let { return it }
        evalNumericExpression(value)?.let { return formatNumber(it) }

        val player = mc.thePlayer
        return when (value.toLowerCase(Locale.ROOT)) {
            "player().posx", "player.posx" -> (player?.posX ?: 0.0).toString()
            "player().posy", "player.posy" -> (player?.posY ?: 0.0).toString()
            "player().posz", "player.posz" -> (player?.posZ ?: 0.0).toString()
            "player().motionx", "player.motionx" -> (player?.motionX ?: 0.0).toString()
            "player().motiony", "player.motiony" -> (player?.motionY ?: 0.0).toString()
            "player().motionz", "player.motionz" -> (player?.motionZ ?: 0.0).toString()
            "player().yaw", "player.yaw", "player().rotationyaw", "player.rotationyaw" -> (player?.rotationYaw ?: 0f).toString()
            "player().pitch", "player.pitch", "player().rotationpitch", "player.rotationpitch" -> (player?.rotationPitch ?: 0f).toString()
            "player().health", "player.health" -> (player?.health ?: 0f).toString()
            else -> value
        }
    }

    private fun evalFunctionValue(value: String): String? {
        val match = ACTION_PATTERN.matcher(value)
        if (!match.matches()) return null
        val name = match.group(1).toUpperCase(Locale.ROOT)
        val args = splitArgs(match.group(2) ?: "").map { replaceVars(unquote(it.trim())) }

        return when (name) {
            "ISRUNNING" -> MacroRuntime.isRunning(args.firstOrNull().orEmpty()).toString()
            "IIF" -> if (evalCondition(match.group(2)?.let { splitArgs(it).getOrNull(0) }.orEmpty())) {
                args.getOrNull(1).orEmpty()
            } else {
                args.getOrNull(2).orEmpty()
            }
            "ITEMID" -> itemFromDescriptor(args.firstOrNull().orEmpty())?.let { Item.getIdFromItem(it).toString() }
            "ITEMNAME" -> parseItemId(args.firstOrNull().orEmpty())?.let { id ->
                Item.getItemById(id)?.let { Item.itemRegistry.getNameForObject(it)?.toString() ?: it.unlocalizedName }
            }
            "ABS" -> args.firstOrNull()?.toDoubleOrNull()?.let { formatNumber(abs(it)) }
            "FLOOR" -> args.firstOrNull()?.toDoubleOrNull()?.let { formatNumber(floor(it)) }
            "CEIL", "CEILING" -> args.firstOrNull()?.toDoubleOrNull()?.let { formatNumber(ceil(it)) }
            "ROUND" -> args.firstOrNull()?.toDoubleOrNull()?.let { formatNumber(it.roundToInt().toDouble()) }
            "MIN" -> minMaxValue(args, chooseMax = false)
            "MAX" -> minMaxValue(args, chooseMax = true)
            "CLAMP" -> {
                val number = args.getOrNull(0)?.toDoubleOrNull()
                val min = args.getOrNull(1)?.toDoubleOrNull()
                val max = args.getOrNull(2)?.toDoubleOrNull()
                if (number != null && min != null && max != null) {
                    val low = min.coerceAtMost(max)
                    val high = min.coerceAtLeast(max)
                    formatNumber(number.coerceIn(low, high))
                } else {
                    null
                }
            }
            "LEN", "LENGTH" -> args.firstOrNull().orEmpty().length.toString()
            "TRIM" -> args.firstOrNull().orEmpty().trim()
            "SUBSTR", "SUBSTRING" -> substringValue(args)
            "CONTAINS" -> args.getOrNull(0).orEmpty().contains(args.getOrNull(1).orEmpty(), truthy(args.getOrNull(2) ?: "false")).toString()
            "BEGINSWITH", "STARTSWITH" -> args.getOrNull(0).orEmpty().startsWith(args.getOrNull(1).orEmpty(), truthy(args.getOrNull(2) ?: "false")).toString()
            "ENDSWITH" -> args.getOrNull(0).orEmpty().endsWith(args.getOrNull(1).orEmpty(), truthy(args.getOrNull(2) ?: "false")).toString()
            "DISTANCE" -> distanceValue(args)
            else -> null
        }
    }

    private fun execFile(args: List<String>) {
        val file = args.firstOrNull()?.trim().orEmpty()
        if (file.isBlank()) return
        val script = MacroStorage.readScriptFile(file)
        if (script == null) {
            ClientUtils.displayChatMessage("§c[MacroEngine] Script file not found: §f$file")
            return
        }
        val extraLocals = args.drop(2).mapIndexed { index, value -> index.toString() to value }.toMap()
        MacroRuntime.runScript(script, args.getOrNull(1) ?: file, extraLocals)
    }

    private fun key(bind: String?, down: Boolean? = null, pulse: Boolean = false) {
        val keyBinding = keyBinding(bind ?: return) ?: return
        if (pulse) {
            schedule {
                KeyBinding.setKeyBindState(keyBinding.keyCode, true)
                KeyBinding.onTick(keyBinding.keyCode)
            }
            sleepInterruptibly(50L)
            schedule { KeyBinding.setKeyBindState(keyBinding.keyCode, GameSettings.isKeyDown(keyBinding)) }
            return
        }
        schedule { KeyBinding.setKeyBindState(keyBinding.keyCode, down == true) }
    }

    private fun toggleKey(bind: String?) {
        val keyBinding = keyBinding(bind ?: return) ?: return
        schedule { KeyBinding.setKeyBindState(keyBinding.keyCode, !GameSettings.isKeyDown(keyBinding)) }
    }

    private fun press(keyName: String?) {
        val code = KeyboardUtils.keyCode(keyName ?: return)
        if (code == 0) return
        schedule { KeyBinding.onTick(code) }
    }

    private fun typeText(text: String) {
        schedule { mc.displayGuiScreen(GuiChat(text)) }
    }

    private fun slot(rawSlot: String?) {
        val value = rawSlot?.toIntOrNull() ?: return
        val slot = if (value in 1..9) value - 1 else value
        if (slot !in 0..8) return
        schedule { mc.thePlayer?.inventory?.currentItem = slot }
    }

    private fun scrollSlot(delta: Int) {
        val player = mc.thePlayer ?: return
        val next = Math.floorMod(player.inventory.currentItem + delta, 9)
        schedule { mc.thePlayer?.inventory?.currentItem = next }
    }

    private fun pick(items: List<String>) {
        val player = mc.thePlayer ?: return
        val wanted = items.mapNotNull { parseItemId(it) }
        if (wanted.isEmpty()) return
        for (slot in 0..8) {
            val stack = player.inventory.mainInventory.getOrNull(slot) ?: continue
            val id = Item.getIdFromItem(stack.item)
            if (wanted.contains(id)) {
                schedule { mc.thePlayer?.inventory?.currentItem = slot }
                return
            }
        }
    }

    private fun getSlot(rawArgs: List<String>, args: List<String>) {
        val targetId = parseItemId(args.getOrNull(0).orEmpty()) ?: return
        val out = rawArgs.getOrNull(1) ?: return
        val start = args.getOrNull(2)?.toIntOrNull() ?: 0
        val inventory = mc.thePlayer?.inventory?.mainInventory ?: return
        val found = (start until inventory.size).firstOrNull { slot ->
            inventory[slot]?.let { Item.getIdFromItem(it.item) == targetId } == true
        } ?: -1
        setVar(out, found.toString())
    }

    private fun getSlotItem(rawArgs: List<String>, args: List<String>) {
        val slot = args.firstOrNull()?.toIntOrNull() ?: return
        val stack = mc.thePlayer?.inventory?.mainInventory?.getOrNull(slot)
        setVar(rawArgs.getOrNull(1), stack?.let { Item.getIdFromItem(it.item).toString() } ?: "0")
        setVar(rawArgs.getOrNull(2), stack?.stackSize?.toString() ?: "0")
        setVar(rawArgs.getOrNull(3), stack?.itemDamage?.toString() ?: "0")
    }

    private fun slotClick(args: List<String>) {
        val slot = args.firstOrNull()?.toIntOrNull() ?: return
        val button = args.getOrNull(1)?.toIntOrNull() ?: 0
        val shift = truthy(args.getOrNull(2) ?: "false")
        schedule {
            val player = mc.thePlayer ?: return@schedule
            val windowId = player.openContainer?.windowId ?: 0
            mc.playerController.windowClick(windowId, slot, button, if (shift) 1 else 0, player)
        }
    }

    private fun slotClickItem(args: List<String>) {
        val descriptor = args.firstOrNull().orEmpty()
        val item = itemFromDescriptor(descriptor) ?: return
        val matchDamage = descriptorHasDamage(descriptor)
        val damage = parseItemDamage(descriptor)
        val button = args.getOrNull(1)?.toIntOrNull() ?: 0
        val shift = truthy(args.getOrNull(2) ?: "false")

        schedule {
            val player = mc.thePlayer ?: return@schedule
            val screen = mc.currentScreen
            if (screen !is GuiContainer) return@schedule

            val container = screen.inventorySlots
            val slots = container.inventorySlots
            val menuSlotCount = (slots.size - 36).coerceIn(0, slots.size)
            val searchSlots = if (menuSlotCount > 0) 0 until menuSlotCount else slots.indices
            val slot = searchSlots.firstOrNull { slotIndex ->
                val stack = slots[slotIndex].stack ?: return@firstOrNull false
                stack.item == item && (!matchDamage || stack.itemDamage == damage)
            } ?: return@schedule

            mc.playerController.windowClick(container.windowId, slot, button, if (shift) 1 else 0, player)
        }
    }

    private fun setSlotItem(args: List<String>) {
        val player = mc.thePlayer ?: return
        if (player.capabilities?.isCreativeMode != true) return
        val itemArg = args.getOrNull(0).orEmpty()
        val item = itemFromDescriptor(itemArg) ?: return
        val slot = args.getOrNull(1)?.toIntOrNull() ?: player.inventory.currentItem
        val amount = (args.getOrNull(2)?.toIntOrNull() ?: 1).coerceIn(1, itemStackLimit(item).coerceAtLeast(1))
        val stack = ItemStack(item, amount, parseItemDamage(itemArg))
        schedule { mc.thePlayer?.sendQueue?.addToSendQueue(C10PacketCreativeInventoryAction(slot, stack)) }
    }

    private fun craftFallback(args: List<String>, wait: Boolean) {
        if (args.isNotEmpty()) pick(args.take(1))
        if (wait) sleepInterruptibly(50L)
    }

    private fun getId(rawArgs: List<String>, args: List<String>, relative: Boolean) {
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return
        val x = args.getOrNull(0)?.toDoubleOrNull() ?: return
        val y = args.getOrNull(1)?.toDoubleOrNull() ?: return
        val z = args.getOrNull(2)?.toDoubleOrNull() ?: return
        val pos = if (relative) {
            BlockPos(player.posX + x, player.posY + y, player.posZ + z)
        } else {
            BlockPos(x, y, z)
        }
        val state = world.getBlockState(pos)
        setVar(rawArgs.getOrNull(3), Block.getIdFromBlock(state.block).toString())
        setVar(rawArgs.getOrNull(4), state.block.getMetaFromState(state).toString())
    }

    private fun getBlockInfo(rawArgs: List<String>, args: List<String>, relative: Boolean) {
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return
        val x = args.getOrNull(0)?.toDoubleOrNull() ?: return
        val y = args.getOrNull(1)?.toDoubleOrNull() ?: return
        val z = args.getOrNull(2)?.toDoubleOrNull() ?: return
        val pos = if (relative) {
            BlockPos(player.posX + x, player.posY + y, player.posZ + z)
        } else {
            BlockPos(x, y, z)
        }
        val state = world.getBlockState(pos)
        val block = state.block
        setVar(rawArgs.getOrNull(3), Block.getIdFromBlock(block).toString())
        setVar(rawArgs.getOrNull(4), block.getMetaFromState(state).toString())
        setVar(rawArgs.getOrNull(5), block.localizedName ?: "")
        setVar(rawArgs.getOrNull(6), Block.blockRegistry.getNameForObject(block)?.toString() ?: "")
    }

    private fun calcYawTo(rawArgs: List<String>, args: List<String>) {
        val player = mc.thePlayer ?: return
        val x = args.getOrNull(0)?.toDoubleOrNull() ?: return
        val z = args.getOrNull(1)?.toDoubleOrNull() ?: return
        val dx = x - player.posX
        val dz = z - player.posZ
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        setVar(rawArgs.getOrNull(2), MathHelper.wrapAngleTo180_float(yaw).toString())
        setVar(rawArgs.getOrNull(3), sqrt(dx * dx + dz * dz).toString())
    }

    private fun calcPitchTo(rawArgs: List<String>, args: List<String>) {
        val aim = calculateAim(args) ?: return
        setVar(rawArgs.getOrNull(3), aim.pitch.toString())
        setVar(rawArgs.getOrNull(4), aim.distance.toString())
    }

    private fun calcAimTo(rawArgs: List<String>, args: List<String>) {
        val aim = calculateAim(args) ?: return
        setVar(rawArgs.getOrNull(3), aim.yaw.toString())
        setVar(rawArgs.getOrNull(4), aim.pitch.toString())
        setVar(rawArgs.getOrNull(5), aim.distance.toString())
    }

    private fun distanceTo(rawArgs: List<String>, args: List<String>) {
        setVar(rawArgs.getOrNull(3), distanceValue(args) ?: return)
    }

    private fun distanceValue(args: List<String>): String? {
        val player = mc.thePlayer ?: return null
        val x = args.getOrNull(0)?.toDoubleOrNull() ?: return null
        val y = args.getOrNull(1)?.toDoubleOrNull() ?: return null
        val z = args.getOrNull(2)?.toDoubleOrNull() ?: return null
        val dx = x - player.posX
        val dy = y - player.posY
        val dz = z - player.posZ
        return formatNumber(sqrt(dx * dx + dy * dy + dz * dz))
    }

    private fun aimTo(args: List<String>) {
        val aim = calculateAim(args) ?: return
        val ticks = args.getOrNull(3)?.toIntOrNull() ?: 6
        applyLook(aim.yaw, aim.pitch, ticks.coerceAtLeast(1))
    }

    private fun calculateAim(args: List<String>): AimResult? {
        val player = mc.thePlayer ?: return null
        val x = args.getOrNull(0)?.toDoubleOrNull() ?: return null
        val y = args.getOrNull(1)?.toDoubleOrNull() ?: return null
        val z = args.getOrNull(2)?.toDoubleOrNull() ?: return null
        val dx = x - player.posX
        val dy = y - (player.posY + player.getEyeHeight().toDouble())
        val dz = z - player.posZ
        val horizontal = sqrt(dx * dx + dz * dz)
        val yaw = MathHelper.wrapAngleTo180_float(Math.toDegrees(atan2(dz, dx)).toFloat() - 90f)
        val pitch = MathHelper.clamp_float(-Math.toDegrees(atan2(dy, horizontal)).toFloat(), -90f, 90f)
        return AimResult(yaw, pitch, sqrt(dx * dx + dy * dy + dz * dz))
    }

    private fun look(args: List<String>, smooth: Boolean) {
        val player = mc.thePlayer ?: return
        val yawArg = args.getOrNull(0) ?: return
        val pitchArg = args.getOrNull(1)
        val yaw = parseAngle(player.rotationYaw, yawArg)
        val pitch = pitchArg?.let { parseAngle(player.rotationPitch, it) } ?: player.rotationPitch
        val ticks = if (smooth) parseLookTicks(args.getOrNull(2)) else 1
        applyLook(yaw, pitch, ticks.coerceAtLeast(1))
    }

    private fun parseLookTicks(value: String?): Int {
        val raw = value?.trim()?.toLowerCase(Locale.ROOT) ?: return 6
        return when {
            raw.endsWith("ms") -> ((raw.removeSuffix("ms").toIntOrNull() ?: 300) / 50).coerceAtLeast(1)
            raw.endsWith("t") -> raw.removeSuffix("t").toIntOrNull() ?: 6
            raw.endsWith("s") -> ((raw.removeSuffix("s").toDoubleOrNull() ?: 0.3) * 20.0).toInt().coerceAtLeast(1)
            else -> raw.toIntOrNull() ?: 6
        }
    }

    private fun applyLook(targetYaw: Float, targetPitch: Float, ticks: Int) {
        val player = mc.thePlayer ?: return
        val startYaw = player.rotationYaw
        val startPitch = player.rotationPitch
        val yawDelta = MathHelper.wrapAngleTo180_float(targetYaw - startYaw)
        val pitchDelta = MathHelper.clamp_float(targetPitch, -90f, 90f) - startPitch

        for (step in 1..ticks) {
            val progress = step.toFloat() / ticks.toFloat()
            val yaw = MathHelper.wrapAngleTo180_float(startYaw + yawDelta * progress)
            val pitch = MathHelper.clamp_float(startPitch + pitchDelta * progress, -90f, 90f)
            schedule {
                mc.thePlayer?.rotationYaw = yaw
                mc.thePlayer?.rotationPitch = pitch
            }
            if (step < ticks) sleepInterruptibly(50L)
        }
    }

    private fun setOption(value: String?, option: String) {
        val number = value?.toFloatOrNull() ?: return
        schedule {
            when (option) {
                "FOV" -> mc.gameSettings.fovSetting = number
                "GAMMA" -> mc.gameSettings.gammaSetting = number / if (number > 10f) 100f else 1f
                "SENSITIVITY" -> mc.gameSettings.mouseSensitivity = number / if (number > 10f) 100f else 1f
            }
        }
    }

    private fun setVolume(args: List<String>) {
        val value = args.firstOrNull()?.toFloatOrNull() ?: return
        val volume = value / if (value > 1f) 100f else 1f
        val category = soundCategory(args.getOrNull(1)) ?: if (args.size == 1) SoundCategory.MASTER else SoundCategory.MASTER
        schedule { mc.gameSettings.setSoundLevel(category, volume.coerceIn(0f, 1f)) }
    }

    private fun setFloatGameSetting(field: String, value: String?) {
        val number = value?.toFloatOrNull() ?: return
        val normalized = number / if (number > 1f) 100f else 1f
        schedule {
            setField(mc.gameSettings, field, normalized.coerceIn(0f, 1f))
        }
    }

    private fun chatVisible(value: String?) {
        val raw = value?.trim()?.toLowerCase(Locale.ROOT).orEmpty()
        schedule {
            mc.gameSettings.chatVisibility = when (raw) {
                "0", "false", "off", "hidden" -> EntityPlayer.EnumChatVisibility.HIDDEN
                "commands", "system", "1" -> EntityPlayer.EnumChatVisibility.SYSTEM
                else -> EntityPlayer.EnumChatVisibility.FULL
            }
        }
    }

    private fun fog(value: String?) {
        val chunks = value?.toIntOrNull()
        schedule {
            mc.gameSettings.renderDistanceChunks = chunks?.coerceIn(2, 32)
                ?: if (mc.gameSettings.renderDistanceChunks <= 2) 8 else 2
        }
    }

    private fun resourcePacks(patterns: List<String>) {
        val packs = resourcePackList()
        val selected = if (patterns.isEmpty()) {
            emptyList()
        } else {
            patterns.flatMap { pattern ->
                packs.filter { it.contains(pattern, ignoreCase = true) }
            }.distinct()
        }
        schedule {
            runCatching {
                val field = mc.gameSettings.javaClass.getDeclaredField("resourcePacks")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val list = field.get(mc.gameSettings) as? MutableList<String>
                if (list != null) {
                    list.clear()
                    list.addAll(selected)
                    mc.gameSettings.saveOptions()
                    mc.refreshResources()
                }
            }
        }
    }

    private fun setResolution(args: List<String>) {
        val width = args.getOrNull(0)?.toIntOrNull() ?: return
        val height = args.getOrNull(1)?.toIntOrNull() ?: return
        schedule {
            runCatching {
                Display.setDisplayMode(DisplayMode(width, height))
                mc.resize(width, height)
            }
        }
    }

    private fun shaderGroup(path: String?) {
        val shader = path?.trim().orEmpty()
        schedule {
            runCatching {
                if (shader.isBlank()) {
                    mc.entityRenderer.stopUseShader()
                    MacroRuntime.guiProperties.remove("SHADERGROUP")
                } else {
                    mc.entityRenderer.loadShader(ResourceLocation(shader))
                    MacroRuntime.guiProperties["SHADERGROUP"] = shader
                }
            }
        }
    }

    private fun bindKey(args: List<String>) {
        val binding = keyBinding(args.getOrNull(0) ?: return) ?: return
        val keyName = args.getOrNull(1) ?: return
        val keyCode = keyName.toIntOrNull() ?: KeyboardUtils.keyCode(keyName)
        if (keyCode == 0) return
        schedule {
            binding.keyCode = keyCode
            KeyBinding.resetKeyBindingArrayAndHash()
            mc.gameSettings.saveOptions()
        }
    }

    private fun camera(value: String?) {
        schedule {
            mc.gameSettings.thirdPersonView = value?.toIntOrNull()
                ?: ((mc.gameSettings.thirdPersonView + 1) % 3)
        }
    }

    private fun title(args: List<String>) {
        schedule {
            if (args.isEmpty()) {
                mc.ingameGUI.displayTitle("", "", 0, 0, 0)
            } else {
                mc.ingameGUI.displayTitle(color(args.getOrNull(0).orEmpty()), color(args.getOrNull(1).orEmpty()), args.getOrNull(2)?.toIntOrNull() ?: 10, args.getOrNull(3)?.toIntOrNull() ?: 40, args.getOrNull(4)?.toIntOrNull() ?: 10)
            }
        }
    }

    private fun gui(name: String?) {
        if (name == null || name.equals("chat", true)) {
            schedule { mc.displayGuiScreen(GuiChat()) }
        }
    }

    private fun showGui(name: String?) {
        val screen = name?.trim().orEmpty()
        MacroRuntime.guiProperties["SCREEN"] = screen
        MacroRuntime.guiProperties["SCREENNAME"] = screen
        gui(screen)
    }

    private fun bindGui(args: List<String>) {
        val slot = args.getOrNull(0).orEmpty()
        val screen = args.getOrNull(1).orEmpty()
        if (slot.isNotBlank()) MacroRuntime.guiProperties["BINDGUI:$slot"] = screen
    }

    private fun placeSign(args: List<String>) {
        for (index in 0..3) {
            val text = args.getOrNull(index).orEmpty()
            if (text.isNotBlank()) setVar("SIGNTEXT[$index]", text)
        }
        if (truthy(args.getOrNull(4) ?: "false")) {
            ClientUtils.displayChatMessage("§7[MacroEngine] Sign text queued: §f${args.take(4).joinToString(" | ")}")
        }
    }

    private fun playSound(args: List<String>) {
        val sound = args.firstOrNull() ?: return
        val volume = args.getOrNull(1)?.toFloatOrNull() ?: 1f
        schedule { mc.thePlayer?.playSound(sound, volume, 1f) }
    }

    private fun config(name: String?) {
        val configName = name?.trim().orEmpty().ifBlank { "default" }
        MacroRuntime.currentConfigName = configName
        setVar("CONFIG", configName)
    }

    private fun logTo(args: List<String>) {
        val target = args.getOrNull(0)?.trim().orEmpty()
        val text = args.drop(1).joinToString(",")
        if (target.isBlank()) return
        if (target.endsWith(".txt", ignoreCase = true) || target.contains(File.separatorChar)) {
            val file = File(MacroStorage.macrosDir, target)
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(color(text) + System.lineSeparator(), Charsets.UTF_8)
            }
        } else {
            MacroRuntime.guiProperties[target] = color(text)
        }
    }

    private fun prompt(rawArgs: List<String>, args: List<String>) {
        val target = rawArgs.getOrNull(0) ?: return
        val override = args.getOrNull(3)
        val default = args.getOrNull(4)
        setVar(target, override?.takeIf { it.isNotBlank() } ?: default.orEmpty())
    }

    private fun store(args: List<String>, overwrite: Boolean) {
        val type = args.getOrNull(0)?.toUpperCase(Locale.ROOT) ?: return
        val value = args.getOrNull(1).orEmpty()
        val list = MacroRuntime.stores.computeIfAbsent(type) { mutableListOf() }
        if (overwrite) list.removeAll { it.equals(value, ignoreCase = true) }
        if (value.isNotBlank() && value !in list) list += value
    }

    private fun getProperty(rawArgs: List<String>, args: List<String>) {
        val control = args.getOrNull(0).orEmpty()
        val property = args.getOrNull(1).orEmpty()
        val key = "$control.$property".toUpperCase(Locale.ROOT)
        val value = MacroRuntime.guiProperties[key].orEmpty()
        setVar(rawArgs.getOrNull(2) ?: rawArgs.getOrNull(1), value)
    }

    private fun setProperty(args: List<String>) {
        val control = args.getOrNull(0).orEmpty()
        val property = args.getOrNull(1).orEmpty()
        val value = args.getOrNull(2).orEmpty()
        if (control.isBlank() || property.isBlank()) return
        MacroRuntime.guiProperties["$control.$property".toUpperCase(Locale.ROOT)] = value
    }

    private fun setLabel(args: List<String>) {
        val label = args.getOrNull(0).orEmpty()
        if (label.isBlank()) return
        MacroRuntime.labels[label.toUpperCase(Locale.ROOT)] = args.getOrNull(1).orEmpty()
        args.getOrNull(2)?.let { MacroRuntime.guiProperties["LABELBIND:${label.toUpperCase(Locale.ROOT)}"] = it }
    }

    private fun keyBinding(bind: String): KeyBinding? {
        val key = bind.toLowerCase(Locale.ROOT)
        val gs = mc.gameSettings
        return when (key) {
            "attack", "key.attack", "leftmouse", "mouse1" -> gs.keyBindAttack
            "use", "useitem", "key.use", "rightmouse", "mouse2" -> gs.keyBindUseItem
            "jump", "key.jump" -> gs.keyBindJump
            "sneak", "key.sneak" -> gs.keyBindSneak
            "sprint", "key.sprint" -> gs.keyBindSprint
            "forward", "key.forward" -> gs.keyBindForward
            "back", "backward", "key.back" -> gs.keyBindBack
            "left", "key.left" -> gs.keyBindLeft
            "right", "key.right" -> gs.keyBindRight
            "drop", "key.drop" -> gs.keyBindDrop
            "inventory", "key.inventory" -> gs.keyBindInventory
            "chat", "key.chat" -> gs.keyBindChat
            "playerlist", "tab", "key.playerlist" -> gs.keyBindPlayerList
            "pickblock", "key.pickitem" -> gs.keyBindPickBlock
            else -> gs.keyBindings.firstOrNull { it.keyDescription.equals(bind, true) }
        }
    }

    private fun unwrapScript(script: String): String {
        if (!script.contains(SCRIPT_START)) return script
        val out = StringBuilder()
        var index = 0
        while (index < script.length) {
            val start = script.indexOf(SCRIPT_START, index)
            if (start < 0) break
            val end = script.indexOf(SCRIPT_END, start + SCRIPT_START.length)
            if (end < 0) break
            out.append(script, start + SCRIPT_START.length, end).append(';')
            index = end + SCRIPT_END.length
        }
        return out.toString()
    }

    private fun expandInlineIncludes(script: String): String {
        val matcher = INCLUDE_PATTERN.matcher(script)
        val out = StringBuffer()
        while (matcher.find()) {
            val included = MacroStorage.readScriptFile(matcher.group(1)) ?: ""
            matcher.appendReplacement(out, Regex.escapeReplacement(included))
        }
        matcher.appendTail(out)
        return out.toString()
    }

    private fun expandParameters(script: String): String {
        var result = script

        val listMatcher = PARAM_LIST_PATTERN.matcher(result)
        val listOut = StringBuffer()
        while (listMatcher.find()) {
            val first = listMatcher.group(1).split(',').firstOrNull()?.trim().orEmpty()
            listMatcher.appendReplacement(listOut, Regex.escapeReplacement(first))
        }
        listMatcher.appendTail(listOut)
        result = listOut.toString()

        val namedMatcher = PARAM_NAMED_PATTERN.matcher(result)
        val namedOut = StringBuffer()
        while (namedMatcher.find()) {
            namedMatcher.appendReplacement(namedOut, "")
        }
        namedMatcher.appendTail(namedOut)
        result = namedOut.toString()

        result = result.replace(PARAM_TYPED_PATTERN) { match ->
            parameterValue(match.groupValues[1])
        }

        result = result.replace("$$?", "")
        result = result.replace("$$!", "")
        for (index in 0..9) {
            result = result.replace("$$$index", MacroRuntime.stores[index.toString()]?.firstOrNull().orEmpty())
        }

        return result
    }

    private fun parameterValue(type: String): String {
        return when (type.toLowerCase(Locale.ROOT)) {
            "u" -> mc.thePlayer?.sendQueue?.playerInfoMap?.firstOrNull()?.gameProfile?.name ?: mc.thePlayer?.name ?: ""
            "i", "i:d" -> heldItem()?.let { "${Item.getIdFromItem(it.item)}:${it.itemDamage}" }.orEmpty()
            "k" -> resourcePackList().firstOrNull().orEmpty()
            "m" -> MacroStorage.macrosDir.listFiles { file -> file.isFile && file.extension.equals("txt", true) }
                ?.firstOrNull()?.name.orEmpty()
            "s" -> MacroRuntime.guiProperties["SHADERGROUP"].orEmpty()
            "f", "h", "p", "t", "w" -> MacroRuntime.stores[type.toUpperCase(Locale.ROOT)]?.firstOrNull().orEmpty()
            else -> MacroRuntime.stores[type.toUpperCase(Locale.ROOT)]?.firstOrNull().orEmpty()
        }
    }

    private fun parseWait(value: String): Long {
        val v = value.trim().toLowerCase(Locale.ROOT)
        return when {
            v.endsWith("ms") -> v.removeSuffix("ms").toLongOrNull() ?: 0L
            v.endsWith("t") -> (v.removeSuffix("t").toLongOrNull() ?: 0L) * 50L
            else -> ((v.toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
        }.coerceAtLeast(0L)
    }

    private fun doCount(statement: Statement): Int {
        return statement.args.firstOrNull()?.let { replaceVars(it).toIntOrNull() } ?: 0
    }

    private fun sendChat(message: String) {
        schedule { mc.thePlayer?.sendChatMessage(color(message)) }
    }

    private fun sleepInterruptibly(totalMs: Long) {
        var remaining = totalMs.coerceAtLeast(0L)
        while (remaining > 0L) {
            if (!runningTasks.contains(taskId)) throw StopMacro()
            val step = remaining.coerceAtMost(50L)
            Thread.sleep(step)
            remaining -= step
        }
    }

    private fun schedule(action: () -> Unit) {
        mc.addScheduledTask { runCatching(action) }
    }

    private fun color(text: String): String = text.replace('&', '\u00A7')

    private fun unquote(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length >= 2 &&
            ((trimmed.first() == '"' && trimmed.last() == '"') || (trimmed.first() == '\'' && trimmed.last() == '\''))
        ) trimmed.substring(1, trimmed.length - 1) else trimmed
    }

    private fun cleanVarName(name: String): String {
        return name.trim()
            .removePrefix("%")
            .removeSuffix("%")
            .removePrefix("&")
            .removePrefix("#")
            .removePrefix("@")
            .toUpperCase(Locale.ROOT)
    }

    private fun truthy(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        if (v.isBlank()) return false
        if (v.equals("false", true) || v.equals("no", true) || v == "0") return false
        return true
    }

    private fun splitBoolean(expr: String, op: String): List<String>? {
        val parts = splitByToken(expr, op)
        return if (parts.size > 1) parts else null
    }

    private fun splitByToken(expr: String, token: String): List<String> {
        val result = mutableListOf<String>()
        var quote: Char? = null
        var index = 0
        var start = 0
        while (index < expr.length) {
            val c = expr[index]
            if (quote != null) {
                if (c == quote) quote = null
                index++
                continue
            }
            if (c == '"' || c == '\'') quote = c
            if (expr.startsWith(token, index)) {
                result += expr.substring(start, index)
                index += token.length
                start = index
            } else {
                index++
            }
        }
        result += expr.substring(start)
        return result
    }

    private fun splitOperator(expr: String, op: String): Pair<String, String>? {
        val parts = splitByToken(expr, op)
        if (parts.size != 2) return null
        return parts[0] to parts[1]
    }

    private fun heldItem(): ItemStack? = mc.thePlayer?.heldItem

    private fun durabilityPercent(stack: ItemStack): Int {
        val maxDamage = stack.maxDamage
        if (!stack.isItemStackDamageable || maxDamage <= 0) return 100
        val remaining = (maxDamage - stack.itemDamage).coerceIn(0, maxDamage)
        return ((remaining.toDouble() / maxDamage.toDouble()) * 100.0).toInt()
    }

    private fun armorVar(key: String): String? {
        val armor = mc.thePlayer?.inventory?.armorInventory ?: return null
        val stack = when {
            key.startsWith("BOOTS") -> armor.getOrNull(0)
            key.startsWith("LEGGINGS") -> armor.getOrNull(1)
            key.startsWith("CHESTPLATE") -> armor.getOrNull(2)
            key.startsWith("HELM") -> armor.getOrNull(3)
            else -> null
        } ?: return null

        return when {
            key.endsWith("ID") -> Item.getIdFromItem(stack.item).toString()
            key.endsWith("NAME") -> stack.displayName
            key.endsWith("DAMAGE") -> stack.maxDamage.toString()
            key.endsWith("DURABILITY") -> (stack.maxDamage - stack.itemDamage).toString()
            else -> null
        }
    }

    private fun hitVar(key: String): String? {
        val hit = mc.objectMouseOver ?: return null
        return when (key) {
            "HIT" -> hit.typeOfHit?.name ?: ""
            "HITX" -> hit.blockPos?.x?.toString() ?: ""
            "HITY" -> hit.blockPos?.y?.toString() ?: ""
            "HITZ" -> hit.blockPos?.z?.toString() ?: ""
            "HITSIDE" -> hit.sideHit?.name ?: ""
            "HITID" -> hit.blockPos?.let { Block.getIdFromBlock(mc.theWorld?.getBlockState(it)?.block ?: Blocks.air).toString() }
            "HITNAME" -> hit.blockPos?.let { mc.theWorld?.getBlockState(it)?.block?.localizedName ?: "" }
            else -> null
        }
    }

    private fun parseItemId(value: String): Int? {
        val id = value.substringBefore(':').trim()
        return id.toIntOrNull()
    }

    private fun parseAngle(base: Float, raw: String): Float {
        return if (raw.startsWith("+") || raw.startsWith("-")) {
            base + (raw.toFloatOrNull() ?: 0f)
        } else {
            raw.toFloatOrNull() ?: base
        }
    }

    private fun directAssignment(raw: String): Pair<String, String>? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.first() !in charArrayOf('&', '#', '@', '%')) return null

        var quote: Char? = null
        var depth = 0
        var index = 0
        while (index < trimmed.length) {
            val c = trimmed[index]
            if (quote != null) {
                if (c == quote) quote = null
                index++
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                '=' -> {
                    val previous = trimmed.getOrNull(index - 1)
                    val next = trimmed.getOrNull(index + 1)
                    if (depth == 0 &&
                        previous != '<' && previous != '>' && previous != '!' && previous != '=' &&
                        next != '='
                    ) {
                        val left = trimmed.substring(0, index).trim()
                        val right = trimmed.substring(index + 1).trim()
                        if (left.isNotBlank()) return left to right
                    }
                }
            }
            index++
        }

        return null
    }

    private fun findVariableEnd(text: String, start: Int): Int {
        var quote: Char? = null
        var depth = 0
        var index = start

        while (index < text.length) {
            val c = text[index]
            if (quote != null) {
                if (c == quote) quote = null
                index++
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '[' -> depth++
                ']' -> if (depth > 0) depth--
                '%' -> if (depth == 0) return index
            }
            index++
        }

        return -1
    }

    private fun parseArrayRef(raw: String): Pair<String, Int?>? {
        val text = raw.trim().removePrefix("%").removeSuffix("%")
        val open = text.indexOf('[')
        val close = text.lastIndexOf(']')
        if (open !in 0 until close) return null
        val name = cleanVarName(text.substring(0, open))
        if (name.isBlank()) return null
        val indexRaw = text.substring(open + 1, close).trim()
        val index = if (indexRaw.isBlank()) null else replaceVars(indexRaw).toIntOrNull()
        return name to index
    }

    private fun resolveArrayRead(raw: String): String? {
        val ref = parseArrayRef(raw) ?: return null
        val array = MacroRuntime.arrays[ref.first] ?: return ""
        return ref.second?.let { array.getOrNull(it).orEmpty() } ?: array.joinToString(",")
    }

    private fun setArrayValue(raw: String, value: String): Boolean {
        val ref = parseArrayRef(raw) ?: return false
        val index = ref.second
        val array = mutableArray(raw)
        if (index == null) {
            array.clear()
            if (value.isNotBlank()) array += value.split(',')
            return true
        }
        while (array.size <= index) array += ""
        array[index] = value
        return true
    }

    private fun mutableArray(raw: String): MutableList<String> {
        val name = parseArrayRef(raw)?.first ?: cleanVarName(raw)
        return MacroRuntime.arrays.computeIfAbsent(name) { mutableListOf() }
    }

    private fun isVariableRef(raw: String): Boolean {
        val text = raw.trim()
        return text.startsWith("&") || text.startsWith("#") || text.startsWith("@") || (text.startsWith("%") && text.endsWith("%"))
    }

    private fun iteratorRows(rawIterator: String): List<Map<String, String>> {
        val iterator = replaceVars(rawIterator).substringBefore('(').substringBefore(':').trim().toLowerCase(Locale.ROOT)
        val player = mc.thePlayer
        return when (iterator) {
            "players" -> player?.sendQueue?.playerInfoMap?.mapIndexed { index, info ->
                mapOf(
                    "INDEX" to index.toString(),
                    "PLAYER" to info.gameProfile.name,
                    "PLAYERNAME" to info.gameProfile.name,
                    "UUID" to info.gameProfile.id.toString()
                )
            }.orEmpty()

            "running" -> MacroRuntime.runningIds().mapIndexed { index, id ->
                mapOf("INDEX" to index.toString(), "MACRO" to id, "VALUE" to id)
            }

            "env" -> MacroRuntime.variables.entries.mapIndexed { index, entry ->
                mapOf("INDEX" to index.toString(), "VARNAME" to entry.key, "VALUE" to entry.value)
            }

            "effects" -> player?.activePotionEffects?.mapIndexed { index, effect ->
                val potion = Potion.potionTypes.getOrNull(effect.potionID)
                mapOf(
                    "INDEX" to index.toString(),
                    "EFFECT" to (potion?.name ?: effect.potionID.toString()),
                    "EFFECTID" to effect.potionID.toString(),
                    "EFFECTDURATION" to effect.duration.toString(),
                    "EFFECTAMPLIFIER" to effect.amplifier.toString()
                )
            }.orEmpty()

            "enchantments" -> enchantmentRows()
            "inventory" -> inventoryRows(hotbarOnly = false)
            "hotbar" -> inventoryRows(hotbarOnly = true)
            "container", "slots" -> containerRows()
            "properties" -> propertyRows()
            "controls" -> MacroRuntime.guiProperties.entries.mapIndexed { index, entry ->
                mapOf("INDEX" to index.toString(), "CONTROL" to entry.key, "VALUE" to entry.value)
            }

            else -> emptyList()
        }
    }

    private fun inventoryRows(hotbarOnly: Boolean): List<Map<String, String>> {
        val inventory = mc.thePlayer?.inventory?.mainInventory ?: return emptyList()
        val slots = if (hotbarOnly) 0..8 else inventory.indices
        return slots.mapNotNull { slot ->
            val stack = inventory.getOrNull(slot) ?: return@mapNotNull null
            itemRow(slot, slot, stack)
        }
    }

    private fun containerRows(): List<Map<String, String>> {
        val slots = mc.thePlayer?.openContainer?.inventorySlots ?: return emptyList()
        return slots.mapIndexedNotNull { index, slot ->
            val stack = slot.stack ?: return@mapIndexedNotNull null
            itemRow(index, slot.slotNumber, stack)
        }
    }

    private fun itemRow(index: Int, slot: Int, stack: ItemStack): Map<String, String> {
        val item = stack.item
        val maxDamage = stack.maxDamage
        val durability = if (stack.isItemStackDamageable) (maxDamage - stack.itemDamage).coerceAtLeast(0) else 0
        return mapOf(
            "INDEX" to index.toString(),
            "SLOT" to slot.toString(),
            "ITEM" to Item.getIdFromItem(item).toString(),
            "ITEMID" to Item.getIdFromItem(item).toString(),
            "ITEMCODE" to (Item.itemRegistry.getNameForObject(item)?.toString() ?: ""),
            "ITEMNAME" to (stack.displayName ?: ""),
            "STACKSIZE" to stack.stackSize.toString(),
            "DAMAGE" to stack.itemDamage.toString(),
            "MAXDAMAGE" to maxDamage.toString(),
            "DURABILITY" to durability.toString(),
            "DURABILITYPCT" to durabilityPercent(stack).toString()
        )
    }

    private fun enchantmentRows(): List<Map<String, String>> {
        val stack = heldItem() ?: return emptyList()
        val tags = stack.enchantmentTagList ?: return emptyList()
        return (0 until tags.tagCount()).mapNotNull { index ->
            val compound = tags.getCompoundTagAt(index)
            val id = compound.getShort("id").toInt()
            val level = compound.getShort("lvl").toInt()
            val enchantment = net.minecraft.enchantment.Enchantment.getEnchantmentById(id)
            mapOf(
                "INDEX" to index.toString(),
                "ENCHANTMENT" to (enchantment?.name ?: id.toString()),
                "ENCHANTMENTID" to id.toString(),
                "ENCHANTMENTLEVEL" to level.toString()
            )
        }
    }

    private fun propertyRows(): List<Map<String, String>> {
        val pos = mc.objectMouseOver?.blockPos ?: return emptyList()
        val state = mc.theWorld?.getBlockState(pos) ?: return emptyList()
        return state.properties.entries.mapIndexed { index, entry ->
            mapOf(
                "INDEX" to index.toString(),
                "PROPERTY" to propertyName(entry.key),
                "VALUE" to entry.value.toString()
            )
        }
    }

    private fun propertyName(property: Any): String {
        return runCatching {
            property.javaClass.getMethod("getName").invoke(property)?.toString() ?: property.toString()
        }.getOrDefault(property.toString())
    }

    private fun evalNumericExpression(value: String): Double? {
        if (!value.any { it in charArrayOf('+', '-', '*', '/', '%') }) return null
        val parser = NumericParser(value)
        return runCatching { parser.parse() }.getOrNull()
    }

    private fun formatNumber(value: Double): String {
        return if (!value.isNaN() && !value.isInfinite() && value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }

    private fun itemFromDescriptor(raw: String): Item? {
        val parts = raw.trim().split(':')
        val descriptor = when {
            parts.isEmpty() -> ""
            parts[0].toIntOrNull() != null -> parts[0]
            parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank() -> "${parts[0]}:${parts[1]}"
            else -> parts[0]
        }
        descriptor.toIntOrNull()?.let { return Item.getItemById(it) }
        if (descriptor.isBlank()) return null
        val id = if (descriptor.contains(':')) descriptor else "minecraft:$descriptor"
        return Item.itemRegistry.getObject(ResourceLocation(id))
    }

    @Suppress("DEPRECATION")
    private fun itemStackLimit(item: Item): Int {
        return item.itemStackLimit
    }

    private fun parseItemDamage(raw: String): Int {
        val parts = raw.split(':')
        return when {
            parts.size >= 3 -> parts[2].toIntOrNull() ?: 0
            parts.size == 2 && parts[0].toIntOrNull() != null -> parts[1].toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun descriptorHasDamage(raw: String): Boolean {
        val parts = raw.trim().split(':')
        return parts.size >= 3 || (parts.size == 2 && parts[0].toIntOrNull() != null)
    }

    private fun soundCategory(raw: String?): SoundCategory? {
        return when (raw?.trim()?.toUpperCase(Locale.ROOT)) {
            null, "" -> null
            "MASTER", "SOUND" -> SoundCategory.MASTER
            "MUSIC" -> SoundCategory.MUSIC
            "RECORD", "RECORDS", "JUKEBOX" -> SoundCategory.RECORDS
            "WEATHER" -> SoundCategory.WEATHER
            "BLOCK", "BLOCKS" -> SoundCategory.BLOCKS
            "HOSTILE", "HOSTILEVOLUME" -> SoundCategory.MOBS
            "NEUTRAL", "FRIENDLY" -> SoundCategory.ANIMALS
            "PLAYER", "PLAYERS" -> SoundCategory.PLAYERS
            "AMBIENT", "ENVIRONMENT" -> SoundCategory.AMBIENT
            else -> null
        }
    }

    private fun resourcePackList(): List<String> {
        return runCatching {
            val field = mc.gameSettings.javaClass.getDeclaredField("resourcePacks")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(mc.gameSettings) as? List<String>).orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun setField(target: Any, name: String, value: Any) {
        runCatching {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(target, value)
        }
    }

    private fun signTextVar(key: String): String? {
        if (!key.startsWith("SIGNTEXT[")) return null
        return resolveArrayRead(key)
    }

    private fun offhandVar(key: String): String {
        return when {
            key.endsWith("ITEMIDDMG") -> "0:0"
            key.endsWith("ITEMNAME") -> ""
            key.endsWith("ITEMCODE") -> ""
            key.endsWith("DURABILITY") -> "0"
            key.endsWith("DAMAGE") -> "0"
            key.endsWith("STACKSIZE") -> "0"
            key.endsWith("COOLDOWN") -> "0"
            key.endsWith("ITEM") -> "0"
            else -> ""
        }
    }

    private fun itemUsePercent(): Int {
        val player = mc.thePlayer ?: return 0
        val max = player.itemInUse?.maxItemUseDuration ?: return 0
        if (max <= 0) return 0
        return ((player.itemInUseDuration.toDouble() / max.toDouble()) * 100.0).toInt()
    }

    private fun dayTime(worldTime: Long): String {
        val ticks = Math.floorMod(worldTime + 6000L, 24000L)
        val hours = (ticks / 1000L).toInt()
        val minutes = ((ticks % 1000L) * 60L / 1000L).toInt()
        return "%02d:%02d".format(Locale.ROOT, hours, minutes)
    }

    private fun isWrapped(raw: String, open: Char, close: Char): Boolean {
        if (raw.length < 2 || raw.first() != open || raw.last() != close) return false
        var depth = 0
        var quote: Char? = null
        for (index in raw.indices) {
            val c = raw[index]
            if (quote != null) {
                if (c == quote) quote = null
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0 && index != raw.lastIndex) return false
                }
            }
        }
        return depth == 0
    }

    private class NumericParser(private val input: String) {
        private var index = 0

        fun parse(): Double {
            val value = parseExpression()
            skipSpaces()
            if (index != input.length) throw IllegalArgumentException("Trailing input")
            return value
        }

        private fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipSpaces()
                value = when {
                    consume('+') -> value + parseTerm()
                    consume('-') -> value - parseTerm()
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                skipSpaces()
                value = when {
                    consume('*') -> value * parseFactor()
                    consume('/') -> value / parseFactor()
                    consume('%') -> value % parseFactor()
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            skipSpaces()
            if (consume('+')) return parseFactor()
            if (consume('-')) return -parseFactor()
            if (consume('(')) {
                val value = parseExpression()
                if (!consume(')')) throw IllegalArgumentException("Missing )")
                return value
            }

            val start = index
            while (index < input.length && (input[index].isDigit() || input[index] == '.')) index++
            if (start == index) throw IllegalArgumentException("Expected number")
            return input.substring(start, index).toDouble()
        }

        private fun consume(char: Char): Boolean {
            skipSpaces()
            if (index < input.length && input[index] == char) {
                index++
                return true
            }
            return false
        }

        private fun skipSpaces() {
            while (index < input.length && input[index].isWhitespace()) index++
        }
    }

    private data class Statement(val name: String, val args: List<String>, val raw: String)
    private data class Branch(val token: Statement, val start: Int, val end: Int)
    private data class AimResult(val yaw: Float, val pitch: Float, val distance: Double)
    private class StopMacro : RuntimeException()
    private class BreakLoop : RuntimeException()

    companion object {
        private const val SCRIPT_START = "$" + "$" + "{"
        private const val SCRIPT_END = "}" + "$" + "$"
        private val ACTION_PATTERN = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\((.*)\\))?\\s*$", Pattern.DOTALL)
        private val INCLUDE_PATTERN = Pattern.compile("\\$\\$<([^>]+)>")
        private val PARAM_LIST_PATTERN = Pattern.compile("\\$\\$\\[\\[([^]]*)]]")
        private val PARAM_NAMED_PATTERN = Pattern.compile("\\$\\$\\[([^]]+)]")
        private val PARAM_TYPED_PATTERN = Regex("\\$\\$([a-z](?::[a-z])?)", RegexOption.IGNORE_CASE)
    }
}
