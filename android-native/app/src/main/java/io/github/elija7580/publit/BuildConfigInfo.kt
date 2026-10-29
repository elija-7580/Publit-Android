package io.github.elija7580.publit

/** App identity for requests (Transitous requires an identifying User-Agent with a contact). */
object BuildConfigInfo {
    const val CONTACT = "https://github.com/elija-7580/Publit-Android"
    @Volatile var version: String = "0.7.0"
    val userAgent: String get() = "Publit/$version (Android; +$CONTACT)"
}
