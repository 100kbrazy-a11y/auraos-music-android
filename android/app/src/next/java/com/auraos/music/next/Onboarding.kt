@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.auraos.music.next

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** the first-run tour. New libraries see it once; anyone can replay it from Settings › Updates & About. */
object Onboard {
    val show = MutableStateFlow(false)
    fun check() {
        val s = Store.settings()
        if (s.optBoolean("onboarded", false)) return
        if (Store.songs.value.isNotEmpty() || Migrate.state.value.isNotEmpty()) { s.put("onboarded", true); Store.save(); return }
        show.value = true
    }
    fun start() { Nav.stack.clear(); show.value = true }
    fun finish() { show.value = false; Store.settings().put("onboarded", true); Store.save() }
}

@Composable
fun OnboardingOverlay() {
    val on by Onboard.show.collectAsState()
    if (!on) return
    val a = accent
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { 6 }
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    Box(Modifier.fillMaxSize().background(C.bg).pointerInput(Unit) { detectTapGestures { } }) {
        Aurora(motion && !saving)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                if (pager.currentPage < 5) TextBtn("Skip") { Onboard.finish() }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { p ->
                Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    when (p) {
                        0 -> { StrataMark(); Spacer(Modifier.height(28.dp)); Head2("Welcome to Strata Music"); Body("Your music, in layers you control. A quick tour, about thirty seconds.") }
                        1 -> {
                            SIconTile(SG.Scan, 96.dp, 60.dp); Spacer(Modifier.height(28.dp))
                            Head2("Bring your music in"); Body("Pick the folders your songs live in. Strata scans them, you tick the songs you want, and they can land in a playlist. You can also add a music pack or find songs on YouTube Music.")
                            Spacer(Modifier.height(20.dp))
                            Btn("Scan folders now", Ic.Folder, tonal = true) { Onboard.finish(); Nav.open(Page.Scanner) }
                        }
                        2 -> {
                            MockSearch(); Spacer(Modifier.height(28.dp))
                            Head2("Search everything"); Body("Type once. The two round buttons beside the search bar switch between your library and YouTube Music, and the results update straight away.")
                        }
                        3 -> {
                            MockPages(); Spacer(Modifier.height(28.dp))
                            Head2("Swipe the player"); Body("In the full player, swipe left for lyrics that light up word by word, and right for the artist. Tap the media bar, or pull it up, to open the player.")
                        }
                        4 -> {
                            SIconTile(SG.Customize, 96.dp, 60.dp); Spacer(Modifier.height(24.dp))
                            Head2("Make it yours"); Body("Pick an accent colour now. Everything else is in Settings: move the parts of the player, change shapes, tabs, lyrics and more.")
                            Spacer(Modifier.height(18.dp))
                            val follow by Prefs.coverAccent.collectAsState(); val cur by Prefs.accent.collectAsState()
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Prefs.ACCENTS.forEach { c ->
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(c)).then(if (c == cur && !follow) Modifier.border(3.dp, Color.White, CircleShape) else Modifier).clickable { Hap.tick(); Prefs.setCoverAccent(false); Prefs.setAccent(c) })
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Chip("Follow the playing cover", follow, ic = Ic.Image) { Prefs.setCoverAccent(!follow) }
                        }
                        else -> { SIconTile(SG.Star, 96.dp, 60.dp); Spacer(Modifier.height(28.dp)); Head2("You're all set"); Body("Replay this any time from Settings › Updates & About. Enjoy the music.") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                repeat(6) { i ->
                    val w by animateFloatAsState(if (i == pager.currentPage) 22f else 7f, label = "dot$i")
                    Box(Modifier.padding(horizontal = 3.dp).height(7.dp).width(w.dp).clip(RoundedCornerShape(4.dp)).background(if (i == pager.currentPage) a else Color.White.copy(alpha = 0.22f)))
                }
            }
            Btn(if (pager.currentPage == 5) "Start listening" else "Next", modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = 20.dp)) {
                if (pager.currentPage == 5) Onboard.finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
        }
    }
}

@Composable private fun Head2(t: String) = T(t, 28.sp, W800, align = TextAlign.Center, lines = 2, modifier = Modifier.padding(bottom = 10.dp))
@Composable private fun Body(t: String) = T(t, 16.sp, color = C.sub, align = TextAlign.Center, lines = 6)

/** Strata's mark: three layers of music stacked like rock */
@Composable
private fun StrataMark() {
    val a = accent
    Canvas(Modifier.size(120.dp)) {
        val w = size.width
        for (i in 0..2) {
            val y = w * (0.12f + i * 0.27f)
            drawRoundRect(a.copy(alpha = 0.30f + 0.30f * i), Offset(w * (0.08f + 0.04f * i), y), Size(w * (0.84f - 0.08f * i), w * 0.22f), CornerRadius(w * 0.11f))
        }
    }
}

@Composable
private fun MockSearch() {
    val a = accent
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(C.surface2).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Ic.Search, tint = C.sub); T("Search", 15.sp, color = C.sub)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(mix(a, 0.30f, C.surface2)), contentAlignment = Alignment.Center) { Icon(Ic.Apps, tint = a) }
        Box(Modifier.size(44.dp).clip(CircleShape).background(C.surface2), contentAlignment = Alignment.Center) { Icon(Ic.Globe, tint = C.sub) }
    }
}

@Composable
private fun MockPages() {
    val a = accent
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((i, n) in listOf("Lyrics", "Player", "Artist").withIndex()) {
            val mid = i == 1
            Box(Modifier.width(if (mid) 92.dp else 78.dp).height(if (mid) 150.dp else 126.dp).clip(RoundedCornerShape(16.dp)).background(if (mid) mix(a, 0.22f, C.surface2) else C.surface)
                .then(if (mid) Modifier.border(2.dp, a, RoundedCornerShape(16.dp)) else Modifier), contentAlignment = Alignment.Center) {
                T(n, 13.sp, W700, if (mid) C.text else C.sub)
            }
        }
    }
}
