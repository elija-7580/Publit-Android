package publit.module

import org.junit.Assert.*
import org.junit.Test
import skip.bridge.Reflector
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.memberFunctions

/** Exercise the same reflection entry point used by Swift's AnyDynamicObject. */
class DroidBridgeTest {
    @Test fun droidHasTheCompanionExpectedBySkipBridge() {
        val companion = Droid::class.companionObjectInstance
        assertNotNull("Kotlin object + @JvmStatic is not sufficient for SkipBridge", companion)
        val methods = companion!!::class.memberFunctions.map { it.name }.toSet()
        for (name in listOf("appBuild", "startLocation", "locationPermission", "walletSigner", "setShortcuts")) {
            assertTrue("Missing platform service: $name", name in methods)
        }
    }

    @Test fun swiftCanCallAProductionBooleanAndVoidMethod() {
        // Neither method needs an Android Context while TTS is uninitialised.
        val reflector = Reflector("publit.module.Droid")
        assertEquals(false, reflector.booleanFunction("isSpeaking", emptyList<Any?>()))
        reflector.voidFunction("stopSpeaking", emptyList<Any?>())
    }
}
