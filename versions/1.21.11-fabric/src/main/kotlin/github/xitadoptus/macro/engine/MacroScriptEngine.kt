package github.xitadoptus.macro.engine

import com.mojang.blaze3d.platform.InputConstants
import github.xitadoptus.macro.MacroMod
import github.xitadoptus.macro.gui.MacroScreen
import github.xitadoptus.macro.recorder.builder.InventoryFullDetector
import github.xitadoptus.macro.recorder.builder.RouteNavigator
import github.xitadoptus.macro.recorder.builder.RouteWaypoint
import github.xitadoptus.macro.util.ClientUtils
import github.xitadoptus.macro.util.KeyboardUtils
import github.xitadoptus.macro.util.MinecraftInstance
import net.minecraft.client.CameraType
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.text.SimpleDateFormat
import java.util.Base64
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
    private val localVariables = locals.mapKeys { it.key.uppercase(Locale.ROOT) }.toMutableMap()
    private val random = Random()

    fun execute(rawScript: String) {
        val script = expandParameters(expandInlineIncludes(rawScript))
        val statements = MacroScriptParser.parse(script)
        if (statements.isEmpty()) {
            val plain = replaceVars(MacroScriptParser.unwrap(script).trim())
            if (plain.isNotBlank()) sendChat(plain)
            return
        }

        try {
            executeRange(statements, 0, statements.size)
        } catch (_: StopMacro) {
        }
    }

    private fun executeRange(statements: List<MacroStatement>, start: Int, end: Int): Int {
        var index = start
        while (index < end) {
            if (!runningTasks.contains(taskId)) throw StopMacro()
            val statement = statements[index]
            when (statement.name) {
                "IF", "IFCONTAINS", "IFBEGINSWITH", "IFENDSWITH", "IFMATCHES" -> index = executeIf(statements, index, end)
                "DO" -> index = executeDo(statements, index, end)
                "FOR", "FOREACH" -> index = executeFor(statements, index, end)
                "UNSAFE" -> index = executeUnsafe(statements, index, end)
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

    private fun executeIf(statements: List<MacroStatement>, ifIndex: Int, end: Int): Int {
        val branches = mutableListOf<Branch>()
        var depth = 0
        var branchToken = statements[ifIndex]
        var branchStart = ifIndex + 1
        var index = ifIndex + 1
        var endif = end

        loop@ while (index < end) {
            val statement = statements[index]
            when (statement.name) {
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
                        branchToken = statement
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

    private fun executeDo(statements: List<MacroStatement>, doIndex: Int, end: Int): Int {
        var depth = 0
        var loopEnd = end
        var loopTerminator: MacroStatement? = null
        var index = doIndex + 1

        loop@ while (index < end) {
            val statement = statements[index]
            when (statement.name) {
                "DO" -> depth++
                "WHILE", "UNTIL", "LOOP" -> {
                    if (depth == 0) {
                        loopEnd = index
                        loopTerminator = statement
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
            if (terminator.name == "LOOP") continue
            val condition = evalCondition(terminator.args.getOrNull(0).orEmpty())
            if (terminator.name == "WHILE" && !condition) break
            if (terminator.name == "UNTIL" && condition) break
        }

        return if (loopEnd < end) loopEnd + 1 else end
    }

    private fun executeFor(statements: List<MacroStatement>, forIndex: Int, end: Int): Int {
        var depth = 0
        var loopEnd = end
        var index = forIndex + 1

        loop@ while (index < end) {
            val statement = statements[index]
            when (statement.name) {
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
            val savedLocals = HashMap(localVariables)
            try {
                for (row in rows) {
                    row.forEach { (key, value) -> setLocalVar(key, value) }
                    try {
                        executeRange(statements, forIndex + 1, loopEnd)
                    } catch (_: BreakLoop) {
                        break
                    }
                }
            } finally {
                localVariables.clear()
                localVariables.putAll(savedLocals)
            }
        }

        return if (loopEnd < end) loopEnd + 1 else end
    }

    private fun executeUnsafe(statements: List<MacroStatement>, unsafeIndex: Int, end: Int): Int {
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

    private fun executeAction(statement: MacroStatement) {
        val args = statement.args.map { replaceVars(unquote(it.trim())) }

        when (statement.name) {
            "CHAT" -> if (args.isNotEmpty()) sendChat(args.joinToString(","))
            "ECHO" -> if (args.isNotEmpty()) sendChat(args.joinToString(","))
            "LOG" -> if (args.isNotEmpty()) ClientUtils.displayChatMessage(ClientUtils.color(args.joinToString(",")))
            "LOGRAW" -> if (args.isNotEmpty()) ClientUtils.displayChatMessage(args.joinToString(","))
            "WAIT" -> sleepInterruptibly(parseWait(args.firstOrNull() ?: "1"))
            "WAITUNTIL" -> waitUntil(statement.args.getOrNull(0).orEmpty(), args.getOrNull(1))
            "WAITWHILE" -> waitWhile(statement.args.getOrNull(0).orEmpty(), args.getOrNull(1))
            "WAITINVENTORYFULL" -> waitInventoryFull()
            "GOTO" -> goTo(args)
            "PAUSEMACRO" -> pauseMacro(args.firstOrNull() ?: "paused")
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
            "LCASE" -> transformString(statement.args, args) { it.lowercase(Locale.ROOT) }
            "UCASE" -> transformString(statement.args, args) { it.uppercase(Locale.ROOT) }
            "ENCODE" -> transformString(statement.args, args) { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
            "DECODE" -> transformString(statement.args, args) { runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrDefault("") }
            "STRIP" -> setVar(statement.args.getOrNull(0), ClientUtils.stripColors(args.getOrNull(1).orEmpty()))
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
            "GETNEARESTNAMETAG" -> getNearestNametag(statement.args, args)
            "KEY" -> key(args.firstOrNull(), pulse = true)
            "KEYDOWN" -> key(args.firstOrNull(), down = true)
            "KEYUP" -> key(args.firstOrNull(), down = false)
            "TOGGLEKEY" -> toggleKey(args.firstOrNull())
            "PRESS" -> press(args.firstOrNull())
            "TYPE" -> typeText(args.joinToString(","))
            "SPRINT" -> schedule { mc.player?.setSprinting(true) }
            "UNSPRINT" -> schedule { mc.player?.setSprinting(false) }
            "SLOT" -> slot(args.firstOrNull())
            "INVENTORYUP" -> scrollSlot(-(args.firstOrNull()?.toIntOrNull() ?: 1))
            "INVENTORYDOWN" -> scrollSlot(args.firstOrNull()?.toIntOrNull() ?: 1)
            "PICK" -> pick(args)
            "GETSLOT" -> getSlot(statement.args, args)
            "GETSLOTITEM" -> getSlotItem(statement.args, args)
            "SLOTCLICK" -> slotClick(args)
            "SLOTCLICKITEM", "CLICKITEM" -> slotClickItem(args)
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
            "CHATHEIGHT" -> setChatOption(args.firstOrNull(), "CHATHEIGHT")
            "CHATHEIGHTFOCUSED" -> setChatOption(args.firstOrNull(), "CHATHEIGHTFOCUSED")
            "CHATOPACITY" -> setChatOption(args.firstOrNull(), "CHATOPACITY")
            "CHATSCALE" -> setChatOption(args.firstOrNull(), "CHATSCALE")
            "CHATWIDTH" -> setChatOption(args.firstOrNull(), "CHATWIDTH")
            "CHATVISIBLE" -> chatVisible(args.firstOrNull())
            "BIND" -> bindKey(args)
            "CAMERA" -> camera(args.firstOrNull())
            "CLEARCHAT" -> schedule { mc.gui.chat.clearMessages(false) }
            "TITLE" -> title(args)
            "POPUPMESSAGE", "TOAST" -> if (args.isNotEmpty()) ClientUtils.displayChatMessage(args.joinToString(" "))
            "GUI", "SHOWGUI" -> gui(args.firstOrNull())
            "BINDGUI" -> bindGui(args)
            "DISCONNECT" -> schedule { mc.disconnect(TitleScreen(), false) }
            "RESPAWN" -> schedule { mc.player?.respawn() }
            "PLAYSOUND" -> playSound(args)
            "ISRUNNING" -> setVar(statement.args.getOrNull(1), MacroRuntime.isRunning(args.firstOrNull().orEmpty()).toString())
            "CONFIG" -> config(args.firstOrNull())
            "IMPORT" -> MacroRuntime.stores.computeIfAbsent("IMPORTS") { java.util.concurrent.CopyOnWriteArrayList<String>() }.add(args.firstOrNull().orEmpty())
            "UNIMPORT" -> MacroRuntime.stores["IMPORTS"]?.remove(args.firstOrNull().orEmpty())
            "LOGTO" -> logTo(args)
            "PROMPT" -> prompt(statement.args, args)
            "STORE" -> store(args, overwrite = false)
            "STOREOVER" -> store(args, overwrite = true)
            "GETPROPERTY" -> getProperty(statement.args, args)
            "SETPROPERTY" -> setProperty(args)
            "SETLABEL" -> setLabel(args)
            "SETSLOTITEM", "CLEARCRAFTING", "CRAFT", "CRAFTANDWAIT", "RESOURCEPACKS", "SETRES", "SHADERGROUP", "FOG", "RELOADRESOURCES", "PLACESIGN", "CHATFILTER", "FILTER", "PASS", "MODIFY", "REPL" -> Unit
            else -> executeUnknown(statement)
        }
    }

    private fun executeUnknown(statement: MacroStatement) {
        val raw = replaceVars(statement.raw.trim())
        if (raw.isBlank()) return
        if (raw.startsWith("/")) sendChat(raw) else ClientUtils.displayChatMessage("\u00A77[MacroEngine] Unsupported action \u00A7f${statement.name}\u00A77.")
    }

    private fun evalIfStatement(statement: MacroStatement): Boolean {
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
        val quoted = raw.length >= 2 && ((raw.first() == '"' && raw.last() == '"') || (raw.first() == '\'' && raw.last() == '\''))
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
            val compare = if (lNum != null && rNum != null) lNum.compareTo(rNum) else left.compareTo(right, ignoreCase = true)
            return when (op) {
                "<=" -> compare <= 0
                ">=" -> compare >= 0
                "!=" -> compare != 0
                "==", "=" -> compare == 0
                "<" -> compare < 0
                ">" -> compare > 0
                else -> false
            }
        }

        return if (quoted) expr.isNotEmpty() else truthy(evalConditionValue(expr))
    }

    private fun evalConditionValue(expr: String): String {
        val value = expr.trim()
        if (value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true)) return value
        if (value.toDoubleOrNull() != null) return value
        if (isVariableRef(value) || isBareIdentifier(value)) return resolveVariable(value)
        return evalValue(value)
    }

    private fun replaceVars(text: String): String {
        if (!text.contains('%')) return text
        val result = StringBuilder()
        var index = 0
        while (index < text.length) {
            val start = text.indexOf('%', index)
            if (start < 0) {
                result.append(text.substring(index))
                break
            }
            result.append(text.substring(index, start))
            val end = findVariableEnd(text, start + 1)
            if (end < 0) {
                result.append(text.substring(start))
                break
            }
            result.append(resolveVariable(text.substring(start + 1, end)))
            index = end + 1
        }
        return result.toString()
    }

    private fun resolveVariable(name: String): String {
        val key = MacroScriptParser.cleanVarName(name)
        resolveArrayRead(name)?.let { return it }
        localVariables[key]?.let { return it }
        MacroRuntime.variables[key]?.let { return it }
        signTextVar(key)?.let { return it }
        armorVar(key)?.let { return it }
        hitVar(key)?.let { return it }
        return when (key) {
            "PLAYER", "PLAYERNAME", "USERNAME" -> mc.player?.gameProfile?.name.orEmpty()
            "SERVER" -> mc.currentServer?.ip.orEmpty()
            "WORLD" -> mc.level?.dimension()?.identifier()?.toString().orEmpty()
            "XPOS", "X" -> mc.player?.x?.toInt()?.toString().orEmpty()
            "YPOS", "Y" -> mc.player?.y?.toInt()?.toString().orEmpty()
            "ZPOS", "Z" -> mc.player?.z?.toInt()?.toString().orEmpty()
            "YAW" -> mc.player?.yRot?.toString().orEmpty()
            "PITCH" -> mc.player?.xRot?.toString().orEmpty()
            "HEALTH" -> mc.player?.health?.roundToInt()?.toString().orEmpty()
            "FOODLEVEL" -> mc.player?.foodData?.foodLevel?.toString().orEmpty()
            "GAMEMODE" -> mc.gameMode?.playerMode?.name.orEmpty()
            "DIMENSION" -> mc.level?.dimension()?.identifier()?.path.orEmpty()
            "TIME" -> System.currentTimeMillis().toString()
            "DAYTIME" -> mc.level?.dayTime?.let { dayTime(it) }.orEmpty()
            "CONFIG" -> MacroRuntime.currentConfigName
            "ITEM", "ITEMID" -> heldItem()?.let { BuiltInRegistries.ITEM.getId(it.item).toString() }.orEmpty()
            "ITEMNAME" -> heldItem()?.hoverName?.string.orEmpty()
            "ITEMCODE" -> heldItem()?.let { BuiltInRegistries.ITEM.getKey(it.item).toString() }.orEmpty()
            "STACKSIZE" -> heldItem()?.count?.toString().orEmpty()
            "DAMAGE" -> heldItem()?.damageValue?.toString().orEmpty()
            "DURABILITY" -> heldItem()?.let { (it.maxDamage - it.damageValue).coerceAtLeast(0).toString() }.orEmpty()
            "DURABILITYPCT" -> heldItem()?.let { durabilityPercent(it).toString() }.orEmpty()
            "ITEMUSEPCT" -> itemUsePercent().toString()
            else -> offhandVar(key)
        }
    }

    private fun readVar(name: String): String? {
        val key = MacroScriptParser.cleanVarName(name)
        return resolveArrayRead(name) ?: localVariables[key] ?: MacroRuntime.variables[key]
    }

    private fun setVar(name: String?, value: String) {
        if (name.isNullOrBlank()) return
        if (setArrayValue(name, value)) return
        MacroRuntime.variables[MacroScriptParser.cleanVarName(name)] = value
    }

    private fun setLocalVar(name: String, value: String) {
        localVariables[MacroScriptParser.cleanVarName(name)] = value
    }

    private fun assignVar(rawArgs: List<String>) {
        val target = rawArgs.getOrNull(0) ?: return
        val expression = rawArgs.getOrNull(1).orEmpty()
        setVar(target, evalValue(expression))
    }

    private fun unsetVar(name: String?) {
        if (name.isNullOrBlank()) return
        parseArrayRef(name)?.let { ref ->
            if (ref.second == null) {
                MacroRuntime.arrays.remove(ref.first)
            } else {
                val arrayIndex = ref.second ?: return@let
                MacroRuntime.arrays[ref.first]?.let { array ->
                    if (arrayIndex in array.indices) array[arrayIndex] = ""
                }
            }
            return
        }
        MacroRuntime.variables.remove(MacroScriptParser.cleanVarName(name))
        localVariables.remove(MacroScriptParser.cleanVarName(name))
    }

    private fun toggleVar(name: String) {
        setVar(name, (!truthy(readVar(name))).toString())
    }

    private fun incVar(name: String?, amount: Int) {
        if (name.isNullOrBlank()) return
        val value = (readVar(name)?.toIntOrNull() ?: 0) + amount
        setVar(name, value.toString())
    }

    private fun randomVar(target: String?, maxArg: String?, minArg: String?) {
        val max = maxArg?.toIntOrNull() ?: 100
        val min = minArg?.toIntOrNull() ?: 0
        val low = min.coerceAtMost(max)
        val high = min.coerceAtLeast(max)
        val span = high.toLong() - low.toLong() + 1L
        val offset = if (span <= 1L) 0L else (random.nextDouble() * span).toLong().coerceIn(0L, span - 1L)
        setVar(target, (low + offset).toString())
    }

    private fun replaceVar(rawArgs: List<String>, regex: Boolean) {
        val target = rawArgs.getOrNull(0) ?: return
        val search = replaceVars(unquote(rawArgs.getOrNull(1).orEmpty()))
        val replacement = replaceVars(unquote(rawArgs.getOrNull(2).orEmpty()))
        val current = readVar(target).orEmpty()
        val next = if (regex) runCatching { current.replace(Regex(search), replacement) }.getOrDefault(current) else current.replace(search, replacement)
        setVar(target, next)
    }

    private fun matchVar(rawArgs: List<String>) {
        val value = replaceVars(unquote(rawArgs.getOrNull(0).orEmpty()))
        val regex = replaceVars(unquote(rawArgs.getOrNull(1).orEmpty()))
        val out = rawArgs.getOrNull(2) ?: return
        val group = replaceVars(unquote(rawArgs.getOrNull(3).orEmpty())).toIntOrNull() ?: 0
        val default = replaceVars(unquote(rawArgs.getOrNull(4).orEmpty()))
        val match = runCatching { Regex(regex).find(value) }.getOrNull()
        val result = match?.groups?.let { if (group in 0 until it.size) it[group]?.value else null } ?: match?.value ?: default
        setVar(out, result)
    }

    private fun timeVar(rawArgs: List<String>, args: List<String>) {
        val out = rawArgs.getOrNull(0) ?: return
        val format = args.getOrNull(1) ?: "HH:mm:ss"
        setVar(out, runCatching { SimpleDateFormat(format, Locale.ROOT).format(Date()) }.getOrDefault(""))
    }

    private fun unaryNumber(rawArgs: List<String>, args: List<String>, transform: (Double) -> Double) {
        setVar(rawArgs.getOrNull(1), formatNumber(transform(args.firstOrNull()?.toDoubleOrNull() ?: 0.0)))
    }

    private fun minMaxNumber(rawArgs: List<String>, args: List<String>, chooseMax: Boolean) {
        setVar(rawArgs.lastOrNull(), minMaxValue(args.dropLast(1), chooseMax).orEmpty())
    }

    private fun clampNumber(rawArgs: List<String>, args: List<String>) {
        val value = args.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val min = args.getOrNull(1)?.toDoubleOrNull() ?: value
        val max = args.getOrNull(2)?.toDoubleOrNull() ?: value
        setVar(rawArgs.getOrNull(3), formatNumber(value.coerceIn(min, max)))
    }

    private fun substringVar(rawArgs: List<String>, args: List<String>) {
        setVar(rawArgs.getOrNull(3), substringValue(args))
    }

    private fun stringTest(rawArgs: List<String>, args: List<String>, test: (String, String, Boolean) -> Boolean) {
        val ignoreCase = args.getOrNull(3)?.toBooleanStrictOrNull() ?: true
        setVar(rawArgs.getOrNull(2), test(args.getOrNull(0).orEmpty(), args.getOrNull(1).orEmpty(), ignoreCase).toString())
    }

    private fun minMaxValue(args: List<String>, chooseMax: Boolean): String? {
        val numbers = args.mapNotNull { it.toDoubleOrNull() }
        if (numbers.isEmpty()) return null
        return formatNumber(if (chooseMax) numbers.max() else numbers.min())
    }

    private fun substringValue(args: List<String>): String {
        val value = args.getOrNull(0).orEmpty()
        val start = (args.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, value.length)
        val length = args.getOrNull(2)?.toIntOrNull() ?: (value.length - start)
        return value.substring(start, (start + length).coerceIn(start, value.length))
    }

    private fun stopMacro(name: String?) {
        if (name.isNullOrBlank()) {
            runningTasks.remove(taskId)
            throw StopMacro()
        }
        MacroRuntime.stopMatching(name)
    }

    private fun transformString(rawArgs: List<String>, args: List<String>, transform: (String) -> String) {
        val value = args.firstOrNull().orEmpty()
        val target = rawArgs.getOrNull(1)
        if (target != null && isVariableRef(target)) {
            setVar(target, transform(value))
        } else if (rawArgs.firstOrNull()?.let(::isVariableRef) == true) {
            setVar(rawArgs.first(), transform(readVar(rawArgs.first()).orEmpty()))
        }
    }

    private fun inlineIf(rawArgs: List<String>, args: List<String>) {
        val out = rawArgs.getOrNull(3) ?: return
        setVar(out, if (evalCondition(rawArgs.getOrNull(0).orEmpty())) args.getOrNull(1).orEmpty() else args.getOrNull(2).orEmpty())
    }

    private fun arraySize(rawArgs: List<String>) {
        val ref = rawArgs.getOrNull(0) ?: return
        setVar(rawArgs.getOrNull(1), mutableArray(ref).size.toString())
    }

    private fun pushArray(rawArgs: List<String>, args: List<String>) {
        val array = mutableArray(rawArgs.getOrNull(0) ?: return)
        array += args.getOrNull(1).orEmpty()
    }

    private fun popArray(rawArgs: List<String>) {
        val array = mutableArray(rawArgs.getOrNull(0) ?: return)
        val value = if (array.isEmpty()) "" else array.removeAt(array.lastIndex)
        setVar(rawArgs.getOrNull(1), value)
    }

    private fun putArray(rawArgs: List<String>, args: List<String>) {
        val array = mutableArray(rawArgs.getOrNull(0) ?: return)
        val index = array.indexOfFirst { it.isBlank() }
        if (index >= 0) array[index] = args.getOrNull(1).orEmpty() else array += args.getOrNull(1).orEmpty()
    }

    private fun indexOfArray(rawArgs: List<String>, args: List<String>) {
        val array = mutableArray(rawArgs.getOrNull(0) ?: return)
        val value = args.getOrNull(2).orEmpty()
        val caseSensitive = args.getOrNull(3)?.toBooleanStrictOrNull() ?: false
        val index = array.indexOfFirst { it.equals(value, ignoreCase = !caseSensitive) }
        setVar(rawArgs.getOrNull(1), index.toString())
    }

    private fun joinArray(rawArgs: List<String>, args: List<String>) {
        val glue = args.getOrNull(0).orEmpty()
        val array = mutableArray(rawArgs.getOrNull(1) ?: return)
        setVar(rawArgs.getOrNull(2), array.joinToString(glue))
    }

    private fun splitArray(rawArgs: List<String>, args: List<String>) {
        val delimiter = args.getOrNull(0).orEmpty()
        val value = args.getOrNull(1).orEmpty()
        val array = mutableArray(rawArgs.getOrNull(2) ?: return)
        array.clear()
        array += if (delimiter.isEmpty()) value.map { it.toString() } else value.split(delimiter)
    }

    private fun itemId(rawArgs: List<String>, args: List<String>) {
        val item = itemFromDescriptor(args.firstOrNull().orEmpty())
        setVar(rawArgs.getOrNull(1), item?.let { BuiltInRegistries.ITEM.getId(it).toString() } ?: "0")
    }

    private fun itemName(rawArgs: List<String>, args: List<String>) {
        val item = args.firstOrNull()?.toIntOrNull()?.let { BuiltInRegistries.ITEM.byId(it) } ?: itemFromDescriptor(args.firstOrNull().orEmpty())
        setVar(rawArgs.getOrNull(1), item?.let { BuiltInRegistries.ITEM.getKey(it).toString() }.orEmpty())
    }

    private fun getItemInfo(rawArgs: List<String>, args: List<String>) {
        val item = itemFromDescriptor(args.firstOrNull().orEmpty()) ?: return
        setVar(rawArgs.getOrNull(1), item.name.string)
        setVar(rawArgs.getOrNull(2), item.defaultMaxStackSize.toString())
        setVar(rawArgs.getOrNull(3), "item")
        setVar(rawArgs.getOrNull(4), BuiltInRegistries.ITEM.getKey(item).toString())
    }

    private fun trace(args: List<String>) {
        hitVar("HIT")
    }

    private fun getNearestNametag(rawArgs: List<String>, args: List<String>) {
        val output = rawArgs.getOrNull(0)
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) {
            setNearestNametagVars(output, null)
            return
        }

        val radius = (args.getOrNull(1)?.toDoubleOrNull() ?: 16.0).coerceAtLeast(0.0)
        val includePlayers = truthy(args.getOrNull(2))
        val filter = ClientUtils.stripColors(args.getOrNull(3).orEmpty()).trim()
        val radiusSqr = radius * radius
        val nearest = onMain<NearestNametag?>(null) {
            val searchBox = player.boundingBox.inflate(radius)
            level.getEntities(player, searchBox).asSequence()
            .mapNotNull { entity ->
                val text = nametagText(entity, includePlayers)
                if (text.isBlank()) return@mapNotNull null
                if (filter.isNotEmpty() && !ClientUtils.stripColors(text).contains(filter, ignoreCase = true)) return@mapNotNull null

                val distanceSqr = player.distanceToSqr(entity.x, entity.y, entity.z)
                if (distanceSqr > radiusSqr) null else NearestNametag(entity, text, distanceSqr)
            }
            .minByOrNull { it.distanceSqr }
        }

        setNearestNametagVars(output, nearest)
    }

    private fun nametagText(entity: Entity, includePlayers: Boolean): String {
        val customName = entity.customName?.string.orEmpty()
        if (customName.isNotBlank()) return customName
        return if (includePlayers && entity is Player) entity.gameProfile.name else ""
    }

    private fun setNearestNametagVars(output: String?, nearest: NearestNametag?) {
        val text = nearest?.text.orEmpty()
        setVar(output, text)
        setVar("NEARESTNAMETAG", text)
        setVar("NEARESTNAMETAGCLEAN", ClientUtils.stripColors(text))
        setVar("NEARESTNAMETAGDISTANCE", nearest?.let { sqrt(it.distanceSqr).toString() }.orEmpty())
        setVar("NEARESTNAMETAGUUID", nearest?.entity?.uuid?.toString().orEmpty())
        setVar("NEARESTNAMETAGTYPE", nearest?.entity?.let { BuiltInRegistries.ENTITY_TYPE.getKey(it.type).toString() }.orEmpty())
        setVar("NEARESTNAMETAGX", nearest?.entity?.x?.toString().orEmpty())
        setVar("NEARESTNAMETAGY", nearest?.entity?.y?.toString().orEmpty())
        setVar("NEARESTNAMETAGZ", nearest?.entity?.z?.toString().orEmpty())
    }

    private fun evalValue(raw: String): String {
        val value = replaceVars(unquote(raw.trim()))
        evalFunctionValue(value)?.let { return it }
        evalNumericExpression(value)?.let { return formatNumber(it) }
        return value
    }

    private fun evalFunctionValue(value: String): String? {
        val statement = MacroScriptParser.parse(value).singleOrNull() ?: return null
        val args = statement.args.map { evalValue(it) }
        return when (statement.name) {
            "ABS" -> formatNumber(abs(args.firstOrNull()?.toDoubleOrNull() ?: 0.0))
            "FLOOR" -> formatNumber(floor(args.firstOrNull()?.toDoubleOrNull() ?: 0.0))
            "CEIL", "CEILING" -> formatNumber(ceil(args.firstOrNull()?.toDoubleOrNull() ?: 0.0))
            "ROUND" -> formatNumber((args.firstOrNull()?.toDoubleOrNull() ?: 0.0).roundToInt().toDouble())
            "MIN" -> minMaxValue(args, chooseMax = false)
            "MAX" -> minMaxValue(args, chooseMax = true)
            "CLAMP" -> (args.getOrNull(0)?.toDoubleOrNull() ?: 0.0).coerceIn(args.getOrNull(1)?.toDoubleOrNull() ?: 0.0, args.getOrNull(2)?.toDoubleOrNull() ?: 0.0).let(::formatNumber)
            "LEN", "LENGTH" -> args.firstOrNull().orEmpty().length.toString()
            "TRIM" -> args.firstOrNull().orEmpty().trim()
            "SUBSTR", "SUBSTRING" -> substringValue(args)
            "CONTAINS" -> (args.size >= 2 && args[0].contains(args[1], ignoreCase = true)).toString()
            "BEGINSWITH", "STARTSWITH" -> (args.size >= 2 && args[0].startsWith(args[1], ignoreCase = true)).toString()
            "ENDSWITH" -> (args.size >= 2 && args[0].endsWith(args[1], ignoreCase = true)).toString()
            "IIF" -> if (evalCondition(statement.args.getOrNull(0).orEmpty())) args.getOrNull(1).orEmpty() else args.getOrNull(2).orEmpty()
            "ISRUNNING" -> MacroRuntime.isRunning(args.firstOrNull().orEmpty()).toString()
            "ITEMID" -> itemFromDescriptor(args.firstOrNull().orEmpty())?.let { BuiltInRegistries.ITEM.getId(it).toString() }
            "ITEMNAME" -> args.firstOrNull()?.toIntOrNull()?.let { BuiltInRegistries.ITEM.byId(it) }?.let { BuiltInRegistries.ITEM.getKey(it).toString() }
            "DISTANCE" -> distanceValue(args)
            else -> null
        }
    }

    private fun execFile(args: List<String>) {
        val file = args.firstOrNull() ?: return
        val script = MacroStorage.readScriptFile(file)
        if (script == null) {
            ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Script file not found: \u00A7f$file")
            return
        }
        val locals = args.drop(2).mapIndexed { index, value -> index.toString() to value }.toMap()
        MacroScriptEngine("$taskId/$file", runningTasks, locals).execute(script)
    }

    private fun waitInventoryFull() {
        var nextNoticeAt = 0L
        while (!InventoryFullDetector.isClientInventoryFull(mc)) {
            val now = System.currentTimeMillis()
            if (now >= nextNoticeAt) {
                ClientUtils.displayChatMessage("\u00A7e[MacroEngine] Waiting for inventory to be full.")
                nextNoticeAt = now + 5000L
            }
            sleepInterruptibly(250L)
        }
    }

    private fun goTo(args: List<String>) {
        val waypoint = RouteWaypoint(
            x = args.getOrNull(0)?.toDoubleOrNull() ?: return,
            y = args.getOrNull(1)?.toDoubleOrNull() ?: return,
            z = args.getOrNull(2)?.toDoubleOrNull() ?: return,
            radius = args.getOrNull(3)?.toDoubleOrNull() ?: 1.25,
            timeoutMillis = args.getOrNull(4)?.toLongOrNull() ?: 15000L,
            sprint = args.getOrNull(5)?.toBooleanStrictOrNull() ?: true
        )
        val navigator = RouteNavigator(mc) { runningTasks.contains(taskId) }
        if (!navigator.goTo(waypoint)) {
            if (!runningTasks.contains(taskId)) throw StopMacro()
            pauseMacro("route blocked")
        }
    }

    private fun pauseMacro(reason: String) {
        RouteNavigator(mc).releaseMovement()
        ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Step builder paused: \u00A7f$reason")
        throw StopMacro()
    }

    private fun key(bind: String?, down: Boolean? = null, pulse: Boolean = false) {
        if (bind.isNullOrBlank()) return
        val binding = keyBinding(bind)
        schedule {
            if (binding != null) {
                // Mirror a real key press: vanilla sets isDown AND increments the click counter.
                // Click-driven actions (use/right-click, inventory, drop, hotbar, swap) only fire via
                // KeyMapping.click(); setting isDown alone — the old behaviour — never triggered them.
                when {
                    pulse -> {
                        binding.isDown = true
                        runCatching { KeyMapping.click(InputConstants.getKey(binding.saveString())) }
                        binding.isDown = false
                    }
                    down == true -> {
                        binding.isDown = true
                        runCatching { KeyMapping.click(InputConstants.getKey(binding.saveString())) }
                    }
                    down == false -> binding.isDown = false
                }
            } else {
                if (pulse) KeyboardUtils.pulse(bind) else if (down != null) KeyboardUtils.set(bind, down)
            }
        }
    }

    private fun toggleKey(bind: String?) {
        if (bind.isNullOrBlank()) return
        val binding = keyBinding(bind)
        schedule {
            if (binding != null) binding.isDown = !binding.isDown else KeyboardUtils.set(bind, !KeyboardUtils.isInputPressed(bind))
        }
    }

    private fun press(keyName: String?) {
        if (!keyName.isNullOrBlank()) schedule { KeyboardUtils.pulse(keyName) }
    }

    private fun typeText(text: String) {
        schedule { mc.setScreen(ChatScreen(text, false)) }
    }

    private fun slot(rawSlot: String?) {
        val slot = rawSlot?.toIntOrNull() ?: return
        schedule {
            val inventory = mc.player?.inventory ?: return@schedule
            inventory.setSelectedSlot((slot - 1).coerceIn(0, 8))
        }
    }

    private fun scrollSlot(delta: Int) {
        schedule {
            val inventory = mc.player?.inventory ?: return@schedule
            inventory.setSelectedSlot(Math.floorMod(inventory.getSelectedSlot() + delta, 9))
        }
    }

    private fun pick(items: List<String>) {
        schedule {
            val inventory = mc.player?.inventory ?: return@schedule
            for (slot in 0..8) {
                val stack = inventory.getNonEquipmentItems().getOrNull(slot) ?: continue
                if (!stack.isEmpty && items.any { itemMatches(stack, it) }) {
                    inventory.setSelectedSlot(slot)
                    return@schedule
                }
            }
        }
    }

    private fun getSlot(rawArgs: List<String>, args: List<String>) {
        val item = args.getOrNull(0).orEmpty()
        val start = (args.getOrNull(2)?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val slot = onMain(-1) {
            mc.player?.inventory?.getNonEquipmentItems().orEmpty().drop(start).indexOfFirst { !it.isEmpty && itemMatches(it, item) }
        }
        setVar(rawArgs.getOrNull(1), if (slot < 0) "-1" else (slot + start).toString())
    }

    private fun getSlotItem(rawArgs: List<String>, args: List<String>) {
        val slot = args.firstOrNull()?.toIntOrNull() ?: return
        val stack = mc.player?.inventory?.getNonEquipmentItems()?.getOrNull(slot) ?: ItemStack.EMPTY
        setVar(rawArgs.getOrNull(1), if (stack.isEmpty) "air" else BuiltInRegistries.ITEM.getId(stack.item).toString())
        setVar(rawArgs.getOrNull(2), if (stack.isEmpty) "0" else stack.count.toString())
        setVar(rawArgs.getOrNull(3), if (stack.isEmpty) "0" else stack.damageValue.toString())
    }

    private fun slotClick(args: List<String>) {
        val slot = args.getOrNull(0)?.toIntOrNull() ?: return
        val button = args.getOrNull(1)?.toIntOrNull() ?: 0
        val shift = args.getOrNull(2)?.toBooleanStrictOrNull() ?: false
        schedule {
            val player = mc.player ?: return@schedule
            val gameMode = mc.gameMode ?: return@schedule
            val menu = player.containerMenu
            gameMode.handleInventoryMouseClick(menu.containerId, slot, button, if (shift) ClickType.QUICK_MOVE else ClickType.PICKUP, player)
        }
    }

    private fun slotClickItem(args: List<String>) {
        val item = args.firstOrNull().orEmpty()
        val button = args.getOrNull(1)?.toIntOrNull() ?: 0
        val shift = args.getOrNull(2)?.toBooleanStrictOrNull() ?: false
        schedule {
            val player = mc.player ?: return@schedule
            val gameMode = mc.gameMode ?: return@schedule
            val menu = player.containerMenu
            val index = menu.slots.indexOfFirst { !it.item.isEmpty && itemMatches(it.item, item) }
            if (index >= 0) gameMode.handleInventoryMouseClick(menu.containerId, index, button, if (shift) ClickType.QUICK_MOVE else ClickType.PICKUP, player)
        }
    }

    private fun getId(rawArgs: List<String>, args: List<String>, relative: Boolean) {
        val pos = blockPos(args, relative) ?: return
        val state = mc.level?.getBlockState(pos) ?: return
        setVar(rawArgs.getOrNull(3), Block.getId(state).toString())
        setVar(rawArgs.getOrNull(4), "0")
    }

    private fun getBlockInfo(rawArgs: List<String>, args: List<String>, relative: Boolean) {
        val pos = blockPos(args, relative) ?: return
        val state = mc.level?.getBlockState(pos) ?: return
        val block = state.block
        setVar(rawArgs.getOrNull(3), Block.getId(state).toString())
        setVar(rawArgs.getOrNull(4), "0")
        setVar(rawArgs.getOrNull(5), block.name.string)
        setVar(rawArgs.getOrNull(6), BuiltInRegistries.BLOCK.getKey(block).toString())
    }

    private fun calcYawTo(rawArgs: List<String>, args: List<String>) {
        val aim = calculateAim(listOf(args.getOrNull(0).orEmpty(), mc.player?.eyeY?.toString().orEmpty(), args.getOrNull(1).orEmpty())) ?: return
        setVar(rawArgs.getOrNull(2), aim.yaw.toString())
        setVar(rawArgs.getOrNull(3), aim.distance.toInt().toString())
    }

    private fun calcPitchTo(rawArgs: List<String>, args: List<String>) {
        val aim = calculateAim(args) ?: return
        setVar(rawArgs.getOrNull(3), aim.pitch.toString())
        setVar(rawArgs.getOrNull(4), aim.distance.toInt().toString())
    }

    private fun calcAimTo(rawArgs: List<String>, args: List<String>) {
        val aim = calculateAim(args) ?: return
        setVar(rawArgs.getOrNull(3), aim.yaw.toString())
        setVar(rawArgs.getOrNull(4), aim.pitch.toString())
        setVar(rawArgs.getOrNull(5), aim.distance.toInt().toString())
    }

    private fun distanceTo(rawArgs: List<String>, args: List<String>) {
        setVar(rawArgs.getOrNull(3), distanceValue(args).orEmpty())
    }

    private fun distanceValue(args: List<String>): String? {
        val player = mc.player ?: return null
        val x = args.getOrNull(0)?.toDoubleOrNull() ?: return null
        val y = args.getOrNull(1)?.toDoubleOrNull() ?: return null
        val z = args.getOrNull(2)?.toDoubleOrNull() ?: return null
        return sqrt(player.distanceToSqr(x, y, z)).toInt().toString()
    }

    private fun aimTo(args: List<String>) {
        val aim = calculateAim(args) ?: return
        applyLook(aim.yaw, aim.pitch, parseLookTicks(args.getOrNull(3)))
    }

    private fun calculateAim(args: List<String>): AimResult? {
        val player = mc.player ?: return null
        val x = args.getOrNull(0)?.toDoubleOrNull() ?: return null
        val y = args.getOrNull(1)?.toDoubleOrNull() ?: return null
        val z = args.getOrNull(2)?.toDoubleOrNull() ?: return null
        val eye = player.eyePosition
        val dx = x - eye.x
        val dy = y - eye.y
        val dz = z - eye.z
        val horizontal = sqrt(dx * dx + dz * dz)
        val yaw = (atan2(dz, dx) * 180.0 / Math.PI - 90.0).toFloat()
        val pitch = (-(atan2(dy, horizontal) * 180.0 / Math.PI)).toFloat()
        return AimResult(yaw, pitch, sqrt(dx * dx + dy * dy + dz * dz))
    }

    private fun look(args: List<String>, smooth: Boolean) {
        val player = mc.player ?: return
        val yaw = parseAngle(player.yRot, args.getOrNull(0).orEmpty())
        val pitch = parseAngle(player.xRot, args.getOrNull(1).orEmpty()).coerceIn(-90f, 90f)
        applyLook(yaw, pitch, if (smooth) parseLookTicks(args.getOrNull(2)) else 0)
    }

    private fun parseLookTicks(value: String?): Int {
        if (value.isNullOrBlank()) return 0
        val ms = parseWait(value)
        return if (value.endsWith("ms", ignoreCase = true) || value.endsWith("s", ignoreCase = true)) (ms / 50L).toInt().coerceAtLeast(1) else value.removeSuffix("t").toIntOrNull() ?: 0
    }

    private fun applyLook(targetYaw: Float, targetPitch: Float, ticks: Int) {
        if (ticks <= 0) {
            schedule {
                mc.player?.yRot = targetYaw
                mc.player?.xRot = targetPitch
            }
            return
        }

        val startYaw = mc.player?.yRot ?: targetYaw
        val startPitch = mc.player?.xRot ?: targetPitch
        for (tick in 1..ticks) {
            val progress = tick.toFloat() / ticks.toFloat()
            schedule {
                mc.player?.yRot = startYaw + (targetYaw - startYaw) * progress
                mc.player?.xRot = startPitch + (targetPitch - startPitch) * progress
            }
            sleepInterruptibly(50)
        }
    }

    private fun setOption(value: String?, option: String) {
        val number = value?.toDoubleOrNull() ?: return
        schedule {
            when (option) {
                "FOV" -> mc.options.fov().set(number.roundToInt().coerceIn(30, 110))
                "GAMMA" -> mc.options.gamma().set(if (number > 10) number / 100.0 else number)
                "SENSITIVITY" -> mc.options.sensitivity().set(if (number > 10) number / 100.0 else number)
            }
            mc.options.save()
        }
    }

    private fun setVolume(args: List<String>) {
        val volume = (args.getOrNull(0)?.toDoubleOrNull() ?: return).let { if (it > 1.0) it / 100.0 else it }.coerceIn(0.0, 1.0)
        val source = soundCategory(args.getOrNull(1)) ?: SoundSource.MASTER
        schedule {
            mc.options.getSoundSourceOptionInstance(source).set(volume)
            mc.options.save()
        }
    }

    private fun setChatOption(value: String?, option: String) {
        val number = value?.toDoubleOrNull() ?: return
        val normalized = if (number > 1.0) number / 100.0 else number
        schedule {
            when (option) {
                "CHATHEIGHT" -> mc.options.chatHeightUnfocused().set(normalized.coerceIn(0.0, 1.0))
                "CHATHEIGHTFOCUSED" -> mc.options.chatHeightFocused().set(normalized.coerceIn(0.0, 1.0))
                "CHATOPACITY" -> mc.options.chatOpacity().set(normalized.coerceIn(0.0, 1.0))
                "CHATSCALE" -> mc.options.chatScale().set(normalized.coerceIn(0.0, 1.0))
                "CHATWIDTH" -> mc.options.chatWidth().set(normalized.coerceIn(0.0, 1.0))
            }
            mc.options.save()
        }
    }

    private fun chatVisible(value: String?) {
        val visible = truthy(value)
        schedule {
            val type = if (visible) net.minecraft.world.entity.player.ChatVisiblity.FULL else net.minecraft.world.entity.player.ChatVisiblity.HIDDEN
            mc.options.chatVisibility().set(type)
            mc.options.save()
        }
    }

    private fun bindKey(args: List<String>) {
        val binding = keyBinding(args.getOrNull(0).orEmpty()) ?: return
        val key = KeyboardUtils.inputKey(args.getOrNull(1).orEmpty()) ?: InputConstants.UNKNOWN
        schedule {
            binding.setKey(key)
            KeyMapping.resetMapping()
            mc.options.save()
        }
    }

    private fun camera(value: String?) {
        val type = when (value?.lowercase(Locale.ROOT)) {
            "third", "back", "3", "thirdperson" -> CameraType.THIRD_PERSON_BACK
            "front", "selfie", "2" -> CameraType.THIRD_PERSON_FRONT
            else -> CameraType.FIRST_PERSON
        }
        schedule {
            mc.options.cameraType = type
        }
    }

    private fun title(args: List<String>) {
        val title = args.getOrNull(0).orEmpty()
        val subtitle = args.getOrNull(1).orEmpty()
        schedule {
            mc.gui.setTitle(Component.literal(ClientUtils.color(title)))
            if (subtitle.isNotBlank()) mc.gui.setSubtitle(Component.literal(ClientUtils.color(subtitle)))
        }
    }

    private fun gui(name: String?) {
        when (name?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            "", "close", "game", "none" -> schedule { mc.setScreen(null) }
            "macro", "macros", "macroengine" -> schedule { mc.setScreen(MacroScreen(mc.screen)) }
            "inventory", "inv" -> schedule {
                val player = mc.player ?: return@schedule
                mc.setScreen(InventoryScreen(player))
            }
            "chat" -> schedule { mc.setScreen(ChatScreen("", false)) }
        }
    }

    private fun bindGui(args: List<String>) {
        if (args.size >= 2) MacroRuntime.guiProperties[args[0]] = args[1]
    }

    private fun playSound(args: List<String>) {
        val id = Identifier.tryParse(args.firstOrNull().orEmpty()) ?: Identifier.withDefaultNamespace(args.firstOrNull().orEmpty())
        val event = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null) ?: return
        val volume = args.getOrNull(1)?.toFloatOrNull() ?: 1f
        val pitch = args.getOrNull(2)?.toFloatOrNull() ?: 1f
        schedule {
            mc.soundManager.play(SimpleSoundInstance.forUI(event, pitch, volume))
        }
    }

    private fun config(name: String?) {
        MacroRuntime.currentConfigName = name?.ifBlank { "default" } ?: "default"
    }

    private fun logTo(args: List<String>) {
        val target = args.getOrNull(0).orEmpty()
        val text = args.drop(1).joinToString(",")
        if (target.isBlank()) return
        if (target.contains('.') || target.endsWith(".txt", ignoreCase = true)) {
            val file = MacroStorage.scriptFile(target.removeSuffix(".txt"))
            file.appendText(text + System.lineSeparator(), Charsets.UTF_8)
        } else {
            MacroRuntime.guiProperties[target] = text
        }
    }

    private fun prompt(rawArgs: List<String>, args: List<String>) {
        setVar(rawArgs.getOrNull(0), args.getOrNull(3) ?: args.getOrNull(2).orEmpty())
    }

    private fun store(args: List<String>, overwrite: Boolean) {
        val type = args.getOrNull(0)?.uppercase(Locale.ROOT) ?: return
        val value = args.getOrNull(1).orEmpty()
        val store = MacroRuntime.stores.computeIfAbsent(type) { java.util.concurrent.CopyOnWriteArrayList<String>() }
        if (overwrite) store.remove(value)
        store += value
    }

    private fun getProperty(rawArgs: List<String>, args: List<String>) {
        setVar(rawArgs.getOrNull(1), MacroRuntime.guiProperties[args.firstOrNull().orEmpty()].orEmpty())
    }

    private fun setProperty(args: List<String>) {
        if (args.size >= 2) MacroRuntime.guiProperties[args[0]] = args[1]
    }

    private fun setLabel(args: List<String>) {
        if (args.size >= 2) MacroRuntime.labels[args[0]] = args[1]
    }

    private fun keyBinding(bind: String): KeyMapping? {
        val key = bind.trim().lowercase(Locale.ROOT)
        val options = mc.options
        return when (key) {
            "attack", "leftclick", "mouse1" -> options.keyAttack
            "use", "rightclick", "mouse2" -> options.keyUse
            "jump" -> options.keyJump
            "sneak", "shift" -> options.keyShift
            "sprint" -> options.keySprint
            "forward", "forwards", "up" -> options.keyUp
            "back", "backward", "down" -> options.keyDown
            "left" -> options.keyLeft
            "right" -> options.keyRight
            "drop" -> options.keyDrop
            "inventory" -> options.keyInventory
            "chat" -> options.keyChat
            "command" -> options.keyCommand
            "playerlist", "tab" -> options.keyPlayerList
            "pickblock", "pick" -> options.keyPickItem
            "swapoffhand", "offhand" -> options.keySwapOffhand
            else -> options.keyMappings.firstOrNull { it.name.equals(bind, ignoreCase = true) }
        }
    }

    private fun expandInlineIncludes(script: String): String {
        val matcher = INCLUDE_PATTERN.matcher(script)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val include = MacroStorage.readScriptFile(matcher.group(1).trim()).orEmpty()
            matcher.appendReplacement(buffer, MatcherQuote.quote(include))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    private fun expandParameters(script: String): String {
        var result = script
        val params = mutableListOf<String>()
        val listMatcher = PARAM_LIST_PATTERN.matcher(result)
        if (listMatcher.find()) {
            params += MacroScriptParser.splitArgs(listMatcher.group(1)).map { replaceVars(unquote(it.trim())) }
            result = listMatcher.replaceFirst("")
        }
        val namedMatcher = PARAM_NAMED_PATTERN.matcher(result)
        val namedBuffer = StringBuffer()
        while (namedMatcher.find()) {
            val name = MacroScriptParser.cleanVarName(namedMatcher.group(1))
            namedMatcher.appendReplacement(namedBuffer, MatcherQuote.quote(localVariables[name].orEmpty()))
        }
        namedMatcher.appendTail(namedBuffer)
        result = namedBuffer.toString()

        params.forEachIndexed { index, value -> result = result.replace("$" + "$" + index, value) }
        return PARAM_TYPED_PATTERN.replace(result) { parameterValue(it.groupValues[1]) }
    }

    private fun parameterValue(type: String): String {
        return when (type.lowercase(Locale.ROOT)) {
            "i" -> random.nextInt(100).toString()
            "s" -> mc.player?.gameProfile?.name.orEmpty()
            "x" -> mc.player?.x?.toInt()?.toString().orEmpty()
            "y" -> mc.player?.y?.toInt()?.toString().orEmpty()
            "z" -> mc.player?.z?.toInt()?.toString().orEmpty()
            else -> ""
        }
    }

    private fun parseWait(value: String): Long {
        val raw = value.trim().lowercase(Locale.ROOT)
        return when {
            raw.endsWith("ms") -> raw.removeSuffix("ms").toLongOrNull() ?: 0L
            raw.endsWith("t") -> (raw.removeSuffix("t").toLongOrNull() ?: 0L) * 50L
            raw.endsWith("s") -> ((raw.removeSuffix("s").toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
            else -> ((raw.toDoubleOrNull() ?: 1.0) * 1000.0).toLong()
        }.coerceAtLeast(0L)
    }

    private fun doCount(statement: MacroStatement): Int {
        return statement.args.getOrNull(0)?.let { evalValue(it).toIntOrNull() } ?: -1
    }

    private fun sendChat(message: String) {
        ClientUtils.sendChat(ClientUtils.color(message))
    }

    private fun sleepInterruptibly(totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0) {
            if (!runningTasks.contains(taskId)) throw StopMacro()
            val chunk = remaining.coerceAtMost(50L)
            Thread.sleep(chunk)
            remaining -= chunk
        }
    }

    private fun waitUntil(rawCondition: String, timeoutArg: String? = null) {
        val deadline = waitDeadline(timeoutArg)
        while (true) {
            if (!runningTasks.contains(taskId)) throw StopMacro()
            if (evalCondition(rawCondition)) break
            if (deadline != 0L && System.currentTimeMillis() >= deadline) break
            Thread.sleep(50L)
        }
    }

    private fun waitWhile(rawCondition: String, timeoutArg: String? = null) {
        val deadline = waitDeadline(timeoutArg)
        while (true) {
            if (!runningTasks.contains(taskId)) throw StopMacro()
            if (!evalCondition(rawCondition)) break
            if (deadline != 0L && System.currentTimeMillis() >= deadline) break
            Thread.sleep(50L)
        }
    }

    private fun waitDeadline(timeoutArg: String?): Long {
        val timeout = timeoutArg?.takeIf { it.isNotBlank() }?.let { parseWait(it) } ?: 0L
        return if (timeout > 0L) System.currentTimeMillis() + timeout else 0L
    }

    private fun schedule(action: () -> Unit) {
        mc.execute(Runnable { action() })
    }


    private fun <T> onMain(fallback: T, block: () -> T): T {
        if (mc.isSameThread) return runCatching(block).getOrDefault(fallback)
        val future = java.util.concurrent.CompletableFuture<T>()
        mc.execute { future.complete(runCatching(block).getOrDefault(fallback)) }
        return runCatching { future.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(fallback)
    }

    private fun unquote(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length >= 2 && ((trimmed.first() == '"' && trimmed.last() == '"') || (trimmed.first() == '\'' && trimmed.last() == '\''))) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun truthy(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        return text.isNotBlank() && !text.equals("false", ignoreCase = true) && text != "0" && !text.equals("none", ignoreCase = true) && !text.equals("null", ignoreCase = true)
    }

    private fun splitBoolean(expr: String, op: String): List<String>? {
        val parts = splitByToken(expr, op)
        return if (parts.size > 1) parts else null
    }

    private fun splitByToken(expr: String, token: String): List<String> {
        val result = mutableListOf<String>()
        var quote: Char? = null
        var depth = 0
        var index = 0
        var start = 0
        while (index < expr.length) {
            val char = expr[index]
            if (quote != null) {
                if (char == quote) quote = null
                index++
                continue
            }
            when (char) {
                '"', '\'' -> quote = char
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
            }
            if (depth == 0 && expr.startsWith(token, index)) {
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

    private fun heldItem(): ItemStack? {
        val stack = mc.player?.mainHandItem
        return if (stack == null || stack.isEmpty) null else stack
    }

    private fun durabilityPercent(stack: ItemStack): Int {
        val maxDamage = stack.maxDamage
        if (!stack.isDamageableItem || maxDamage <= 0) return 100
        val remaining = (maxDamage - stack.damageValue).coerceIn(0, maxDamage)
        return ((remaining.toDouble() / maxDamage.toDouble()) * 100.0).toInt()
    }

    private fun armorVar(key: String): String? {
        val player = mc.player ?: return null
        val stack = when {
            key.startsWith("BOOTS") -> player.getItemBySlot(EquipmentSlot.FEET)
            key.startsWith("LEGGINGS") -> player.getItemBySlot(EquipmentSlot.LEGS)
            key.startsWith("CHESTPLATE") -> player.getItemBySlot(EquipmentSlot.CHEST)
            key.startsWith("HELM") -> player.getItemBySlot(EquipmentSlot.HEAD)
            else -> null
        } ?: return null

        if (stack.isEmpty) return ""
        return when {
            key.endsWith("ID") -> BuiltInRegistries.ITEM.getId(stack.item).toString()
            key.endsWith("NAME") -> stack.hoverName.string
            key.endsWith("DAMAGE") -> stack.damageValue.toString()
            key.endsWith("DURABILITY") -> (stack.maxDamage - stack.damageValue).toString()
            else -> null
        }
    }

    private fun hitVar(key: String): String? {
        val hit = mc.hitResult ?: return null
        return when (key) {
            "HIT" -> hit.type.name
            "HITX" -> (hit as? BlockHitResult)?.blockPos?.x?.toString().orEmpty()
            "HITY" -> (hit as? BlockHitResult)?.blockPos?.y?.toString().orEmpty()
            "HITZ" -> (hit as? BlockHitResult)?.blockPos?.z?.toString().orEmpty()
            "HITSIDE" -> (hit as? BlockHitResult)?.direction?.name.orEmpty()
            "HITID" -> (hit as? BlockHitResult)?.blockPos?.let { mc.level?.getBlockState(it)?.let(Block::getId)?.toString() }.orEmpty()
            "HITNAME" -> (hit as? BlockHitResult)?.blockPos?.let { mc.level?.getBlockState(it)?.block?.name?.string }.orEmpty()
            else -> null
        }
    }

    private fun parseAngle(base: Float, raw: String): Float {
        return if (raw.startsWith("+") || raw.startsWith("-")) base + (raw.toFloatOrNull() ?: 0f) else raw.toFloatOrNull() ?: base
    }

    private fun findVariableEnd(text: String, start: Int): Int {
        var quote: Char? = null
        var depth = 0
        var index = start
        while (index < text.length) {
            val char = text[index]
            if (quote != null) {
                if (char == quote) quote = null
                index++
                continue
            }
            when (char) {
                '"', '\'' -> quote = char
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
        val name = MacroScriptParser.cleanVarName(text.substring(0, open))
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
        val name = parseArrayRef(raw)?.first ?: MacroScriptParser.cleanVarName(raw)
        return MacroRuntime.arrays.computeIfAbsent(name) { java.util.concurrent.CopyOnWriteArrayList<String>() }
    }

    private fun isVariableRef(raw: String): Boolean {
        val text = raw.trim()
        return text.startsWith("&") || text.startsWith("#") || text.startsWith("@") || (text.startsWith("%") && text.endsWith("%"))
    }

    private fun isBareIdentifier(raw: String): Boolean {
        val text = raw.trim()
        return text.isNotEmpty() && text.first().let { it == '_' || it.isLetter() } && text.drop(1).all { it == '_' || it.isLetterOrDigit() }
    }

    private fun iteratorRows(rawIterator: String): List<Map<String, String>> {
        val iterator = replaceVars(rawIterator).substringBefore('(').substringBefore(':').trim().lowercase(Locale.ROOT)
        return onMain(emptyList<Map<String, String>>()) {
        val player = mc.player
        when (iterator) {
            "players" -> mc.connection?.listedOnlinePlayers?.mapIndexed { index, info ->
                mapOf("INDEX" to index.toString(), "PLAYER" to info.profile.name, "PLAYERNAME" to info.profile.name, "UUID" to info.profile.id.toString())
            }.orEmpty()
            "running" -> MacroRuntime.runningIds().mapIndexed { index, id -> mapOf("INDEX" to index.toString(), "MACRO" to id, "VALUE" to id) }
            "env" -> MacroRuntime.variables.entries.mapIndexed { index, entry -> mapOf("INDEX" to index.toString(), "VARNAME" to entry.key, "VALUE" to entry.value) }
            "effects" -> player?.activeEffects?.mapIndexed { index, effect ->
                val id = BuiltInRegistries.MOB_EFFECT.getKey(effect.effect.value()).toString()
                mapOf("INDEX" to index.toString(), "EFFECT" to id, "EFFECTID" to id, "EFFECTDURATION" to effect.duration.toString(), "EFFECTAMPLIFIER" to effect.amplifier.toString())
            }.orEmpty()
            "inventory" -> inventoryRows(hotbarOnly = false)
            "hotbar" -> inventoryRows(hotbarOnly = true)
            "container", "slots" -> containerRows()
            "properties" -> propertyRows()
            "controls" -> MacroRuntime.guiProperties.entries.mapIndexed { index, entry -> mapOf("INDEX" to index.toString(), "CONTROL" to entry.key, "VALUE" to entry.value) }
            else -> emptyList()
        }
        }
    }

    private fun inventoryRows(hotbarOnly: Boolean): List<Map<String, String>> {
        val inventory = mc.player?.inventory?.getNonEquipmentItems() ?: return emptyList()
        val slots = if (hotbarOnly) 0..8 else inventory.indices
        return slots.mapNotNull { slot ->
            val stack = inventory.getOrNull(slot) ?: return@mapNotNull null
            if (stack.isEmpty) null else itemRow(slot, slot, stack)
        }
    }

    private fun containerRows(): List<Map<String, String>> {
        val slots = mc.player?.containerMenu?.slots ?: return emptyList()
        return slots.mapIndexedNotNull { index, slot ->
            val stack = slot.item
            if (stack.isEmpty) null else itemRow(index, slot.containerSlot, stack)
        }
    }

    private fun itemRow(index: Int, slot: Int, stack: ItemStack): Map<String, String> {
        val item = stack.item
        val maxDamage = stack.maxDamage
        val durability = if (stack.isDamageableItem) (maxDamage - stack.damageValue).coerceAtLeast(0) else 0
        return mapOf(
            "INDEX" to index.toString(),
            "SLOT" to slot.toString(),
            "ITEM" to BuiltInRegistries.ITEM.getId(item).toString(),
            "ITEMID" to BuiltInRegistries.ITEM.getId(item).toString(),
            "ITEMCODE" to BuiltInRegistries.ITEM.getKey(item).toString(),
            "ITEMNAME" to stack.hoverName.string,
            "STACKSIZE" to stack.count.toString(),
            "DAMAGE" to stack.damageValue.toString(),
            "MAXDAMAGE" to maxDamage.toString(),
            "DURABILITY" to durability.toString(),
            "DURABILITYPCT" to durabilityPercent(stack).toString()
        )
    }

    private fun propertyRows(): List<Map<String, String>> {
        val pos = (mc.hitResult as? BlockHitResult)?.blockPos ?: return emptyList()
        val state = mc.level?.getBlockState(pos) ?: return emptyList()
        return state.values.entries.mapIndexed { index, entry ->
            mapOf("INDEX" to index.toString(), "PROPERTY" to entry.key.name, "VALUE" to entry.value.toString())
        }
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
        val descriptor = raw.trim()
        if (descriptor.isBlank()) return null
        descriptor.toIntOrNull()?.let { return BuiltInRegistries.ITEM.byId(it).takeIf { item -> item != Items.AIR } }
        val id = Identifier.tryParse(if (descriptor.contains(':')) descriptor else "minecraft:$descriptor") ?: return null
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null)?.takeIf { it != Items.AIR }
    }

    private fun itemMatches(stack: ItemStack, descriptor: String): Boolean {
        val text = descriptor.trim()
        if (text.isBlank()) return false
        val item = stack.item
        val id = BuiltInRegistries.ITEM.getId(item).toString()
        val key = BuiltInRegistries.ITEM.getKey(item).toString()
        val short = key.substringAfter(':')
        val name = stack.hoverName.string
        return text.equals(id, true) || text.equals(key, true) || text.equals(short, true) || name.contains(text, ignoreCase = true)
    }

    private fun blockPos(args: List<String>, relative: Boolean): BlockPos? {
        val player = mc.player ?: return null
        val x = args.getOrNull(0)?.toIntOrNull() ?: return null
        val y = args.getOrNull(1)?.toIntOrNull() ?: return null
        val z = args.getOrNull(2)?.toIntOrNull() ?: return null
        return if (relative) player.blockPosition().offset(x, y, z) else BlockPos(x, y, z)
    }

    private fun soundCategory(raw: String?): SoundSource? {
        return when (raw?.trim()?.uppercase(Locale.ROOT)) {
            null, "" -> null
            "MASTER", "SOUND" -> SoundSource.MASTER
            "MUSIC" -> SoundSource.MUSIC
            "RECORD", "RECORDS", "JUKEBOX" -> SoundSource.RECORDS
            "WEATHER" -> SoundSource.WEATHER
            "BLOCK", "BLOCKS" -> SoundSource.BLOCKS
            "HOSTILE", "HOSTILEVOLUME" -> SoundSource.HOSTILE
            "NEUTRAL", "FRIENDLY" -> SoundSource.NEUTRAL
            "PLAYER", "PLAYERS" -> SoundSource.PLAYERS
            "AMBIENT", "ENVIRONMENT" -> SoundSource.AMBIENT
            "VOICE" -> SoundSource.VOICE
            else -> null
        }
    }

    private fun signTextVar(key: String): String? {
        if (!key.startsWith("SIGNTEXT[")) return null
        return resolveArrayRead(key)
    }

    private fun offhandVar(key: String): String {
        val stack = mc.player?.offhandItem
        if (stack == null || stack.isEmpty) {
            return when {
                key.startsWith("OFFHAND") -> "0"
                else -> ""
            }
        }
        return when {
            key.endsWith("ITEMIDDMG") -> "${BuiltInRegistries.ITEM.getId(stack.item)}:${stack.damageValue}"
            key.endsWith("ITEMNAME") -> stack.hoverName.string
            key.endsWith("ITEMCODE") -> BuiltInRegistries.ITEM.getKey(stack.item).toString()
            key.endsWith("DURABILITY") -> (stack.maxDamage - stack.damageValue).coerceAtLeast(0).toString()
            key.endsWith("DAMAGE") -> stack.damageValue.toString()
            key.endsWith("STACKSIZE") -> stack.count.toString()
            key.endsWith("ITEM") -> BuiltInRegistries.ITEM.getId(stack.item).toString()
            else -> ""
        }
    }

    private fun itemUsePercent(): Int {
        val player = mc.player ?: return 0
        val max = player.useItem.maxDamage.takeIf { it > 0 } ?: return 0
        val remaining = player.useItemRemainingTicks
        return ((remaining.toDouble() / max.toDouble()) * 100.0).toInt()
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
            val char = raw[index]
            if (quote != null) {
                if (char == quote) quote = null
                continue
            }
            when (char) {
                '"', '\'' -> quote = char
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

    private data class Branch(val token: MacroStatement, val start: Int, val end: Int)
    private data class AimResult(val yaw: Float, val pitch: Float, val distance: Double)
    private data class NearestNametag(val entity: Entity, val text: String, val distanceSqr: Double)
    private class StopMacro : RuntimeException()
    private class BreakLoop : RuntimeException()

    private object MatcherQuote {
        fun quote(value: String): String = java.util.regex.Matcher.quoteReplacement(value)
    }

    companion object {
        private val INCLUDE_PATTERN = Pattern.compile("\\$\\$<([^>]+)>")
        private val PARAM_LIST_PATTERN = Pattern.compile("\\$\\$\\[\\[([^]]*)]]")
        private val PARAM_NAMED_PATTERN = Pattern.compile("\\$\\$\\[([^]]+)]")
        private val PARAM_TYPED_PATTERN = Regex("\\$\\$([a-z](?::[a-z])?)", RegexOption.IGNORE_CASE)
    }
}
