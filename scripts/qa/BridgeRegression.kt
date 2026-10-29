import skip.bridge.Reflector

object OldDroid {
    @JvmStatic fun appBuild(): String = "59"
    @JvmStatic fun startLocation(precise: Boolean) { }
}
class NewDroid private constructor() {
    companion object {
        var requested = false
        @JvmStatic fun appBuild(): String = "59"
        @JvmStatic fun startLocation(precise: Boolean) { requested = precise }
        @JvmStatic fun isSpeaking(): Boolean = false
        @JvmStatic fun density(): Double = 1.5
    }
}
fun main() {
    val old = Reflector("OldDroid")
    check(runCatching { old.stringFunction("appBuild", emptyList()) }.exceptionOrNull() is NoSuchMethodError)
    check(runCatching { old.voidFunction("startLocation", listOf(false)) }.exceptionOrNull() is NoSuchMethodError)
    println("PASS: original object reproduces both NoSuchMethodError failures")
    val fixed = Reflector("NewDroid")
    check(fixed.stringFunction("appBuild", emptyList()) == "59")
    fixed.voidFunction("startLocation", listOf(true))
    check(NewDroid.requested)
    check(fixed.booleanFunction("isSpeaking", emptyList()) == false)
    check(fixed.doubleFunction("density", emptyList()) == 1.5)
    println("PASS: companion resolves String, Void(Boolean), Bool and Double calls")
}
