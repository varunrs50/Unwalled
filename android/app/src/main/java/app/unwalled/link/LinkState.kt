package app.unwalled.link

import kotlinx.coroutines.flow.MutableStateFlow

sealed interface LinkStatus {
    data object NotPaired : LinkStatus
    data class Pairing(val macName: String) : LinkStatus
    data class Searching(val macName: String) : LinkStatus
    data class Connected(val macName: String) : LinkStatus
}

/** Process-wide connection state, written by LinkService and observed by the UI. */
object LinkState {
    val status = MutableStateFlow<LinkStatus>(LinkStatus.NotPaired)
    val error = MutableStateFlow<String?>(null)
}
