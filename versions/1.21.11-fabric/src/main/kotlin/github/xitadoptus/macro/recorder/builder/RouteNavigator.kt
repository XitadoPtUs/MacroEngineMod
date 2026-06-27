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
            val sample = sample()
            if (!sample.present) return false
            if (WaypointMath.reached(sample.x, sample.y, sample.z, waypoint)) {
                releaseMovement()
                return true
            }

            val yaw = WaypointMath.yawTo(sample.x, sample.z, waypoint.x, waypoint.z)
            val flightAction = RouteFlightDecision.choose(
                blockedAhead = sample.blockedAhead,
                flying = sample.flying,
                playerY = sample.y,
                waypointY = waypoint.y
            )

            client.execute(Runnable {
                client.player?.yRot = yaw
                client.options.keyUp.isDown = true
                client.options.keySprint.isDown = waypoint.sprint
                client.options.keyJump.isDown = flightAction == FlightAction.ASCEND
                client.options.keyShift.isDown = flightAction == FlightAction.DESCEND
            })

            if (stuck.update(System.currentTimeMillis(), sample.x, sample.y, sample.z)) {
                bypassAttempts++
                if (bypassAttempts > 2) {
                    releaseMovement()
                    return false
                }
                bypass(waypoint.jumpAllowed, bypassAttempts)
                if (!running()) break
                stuck.reset(System.currentTimeMillis(), sample.x, sample.y, sample.z)
            }

            Thread.sleep(50L)
        }

        releaseMovement()
        return false
    }

    private data class NavSample(
        val present: Boolean,
        val x: Double = 0.0,
        val y: Double = 0.0,
        val z: Double = 0.0,
        val flying: Boolean = false,
        val blockedAhead: Boolean = false
    )

    private fun sample(): NavSample = onMain(NavSample(present = false)) {
        val player = client.player ?: return@onMain NavSample(present = false)
        NavSample(
            present = true,
            x = player.x,
            y = player.y,
            z = player.z,
            flying = player.abilities.flying || player.abilities.mayfly,
            blockedAhead = blockedAhead()
        )
    }

    private fun <T> onMain(fallback: T, block: () -> T): T {
        if (client.isSameThread) return runCatching(block).getOrDefault(fallback)
        val future = java.util.concurrent.CompletableFuture<T>()
        client.execute { future.complete(runCatching(block).getOrDefault(fallback)) }
        return runCatching { future.get(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(fallback)
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
