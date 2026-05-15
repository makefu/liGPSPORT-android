package de.syntaxfehler.ligpsport.route

/**
 * One way to turn (start, end) into a GPX-format route. Implementations
 * may hit the network (`isOffline=false`) or run entirely on-device
 * (`isOffline=true`). Output is always a UTF-8 GPX byte stream that
 * [GpxParser] can consume and `CnxEncoder` can convert.
 */
interface RouteProvider {
    /** Stable identifier used for SharedPreferences + broadcasts. */
    val id: String

    /** Human-readable label for the Settings UI. */
    val displayName: String

    /** One-line description for the Settings UI. */
    val description: String

    /** True if the provider works without network access. */
    val isOffline: Boolean

    /**
     * Plan a route through `start → intermediates[0] → … → intermediates[N] → end`.
     * Empty [intermediates] is the common two-point case. [profile] is a
     * hint (cycling vs. walking etc.); providers may ignore it.
     */
    suspend fun planGpx(
        start: Point,
        end: Point,
        intermediates: List<Point> = emptyList(),
        profile: String = "trekking",
    ): ByteArray
}
