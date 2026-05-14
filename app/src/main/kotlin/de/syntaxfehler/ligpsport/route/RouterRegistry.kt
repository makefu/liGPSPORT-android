package de.syntaxfehler.ligpsport.route

import de.syntaxfehler.ligpsport.route.providers.BRouterProvider
import de.syntaxfehler.ligpsport.route.providers.OsrmProvider
import de.syntaxfehler.ligpsport.route.providers.StraightLineProvider

/**
 * Static catalogue of available [RouteProvider]s. Order is the order
 * the Settings UI renders. The first entry is the default fallback
 * when no preference is set (or the stored preference references a
 * provider that no longer exists).
 */
object RouterRegistry {
    val all: List<RouteProvider> = listOf(
        BRouterProvider(),
        OsrmProvider(),
        StraightLineProvider(),
    )

    val default: RouteProvider get() = all.first()

    fun byId(id: String?): RouteProvider? =
        if (id == null) null else all.firstOrNull { it.id == id }
}
