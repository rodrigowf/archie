package com.assistant.archie.feature.visuals

import android.content.Context
import com.assistant.archie.feature.visuals.web.WebTrust
import com.assistant.archie.feature.visuals.web.WebViewPool
import com.assistant.core.data.LoadState
import com.assistant.core.model.VisualInfo
import com.assistant.core.network.ApiResult
import com.assistant.core.protocol.CastProbeDto
import com.assistant.core.protocol.CastResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/** Mockup content (phone (h), tablet inline card) for the visuals tests and goldens. */
object VisualFixtures {
    val items = listOf(
        VisualInfo("energy/weekly-energy.html", "/energy/weekly-energy.html", "Weekly energy usage", "2026-10-03T13:00:00+00:00", "2026-10-03T13:00:00+00:00", 27_851),
        VisualInfo("tarot-canvas/index.html", "/tarot-canvas/index.html", "Tarô Gamificado — Canvas de Progresso", "2026-10-03T09:45:45+00:00", "2026-10-03T09:45:45+00:00", 27_851),
        VisualInfo("generated/utopian_group_portrait.html", "/generated/utopian_group_portrait.html", "Utopian Group Portrait", null, "2026-10-01T10:00:00+00:00", 9_000),
        VisualInfo("music1 mix/sync report.html", "/music1 mix/sync report.html", "Music video sync report", null, "2026-09-20T10:00:00+00:00", 12_000),
        VisualInfo("dashboard.html", "/dashboard.html", "Home dashboard", null, "2026-08-02T10:00:00+00:00", 5_000),
    )
}

/** In-memory [VisualsDeps]; the cast answers what [probe]/[castResult] say, never the network. */
class FakeVisualsDeps(
    context: Context,
    items: List<VisualInfo>? = VisualFixtures.items,
    var probe: ApiResult<CastProbeDto> = ApiResult.Ok(CastProbeDto(available = true)),
    var castResult: ApiResult<CastResponse> = ApiResult.Ok(CastResponse(ok = true, message = "Showing on TV: https://x")),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) : VisualsDeps {
    override val list = MutableStateFlow(LoadState(items))
    val casts = mutableListOf<String>()
    val renames = mutableListOf<Pair<String, String>>()
    var refreshes = 0
    override fun refresh() { refreshes++ }
    override suspend fun rename(path: String, title: String): ApiResult<Unit> { renames += path to title; return ApiResult.Ok(Unit) }
    override val cast = CastController({ probe }, { casts += it; castResult }, scope)
    override val pool = WebViewPool(context)
    override val trust = WebTrust({ "ws://192.168.0.200:80" }, { null })
}
