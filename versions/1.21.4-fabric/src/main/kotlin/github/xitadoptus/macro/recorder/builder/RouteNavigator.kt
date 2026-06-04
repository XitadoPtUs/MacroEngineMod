package github.xitadoptus.macro.recorder.builder

import net.minecraft.client.Minecraft
import kotlin.math.cos
import kotlin.math.sin

class RouteNavigator(
    private val client: Minecraft = Minecraft.getInstance(),
    private val running: () -> Boolean = { true }
) {
    fun goTo(waypoint: RouteWaypoint): Boolean {
        val start = System.currentTimeMillis()
        val stuck = StuckDetector()
        var bypassAttempts = 0

        while (System.currentTimeMillis() - start <= waypoint.timeoutMillis && running()) {
            val player = client.player ?: return false
            if (WaypointMath.reached(player.x, player.y, player.z, waypoint)) {
                releaseMovement()
                return true
            }

            val yaw = WaypointMath.yawTo(player.x, player.z, waypoint.x, waypoint.z)
            val blockedAhead = blockedAhead()
            val flightAction = RouteFlightDecision.choose(
                blockedAhead = blockedAhead,
                flying = player.abilities.flying || player.abilities.mayfly,
                playerY = player.y,
                waypointY = waypoint.y
            )

            client.execute(Runnable {
                client.player?.yRot = yaw
                client.options.keyUp.isDown = true
                client.options.keySprint.isDown = waypoint.sprint
                client.options.keyJump.isDown = flightAction == FlightAction.ASCEND
                client.options.keyShift.isDown = flightAction == FlightAction.DESCEND
            })

            if (stuck.update(System.currentTimeMillis(), player.x, player.y, player.z)) {
                bypassAttempts++
                if (bypassAttempts > 2) {
                    releaseMovement()
                    return false
                }
                bypass(waypoint.jumpAllowed, bypassAttempts)
                if (!running()) break
                stuck.reset(System.currentTimeMillis(), player.x, player.y, player.z)
            }

            Thread.sleep(50L)
        }

        releaseMovement()
        return false
    }

    fun releaseMovement() {
        client.execute(Runnable {
            client.options.keyUp.isDown = false
            client.options.keyDown.isDown = false
            client.options.keyLeft.isDown = false
            client.options.keyRight.isDown = false
            client.options.keyJump.isDown = false
            client.options.keySprint.isDown = false
            client.options.keyShift.isDown = false
            client.options.keyAttack.isDown = false
            client.options.keyUse.isDown = false
        })
    }

    private fun bypass(jumpAllowed: Boolean, attempt: Int) {
        if (!running()) return
        if (jumpAllowed) pulseJump()
        if (!running()) return
        val sideLeft = attempt % 2 != 0
        client.execute(Runnable {
            if (sideLeft) client.options.keyLeft.isDown = true else client.options.keyRight.isDown = true
        })
        sleepWhileRunning(250L)
        client.execute(Runnable {
            client.options.keyLeft.isDown = false
            client.options.keyRight.isDown = false
        })
    }

    private fun pulseJump() {
        client.execute(Runnable {
            client.options.keyJump.isDown = true
        })
        sleepWhileRunning(100L)
        client.execute(Runnable {
            client.options.keyJump.isDown = false
        })
    }

    private fun sleepWhileRunning(totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0L && running()) {
            val chunk = remaining.coerceAtMost(25L)
            Thread.sleep(chunk)
            remaining -= chunk
        }
    }

    private fun blockedAhead(): Boolean {
        val player = client.player ?: return false
        val level = client.level ?: return false
        val yaw = Math.toRadians(player.yRot.toDouble())
        val x = -sin(yaw) * 0.82
        val z = cos(yaw) * 0.82
        val box = player.boundingBox.inflate(-0.08, 0.0, -0.08).move(x, 0.05, z)
        return !level.noBlockCollision(player, box)
    }
}
