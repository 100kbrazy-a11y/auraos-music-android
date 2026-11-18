package com.auraos.music.next

import android.content.Context

/** CI only: `am start --es open <screen>` jumps straight to a screen for the side-by-side screenshots. */
object Tour {
    fun open(ctx: Context, o: String) {
        val k = o.substringBefore(":"); val v = o.substringAfter(":", "")
        Dialogs.close(); NpState.open.value = false; Nav.stack.clear(); Prefs.setNpLyrics(false)
        val songs = Store.songs.value
        val byTitle = songs.sortedBy { it.title.lowercase() }
        when (k) {
            "tab" -> Nav.tab.value = v.toInt()
            "album" -> Store.albums().firstOrNull { it.title == v }?.let { Nav.open(Page.AlbumP(it.key)) }
            "artist" -> Nav.open(Page.ArtistP(v))
            "settings" -> Nav.open(Page.Settings)
            "search" -> Nav.open(Page.Search)
            "filter" -> Dialogs.sheet { FilterSheet() }
            "songmenu" -> songs.firstOrNull { it.title == v }?.let { s -> Dialogs.sheet { SongSheet(s) } }
            "play" -> songs.firstOrNull { it.title == v }?.let { s -> Ctl.play(ctx, byTitle, byTitle.indexOf(s), "Songs", false) }
            "player" -> NpState.open.value = true
            "lyrics" -> { Prefs.setNpLyrics(true); NpState.open.value = true }
            "queue" -> { NpState.open.value = true; Dialogs.sheet { QueueSheet() } }
            "npmenu" -> { NpState.open.value = true; Ctl.queue.value.getOrNull(com.auraos.music.NativePlayer.state.value.index)?.let { s -> Dialogs.sheet { NpSheet(s) } } }
        }
    }
}
