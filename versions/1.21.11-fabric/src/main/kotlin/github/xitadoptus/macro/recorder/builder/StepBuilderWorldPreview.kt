package github.xitadoptus.macro.recorder.builder

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object StepBuilderWorldPreview {
    fun render(context: WorldRenderContext, snapshot: BuilderPreviewSnapshot) {
        render(context, snapshot.waypoints, snapshot.target)
    }

    fun render(context: WorldRenderContext, waypoints: List<RouteWaypoint>, target: BlockTarget?) {
        val now = System.currentTimeMillis()
        val collection = Minecraft.getInstance().levelRenderer.collectPerFrameGizmos()
        try {
            waypoints.forEachIndexed { index, waypoint ->
                if (waypoint.manual || index % visibleStride(waypoints.size) == 0) {
                    WaypointSmokeShape.lines(waypoint, now).forEach { line ->
                        Gizmos.line(
                            Vec3(line.start.x, line.start.y, line.start.z),
                            Vec3(line.end.x, line.end.y, line.end.z),
                            color(line.red, line.green, line.blue, line.alpha),
                            0.035f
                        ).persistForMillis(80)
                    }
                }
            }

            target?.let(::renderTarget)
        } finally {
            collection.close()
        }
    }

    private fun renderTarget(target: BlockTarget) {
        val box = AABB(
            target.x.toDouble(),
            target.y.toDouble(),
            target.z.toDouble(),
            target.x + 1.0,
            target.y + 1.0,
            target.z + 1.0
        ).inflate(0.006)

        Gizmos.cuboid(box, GizmoStyle.stroke(color(26, 242, 190, 255), 0.04f), true).persistForMillis(80)
    }

    private fun visibleStride(size: Int): Int {
        return (size / 30).coerceAtLeast(1)
    }

    private fun color(red: Int, green: Int, blue: Int, alpha: Int): Int {
        return ((alpha and 255) shl 24) or ((red and 255) shl 16) or ((green and 255) shl 8) or (blue and 255)
    }
}
