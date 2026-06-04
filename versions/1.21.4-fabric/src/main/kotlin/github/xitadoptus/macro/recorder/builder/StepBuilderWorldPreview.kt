package github.xitadoptus.macro.recorder.builder

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShapeRenderer
import net.minecraft.world.phys.AABB

object StepBuilderWorldPreview {
    fun render(context: WorldRenderContext, snapshot: BuilderPreviewSnapshot) {
        render(context, snapshot.waypoints, snapshot.target)
    }

    fun render(context: WorldRenderContext, waypoints: List<RouteWaypoint>, target: BlockTarget?) {
        val matrices = context.matrixStack() ?: return
        val consumers = context.consumers() ?: return
        val camera = context.camera().position
        val lineConsumer = consumers.getBuffer(RenderType.lines())
        val pose = matrices.last()
        val now = System.currentTimeMillis()

        waypoints.forEachIndexed { index, waypoint ->
            if (waypoint.manual || index % visibleStride(waypoints.size) == 0) {
                WaypointSmokeShape.lines(waypoint, now).forEach { line ->
                    lineConsumer.addVertex(pose, (line.start.x - camera.x).toFloat(), (line.start.y - camera.y).toFloat(), (line.start.z - camera.z).toFloat())
                        .setColor(line.red, line.green, line.blue, line.alpha)
                        .setNormal(pose, 0f, 1f, 0f)
                    lineConsumer.addVertex(pose, (line.end.x - camera.x).toFloat(), (line.end.y - camera.y).toFloat(), (line.end.z - camera.z).toFloat())
                        .setColor(line.red, line.green, line.blue, line.alpha)
                        .setNormal(pose, 0f, 1f, 0f)
                }
            }
        }

        target ?: return
        renderTarget(context, target)
    }

    private fun renderTarget(context: WorldRenderContext, target: BlockTarget) {
        val matrices = context.matrixStack() ?: return
        val consumers = context.consumers() ?: return
        val camera = context.camera().position
        val box = AABB(
            target.x.toDouble(),
            target.y.toDouble(),
            target.z.toDouble(),
            target.x + 1.0,
            target.y + 1.0,
            target.z + 1.0
        ).inflate(0.006).move(-camera.x, -camera.y, -camera.z)

        matrices.pushPose()
        ShapeRenderer.renderLineBox(matrices, consumers.getBuffer(RenderType.lines()), box, 0.1f, 0.95f, 0.75f, 1.0f)
        matrices.popPose()
    }

    private fun visibleStride(size: Int): Int {
        return (size / 30).coerceAtLeast(1)
    }
}
