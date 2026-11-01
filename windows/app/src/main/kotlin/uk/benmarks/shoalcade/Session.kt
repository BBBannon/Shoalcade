package uk.benmarks.shoalcade

/**
 * Logged-in Jellyfin session: the phone's own data class (android/.../Session.kt lines 10-15), with the same fields,
 * order and names, so the shared JellyfinClient and its tests compile unchanged. The phone's Session.kt can't be
 * compiled here because its SessionStore uses Android's encrypted preferences; the desktop's SessionStore
 * (desktop/SessionStore.kt) keeps the token with Windows DPAPI instead.
 */
data class Session(
    val serverUrl: String,
    val userId: String,
    val userName: String,
    val token: String,
)
