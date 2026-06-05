package github.xitadoptus.macro.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateVersionPolicyTest {
    @Test
    fun ignoresMatchingReleaseTags() {
        assertFalse(UpdateVersionPolicy.shouldNotify("1.0.0", "v1.0.0"))
    }

    @Test
    fun ignoresVersionedMinecraftReleaseTags() {
        assertFalse(UpdateVersionPolicy.shouldNotify("1.0.0", "1.21.4-fabric-1.0.0"))
    }

    @Test
    fun detectsNewReleaseTags() {
        assertTrue(UpdateVersionPolicy.shouldNotify("1.0.0", "v1.0.1"))
    }
}
