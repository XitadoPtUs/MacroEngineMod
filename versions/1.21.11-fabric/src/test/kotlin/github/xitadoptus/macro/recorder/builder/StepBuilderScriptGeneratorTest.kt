package github.xitadoptus.macro.recorder.builder

import github.xitadoptus.macro.engine.MacroScriptParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepBuilderScriptGeneratorTest {
    @Test
    fun generatesSellLoopScriptFromBuilderSteps() {
        val builder = StepBuilderMacro(
            name = "Cactus Seller",
            steps = mutableListOf(
                BuilderStep(type = BuilderStepType.WAIT_INVENTORY_FULL),
                BuilderStep(type = BuilderStepType.COMMAND, command = "/vip"),
                BuilderStep(
                    type = BuilderStepType.ROUTE_TO_SELL,
                    route = mutableListOf(RouteWaypoint(x = 10.0, y = 64.0, z = 20.0))
                ),
                BuilderStep(
                    type = BuilderStepType.SELL_TARGET,
                    target = BlockTarget(12, 65, 22),
                    sellAction = SellAction(button = ClickButton.LEFT, shift = true)
                ),
                BuilderStep(
                    type = BuilderStepType.RETURN_ROUTE,
                    route = mutableListOf(RouteWaypoint(x = 0.0, y = 64.0, z = 0.0))
                ),
                BuilderStep(type = BuilderStepType.LOOP)
            )
        )

        val script = StepBuilderScriptGenerator.generate(builder)

        assertEquals(
            "$" + "$" + "{\n" +
                "LOG(\"&a[MacroEngine] Step builder macro started\");\n" +
                "DO();\n" +
                "LOG(\"&e[MacroEngine] Waiting for inventory to be full\");\n" +
                "WAITINVENTORYFULL();\n" +
                "CHAT(\"/vip\");\n" +
                "GOTO(10.00, 64.00, 20.00, 1.25, 15000, true);\n" +
                "AIMTO(12.50, 65.50, 22.50);\n" +
                "KEYDOWN(sneak);\n" +
                "KEYDOWN(attack);\n" +
                "WAIT(150ms);\n" +
                "KEYUP(attack);\n" +
                "KEYUP(sneak);\n" +
                "GOTO(0.00, 64.00, 0.00, 1.25, 15000, true);\n" +
                "LOOP();\n" +
                "}" + "$" + "$",
            script
        )
        assertTrue(MacroScriptParser.parse(script).isNotEmpty())
    }
}
