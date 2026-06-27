package github.xitadoptus.macro.recorder

import github.xitadoptus.macro.engine.MacroEntry
import github.xitadoptus.macro.engine.MacroStorage
import github.xitadoptus.macro.gui.MacroFonts
import github.xitadoptus.macro.util.ClientUtils
import github.xitadoptus.macro.util.KeyboardUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import java.awt.Color
import java.util.Locale

object MacroRecorder {
    private const val COUNTDOWN_MS = 3000L
    private const val LOOK_INTERVAL_MS = 20L
    private const val LOOK_DELTA = 0.25f

    private val inputNames = listOf(
        "forward",
        "back",
        "left",
        "right",
        "jump",
        "sneak",
        "sprint",
        "attack",
        "use",
        "drop",
        "inventory",
        "swapoffhand",
        "pickblock",
        "playerlist"
    )

    @Volatile
    private var state = RecorderState.IDLE

    private var countdownStartedAt = 0L
    private var recordingStartedAt = 0L
    private var stopKey = "RSHIFT"
    private var previousStopPressed = false
    private var tracker = RecordedInputTracker(inputNames)
    private val actions = mutableListOf<RecordedMacroAction>()
    private var lastSlot = -1
    private var lastYaw = Float.NaN
    private var lastPitch = Float.NaN
    private var lastLookAt = Long.MIN_VALUE
    private var statusText = ""
    private var statusUntil = 0L

    val active: Boolean
        get() = state == RecorderState.COUNTDOWN || state == RecorderState.RECORDING

    fun start(rawStopKey: String): Boolean {
        val client = Minecraft.getInstance()
        if (active) return false
        if (client.level == null || client.player == null) {
            ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Join a world before recording.")
            return false
        }

        val normalized = KeyboardUtils.normalizeKey(rawStopKey)
        if (!KeyboardUtils.isValidKeyName(normalized) || normalized == "NONE") {
            ClientUtils.displayChatMessage("\u00A7c[MacroEngine] Invalid recorder stop key: \u00A7f$normalized")
            return false
        }

        stopKey = normalized
        countdownStartedAt = System.currentTimeMillis()
        previousStopPressed = KeyboardUtils.isInputPressed(stopKey)
        statusText = "Starting in 3"
        statusUntil = countdownStartedAt + COUNTDOWN_MS
        state = RecorderState.COUNTDOWN
        return true
    }

    fun onClientTick(client: Minecraft): Boolean {
        if (!active) return false
        if (client.level == null || client.player == null) {
            cancel("Recording cancelled: world closed.")
            return true
        }

        return when (state) {
            RecorderState.COUNTDOWN -> {
                tickCountdown(client)
                true
            }
            RecorderState.RECORDING -> {
                tickRecording(client)
                true
            }
            RecorderState.IDLE -> false
        }
    }

    fun captureFrame() {
        if (state != RecorderState.RECORDING) return
        val client = Minecraft.getInstance()
        if (client.level == null || client.player == null) return
        val elapsed = elapsedMillis()
        actions += tracker.update(elapsed, sampleInputs(client))
        captureSlot(client, elapsed)
        captureLook(client, elapsed)
    }

    fun renderOverlay(graphics: GuiGraphics) {
        val now = System.currentTimeMillis()
        val text = overlayText(now)
        if (text.isBlank()) return

        val client = Minecraft.getInstance()
        val font = client.font
        val width = client.window.guiScaledWidth
        val x = width - font.width(text) - 18
        val y = 16

        graphics.fill(x - 8, y - 6, width - 10, y + 16, Color(15, 18, 22, 220).rgb)
        graphics.drawString(font, MacroFonts.text(text), x, y, Color.WHITE.rgb, true)
    }

    private fun tickCountdown(client: Minecraft) {
        val now = System.currentTimeMillis()
        val elapsed = now - countdownStartedAt
        val remaining = ((COUNTDOWN_MS - elapsed + 999L) / 1000L).coerceIn(1L, 3L)
        statusText = "Starting in $remaining"
        previousStopPressed = KeyboardUtils.isInputPressed(stopKey)

        if (elapsed >= COUNTDOWN_MS) {
            beginRecording(client, now)
        }
    }

    private fun beginRecording(client: Minecraft, now: Long) {
        state = RecorderState.RECORDING
        recordingStartedAt = now
        tracker = RecordedInputTracker(inputNames)
        actions.clear()
        lastSlot = -1
        lastYaw = Float.NaN
        lastPitch = Float.NaN
        lastLookAt = Long.MIN_VALUE
        statusText = "Recording - press $stopKey to stop"
        statusUntil = Long.MAX_VALUE
        previousStopPressed = KeyboardUtils.isInputPressed(stopKey)
        captureInitialState(client)
    }

    private fun tickRecording(client: Minecraft) {
        val elapsed = elapsedMillis()
        val stopPressed = KeyboardUtils.isInputPressed(stopKey)
        if (stopPressed && !previousStopPressed) {
            finish(elapsed)
            previousStopPressed = stopPressed
            return
        }
        previousStopPressed = stopPressed

        actions += tracker.update(elapsed, sampleInputs(client))
        captureSlot(client, elapsed)
        captureLook(client, elapsed)
    }

    private fun captureInitialState(client: Minecraft) {
        actions += tracker.update(0L, sampleInputs(client))
        captureSlot(client, 0L)
        captureLook(client, 0L, force = true)
    }

    private fun sampleInputs(client: Minecraft): Map<String, Boolean> {
        val options = client.options
        return linkedMapOf(
            "forward" to options.keyUp.isDown,
            "back" to options.keyDown.isDown,
            "left" to options.keyLeft.isDown,
            "right" to options.keyRight.isDown,
            "jump" to options.keyJump.isDown,
            "sneak" to options.keyShift.isDown,
            "sprint" to options.keySprint.isDown,
            "attack" to options.keyAttack.isDown,
            "use" to options.keyUse.isDown,
            "drop" to options.keyDrop.isDown,
            "inventory" to options.keyInventory.isDown,
            "swapoffhand" to options.keySwapOffhand.isDown,
            "pickblock" to options.keyPickItem.isDown,
            "playerlist" to options.keyPlayerList.isDown
        )
    }

    private fun captureSlot(client: Minecraft, timeMillis: Long) {
        val selected = client.player?.inventory?.selected ?: return
        if (selected != lastSlot) {
            lastSlot = selected
            actions += RecordedMacroAction(timeMillis, "SLOT(${selected + 1})")
        }
    }

    private fun captureLook(client: Minecraft, timeMillis: Long, force: Boolean = false) {
        val player = client.player ?: return
        val yaw = player.yRot
        val pitch = player.xRot
        val changed = force ||
            lastYaw.isNaN() ||
            kotlin.math.abs(yaw - lastYaw) >= LOOK_DELTA ||
            kotlin.math.abs(pitch - lastPitch) >= LOOK_DELTA
        val intervalReady = force || timeMillis - lastLookAt >= LOOK_INTERVAL_MS
        if (!changed || !intervalReady) return

        lastYaw = yaw
        lastPitch = pitch
        lastLookAt = timeMillis
        actions += RecordedMacroAction(timeMillis, "LOOK(${formatAngle(yaw)}, ${formatAngle(pitch)})")
    }

    private fun finish(timeMillis: Long) {
        actions += tracker.releaseHeld(timeMillis)
        val name = nextMacroName()
        MacroStorage.config.macros += MacroEntry(name = name, key = "NONE", script = MacroRecorderScriptBuilder.build(actions), enabled = true)
        MacroStorage.save()
        state = RecorderState.IDLE
        statusText = "Recorded macro saved: $name"
        statusUntil = System.currentTimeMillis() + 2500L
        ClientUtils.displayChatMessage("\u00A7a[MacroEngine] Recorded macro saved: \u00A7f$name")
    }

    private fun cancel(message: String) {
        state = RecorderState.IDLE
        statusText = message
        statusUntil = System.currentTimeMillis() + 2500L
        ClientUtils.displayChatMessage("\u00A7c[MacroEngine] $message")
    }

    private fun overlayText(now: Long): String {
        return when (state) {
            RecorderState.COUNTDOWN, RecorderState.RECORDING -> statusText
            RecorderState.IDLE -> if (now <= statusUntil) statusText else ""
        }
    }

    private fun elapsedMillis(): Long {
        return (System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L)
    }

    private fun nextMacroName(): String {
        val base = "Recorded Macro"
        val names = MacroStorage.config.macros.map { it.name }.toSet()
        if (base !in names) return base
        var index = 2
        while ("$base $index" in names) index++
        return "$base $index"
    }

    private fun formatAngle(value: Float): String {
        return String.format(Locale.ROOT, "%.2f", value)
    }

    private enum class RecorderState {
        IDLE,
        COUNTDOWN,
        RECORDING
    }
}
