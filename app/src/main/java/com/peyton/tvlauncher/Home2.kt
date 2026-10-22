package com.peyton.tvlauncher

import android.view.KeyEvent as NKey
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot


private val Dark = Color(0xFF101115)
private val Squircle = IconSquircle
/** Material 3 "emphasized" curves. */
private val EmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val FocusSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 420f)
private val LinearOutSlowIn = CubicBezierEasing(0f, 0f, 0.2f, 1f)

@Composable
fun HomeV2(st: LauncherState, actions: Actions) {
  // Wallpaper crossfade: keep the previous image until the new one has faded in (800 ms+), glass included.
  var cur by remember { mutableStateOf(st.wallpaper to st.backdrop) }
  var old by remember { mutableStateOf<Pair<ImageBitmap?, ImageBitmap?>?>(null) }
  val fade = remember { Animatable(1f) }
  LaunchedEffect(st.wallpaper) {
    if (st.wallpaper !== cur.first) {
      old = cur; cur = st.wallpaper to st.backdrop
      fade.snapTo(0f); fade.animateTo(1f, tween(900, easing = LinearOutSlowIn)); old = null
    }
  }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val screen = IntSize(constraints.maxWidth, constraints.maxHeight)
    val fadeState = fade.asState()
    val glass = remember(cur, old, screen) { GlassBg(cur.second, screen, old?.second, fadeState) }
    CompositionLocalProvider(LocalGlass provides glass) { HomeContent(st, actions, cur.first, old?.first, fadeState) }
  }
}

/** Key prefix for focus requesters of the app surface in each layout. */
private fun prefixOf(l: HomeLayout) = when (l) { HomeLayout.CINEMATIC -> "row"; HomeLayout.SIDE_RAIL -> "rail"; else -> "dock" }

fun enterDockEdit(st: LauncherState, startPkg: String? = null) {
  val visible = st.favoriteApps.map { it.pkg }
  val keep = st.favorites.filter { it !in visible }          // hidden / not-installed favourites are preserved
  st.menuFor = null; st.allApps = false; st.moving = null
  st.dockEdit = DockEditor(visible, startPkg?.let { visible.indexOf(it) }?.takeIf { it >= 0 } ?: 0) { st.replaceFavorites(it + keep) }
}

/** Everything a layout needs; the same shared state drives all four layouts and the Settings previews. */
class HomeEnv(
  val st: LauncherState, val actions: Actions, val interactive: Boolean, val req: (String) -> FocusRequester,
  val onAppFocus: (String) -> Unit, val wall: ImageBitmap?, val prevWall: ImageBitmap?, val fade: State<Float>?,
  val dockVisible: State<Float>?,
)

@Composable
private fun HomeContent(st: LauncherState, actions: Actions, wall: ImageBitmap?, prevWall: ImageBitmap?, fade: State<Float>) {
  val reqs = remember { HashMap<String, FocusRequester>() }
  val req: (String) -> FocusRequester = remember { { k -> reqs.getOrPut(k) { FocusRequester() } } }
  var lastApp by remember { mutableStateOf<String?>(null) }
  val favs = st.favoriteApps
  val layout = st.layout
  val pre = prefixOf(layout)
  val firstKey = favs.firstOrNull()?.let { "$pre:${it.pkg}" } ?: "$pre:all"
  val lastMove = remember { LongArray(1) }
  val overlay = st.menuFor != null || st.inputsOpen || st.allApps || st.dockEdit != null
  val bg = !overlay

  // Minimal layout: dock slides in on any key, hides after 6 s without input.
  val dockVis = remember { Animatable(if (layout == HomeLayout.MINIMAL) 0f else 1f) }
  var dockWanted by remember { mutableStateOf(layout != HomeLayout.MINIMAL) }
  var poke by remember { mutableIntStateOf(0) }
  LaunchedEffect(layout) { dockWanted = layout != HomeLayout.MINIMAL; if (layout != HomeLayout.MINIMAL) dockVis.snapTo(1f) }
  LaunchedEffect(dockWanted) { dockVis.animateTo(if (dockWanted) 1f else 0f, if (dockWanted) tween(280, easing = EmphasizedDecel) else tween(220, easing = EmphasizedAccel)) }
  LaunchedEffect(poke, layout, overlay) { if (layout == HomeLayout.MINIMAL && !overlay && dockWanted) { delay(6000); dockWanted = false } }
  val swallow = remember { IntArray(1) { -1 } }

  LaunchedEffect(favs.isNotEmpty(), st.homeTick, layout) { delay(60); tryFocus(req(firstKey)) }
  LaunchedEffect(overlay) { if (!overlay) { delay(40); tryFocus(reqs[lastApp ?: firstKey] ?: req(firstKey)) } }
  LaunchedEffect(st.favorites, st.moving) { st.moving?.let { delay(16); tryFocus(req("dock:$it")) } }

  Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
    val n = e.nativeKeyEvent
    if (layout == HomeLayout.MINIMAL && !overlay) {
      if (n.keyCode == swallow[0]) { if (n.action == NKey.ACTION_UP) swallow[0] = -1; return@onPreviewKeyEvent true }
      poke++
      if (!dockWanted && n.action == NKey.ACTION_DOWN && n.keyCode != NKey.KEYCODE_BACK) { dockWanted = true; swallow[0] = n.keyCode; return@onPreviewKeyEvent true }
    }
    // Held D-pad: pace auto-repeat to the focus animation (~9 steps/s) so scrolling glides instead of stuttering.
    if (n.action == NKey.ACTION_DOWN && n.repeatCount > 0 && n.keyCode in NKey.KEYCODE_DPAD_UP..NKey.KEYCODE_DPAD_RIGHT) {
      val t = n.eventTime; if (t - lastMove[0] < 110) return@onPreviewKeyEvent true; lastMove[0] = t
    } else if (n.action == NKey.ACTION_DOWN) lastMove[0] = n.eventTime
    false
  }) {
    val env = HomeEnv(st, actions, bg, req, { lastApp = it }, wall, prevWall, fade, dockVis.asState())
    Crossfade(layout, animationSpec = tween(450), label = "layout") { l -> LayoutBody(env, l) }

    AllAppsPanel(st, actions, req)

    st.menuFor?.let { app ->
      val fav = st.isFav(app.pkg)
      val close = { st.menuFor = null }
      val items = buildList {
        add(MenuItem("Open", Icons.Filled.PlayArrow) { close(); actions.launch(app) })
        add(MenuItem(if (fav) "Remove from dock" else "Add to dock", if (fav) Icons.Filled.Clear else Icons.Filled.Star) { st.toggleFav(app.pkg); close() })
        add(MenuItem("Edit dock", Icons.Filled.Edit) { enterDockEdit(st, app.pkg.takeIf { fav }) })
        add(MenuItem("Hide app", Icons.Filled.Lock) { st.hide(app.pkg); if (lastApp?.endsWith(app.pkg) == true) lastApp = null; close() })
        add(MenuItem("App info", Icons.Filled.Info) { close(); actions.appInfo(app) })
        add(MenuItem("Cancel", Icons.Filled.ArrowBack) { close() })
      }
      OverlayMenu(app.label, if (app.leanback) app.pkg else "${app.pkg} · phone app", { AppThumb(app, 112.dp) }, items, close)
    }
    if (st.inputsOpen) {
      val items = remember { actions.inputs() }
      OverlayMenu("Inputs", "Switch source", null, items + MenuItem("Cancel", Icons.Filled.ArrowBack) { st.inputsOpen = false }) { st.inputsOpen = false }
    }
    st.dockEdit?.let { DockEditOverlay(st, it) }
    st.upload?.takeIf { it.background }?.let { u ->
      Row(Modifier.align(Alignment.TopCenter).padding(top = 14.dp).clip(RoundedCornerShape(50)).background(Color(0xE6161923)).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Color(0xFF7BE0A0)))
        Spacer(Modifier.width(8.dp))
        T(if (u.batchTotal > 0) "Receiving photos  ${u.batchDone} of ${u.batchTotal}" else "Receiving photos…", 12.5.sp, weight = FontWeight.SemiBold)
      }
    }
  }
}

/** Live, non-interactive miniature of a layout (Settings screens). */
@Composable
fun HomePreview(st: LauncherState, actions: Actions, layout: HomeLayout, modifier: Modifier = Modifier, scale: Float = 0.5f) {
  val cfg = LocalConfiguration.current
  val w = cfg.screenWidthDp.dp; val h = cfg.screenHeightDp.dp
  val origin = remember { mutableStateOf(Offset.Zero) }
  val d = LocalDensity.current
  val screenPx = with(d) { IntSize(w.roundToPx(), h.roundToPx()) }
  val glass = remember(st.backdrop, screenPx, scale) { GlassBg(st.backdrop, screenPx, previewOrigin = origin, previewScale = scale) }
  val reqs = remember { HashMap<String, FocusRequester>() }
  Box(modifier.size(w * scale, h * scale).clip(RoundedCornerShape(18.dp)).background(Color.Black)
    .onGloballyPositioned { origin.value = it.positionInRoot() }) {
    Box(Modifier.requiredSize(w, h).graphicsLayer { scaleX = scale; scaleY = scale }) {
      CompositionLocalProvider(LocalGlass provides glass) {
        val env = HomeEnv(st, actions, false, { k -> reqs.getOrPut(k) { FocusRequester() } }, {}, st.wallpaper, null, null, null)
        Crossfade(layout, animationSpec = tween(350), label = "pv") { l -> LayoutBody(env, l) }
      }
    }
  }
}

@Composable
private fun LayoutBody(env: HomeEnv, layout: HomeLayout) {
  when (layout) {
    HomeLayout.GLASS -> GlassLayout(env)
    HomeLayout.CINEMATIC -> CinematicLayout(env)
    HomeLayout.SIDE_RAIL -> SideRailLayout(env)
    HomeLayout.MINIMAL -> MinimalLayout(env)
  }
}

/** Clock size inside the smaller slots of Cinematic / Side rail. */
private fun compactScale(s: ClockStyle) = when (s) {
  ClockStyle.CENTERED -> 0.56f; ClockStyle.STACKED -> 0.62f; ClockStyle.CORNER -> 1.1f; ClockStyle.SPLIT -> 0.52f; ClockStyle.ANALOG -> 0.62f; ClockStyle.WORDS -> 0.62f
}

/** Static, cached background layer: wallpaper + scrims drawn once into one texture. */
@Composable
private fun StaticLayer(env: HomeEnv, scrim: ScrimKind, content: @Composable BoxScope.() -> Unit) {
  val g = LocalGlass.current
  Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
    Wallpaper(env.wall, env.prevWall, env.fade)
    val topA = remember(g) { if (g.screen.width > 0) g.darknessFor(androidx.compose.ui.geometry.Rect(0f, 0f, g.screen.width.toFloat(), g.screen.height * 0.4f), 0.30f, 0.62f) else 0.4f }
    val topAnim by animateFloatAsState(topA, tween(900), label = "topA")
    Box(Modifier.fillMaxSize().drawWithCache {
      val v = Brush.radialGradient(listOf(Color(0x0D0A0C14), Color(0x800A0C14)), Offset(size.width / 2, size.height * 0.32f), size.maxDimension * 0.72f)
      val low = Brush.verticalGradient(0f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0xB30A0C14))
      val top = Brush.verticalGradient(0f to Color(0xFF0A0C14), 0.22f to Color(0xB30A0C14), 0.46f to Color.Transparent)
      val cineL = Brush.radialGradient(listOf(Color(0xCC05060A), Color.Transparent), Offset(0f, size.height), size.width * 0.62f)
      val cineR = Brush.radialGradient(listOf(Color(0x9905060A), Color.Transparent), Offset(size.width, 0f), size.width * 0.42f)
      val minimal = Brush.radialGradient(listOf(Color(0x8C05060A), Color.Transparent), Offset(0f, 0f), size.width * 0.38f)
      onDrawBehind {
        when (scrim) {
          ScrimKind.GLASS -> { drawRect(v); drawRect(low); drawRect(top, alpha = topAnim) }
          ScrimKind.CINEMATIC -> { drawRect(cineL); drawRect(cineR) }
          ScrimKind.RAIL -> { drawRect(Color(0xFF05060A), alpha = 0.32f) }
          ScrimKind.MINIMAL -> { drawRect(minimal); drawRect(low, alpha = 0.6f) }
        }
      }
    })
    content()
  }
}
private enum class ScrimKind { GLASS, CINEMATIC, RAIL, MINIMAL }

// ---------------------------------------------------------------- 1) Glass dock (v4 look)

@Composable
private fun GlassLayout(env: HomeEnv) {
  val st = env.st
  Box(Modifier.fillMaxSize()) {
    StaticLayer(env, ScrimKind.GLASS) {
      val stacked = st.clock.style == ClockStyle.STACKED
      val leftCards = Look.inSlot(st.cards, CardSlot.LEFT).isNotEmpty()
      Column(if (stacked) Modifier.align(Alignment.TopStart).padding(start = if (leftCards) 46.dp + 228.dp + 44.dp else 64.dp, top = 40.dp)
        else Modifier.align(Alignment.TopCenter).padding(top = if (st.clock.style == ClockStyle.CORNER) 56.dp else 40.dp),
        horizontalAlignment = if (stacked) Alignment.Start else Alignment.CenterHorizontally) {
        ClockFace(st.clock, st.use24h, scale = if (st.clock.style == ClockStyle.CORNER) 1.25f else 1f, align = if (stacked) Alignment.Start else Alignment.CenterHorizontally)
        if (st.cards[HomeCard.TOGGLES] == null || st.cards[HomeCard.TOGGLES] == CardSlot.OFF) {
          Spacer(Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPill(Icons.Filled.Star, "Ambient", env.interactive) { env.actions.startAmbient() }
            GlassPill(Icons.Filled.Settings, "Settings", env.interactive) { st.screen = Screen.SETTINGS }
          }
        }
      }
      // One card per side: both get the same fixed size. Two stacked on a side keep natural heights so they clear the dock.
      val left = Look.inSlot(st.cards, CardSlot.LEFT); val right = Look.inSlot(st.cards, CardSlot.RIGHT)
      val cardH = if (left.size <= 1 && right.size <= 1) SideCardHeight else null
      CardStack(left, st, env.actions, env.interactive, SideCardWidth, Modifier.align(Alignment.TopStart).padding(start = 46.dp, top = 44.dp), height = cardH)
      CardStack(right, st, env.actions, env.interactive, SideCardWidth, Modifier.align(Alignment.TopEnd).padding(end = 46.dp, top = 44.dp), height = cardH)
    }
    Dock(env, Modifier.align(Alignment.BottomCenter).padding(bottom = 42.dp))
    if (st.dockEdit == null) T(if (st.moving != null) "‹  ›  Move   ·   OK when done" else "↓  All apps   ·   Hold OK for options", 12.sp,
      Modifier.align(Alignment.BottomCenter).padding(bottom = 13.dp), weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.86f), spacing = 0.5.sp, shadow = TextShadow)
  }
}

// ---------------------------------------------------------------- 2) Cinematic

@Composable
private fun CinematicLayout(env: HomeEnv) {
  val st = env.st
  var label by remember { mutableStateOf(st.favoriteApps.firstOrNull()?.label ?: "All apps") }
  Box(Modifier.fillMaxSize()) {
    StaticLayer(env, ScrimKind.CINEMATIC) {
      Column(Modifier.align(Alignment.TopEnd).padding(end = 46.dp, top = 36.dp), horizontalAlignment = Alignment.End) {
        ClockFace(st.clock, st.use24h, scale = compactScale(st.clock.style), align = Alignment.End)
        Spacer(Modifier.height(14.dp))
        // Side by side (not stacked) so tall cards never reach the tile row.
        val cs = (Look.inSlot(st.cards, CardSlot.LEFT) + Look.inSlot(st.cards, CardSlot.RIGHT)).take(2)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
          cs.forEach { c -> CardStack(listOf(c), st, env.actions, env.interactive, SideCardWidth, max = 1, height = SideCardHeight) }
        }
      }
    }
    Column(Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 26.dp)) {
      FocusLabel(label)
      T("Hold OK for options   ·   ↓  All apps", 12.5.sp, Modifier.padding(top = 2.dp, bottom = 14.dp), weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.8f), spacing = 0.4.sp, shadow = TextShadow)
      TileRow(env) { label = it }
    }
  }
}

/** Big name of the focused app; fades + rises on change (graphicsLayer only). */
@Composable
private fun FocusLabel(text: String) {
  val a = remember { Animatable(1f) }
  var shown by remember { mutableStateOf(text) }
  LaunchedEffect(text) { if (text != shown) { a.animateTo(0f, tween(90)); shown = text; a.animateTo(1f, tween(220, easing = EmphasizedDecel)) } }
  T(shown, 46.sp, Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 10.dp.toPx() }, weight = FontWeight.Light, spacing = (-1).sp, shadow = ClockGlow)
}

@Composable
private fun TileRow(env: HomeEnv, onLabel: (String) -> Unit) {
  val st = env.st; val favs = st.favoriteApps
  val tileW = 204.dp; val gap = 22.dp
  val d = LocalDensity.current
  val pitch = with(d) { (tileW + gap).toPx() }
  val idx = remember { mutableIntStateOf(0) }
  val scroll = remember { Animatable(0f) }
  BoxWithConstraints(Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
    val n = e.nativeKeyEvent
    if (n.keyCode == NKey.KEYCODE_DPAD_DOWN && env.interactive) { if (n.action == NKey.ACTION_DOWN && n.repeatCount == 0) st.allApps = true; true } else false
  }) {
    val visW = constraints.maxWidth.toFloat()
    val contentW = (favs.size + 1) * pitch
    val target = ((idx.intValue - 1).coerceAtLeast(0) * pitch).coerceIn(0f, (contentW - visW + with(d) { 56.dp.toPx() }).coerceAtLeast(0f))
    LaunchedEffect(target) { scroll.animateTo(target, spring(0.9f, 260f)) }
    val ready = dockReady(st)
    Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).graphicsLayer { translationX = -scroll.value; alpha = ready.value }, horizontalArrangement = Arrangement.spacedBy(gap)) {
      favs.forEachIndexed { i, app ->
        key(app.pkg) {
          val k = "row:${app.pkg}"
          AppTile(app, tileW, env.req(k), onFocused = { env.onAppFocus(k); idx.intValue = i; onLabel(app.label) }, onClick = { env.actions.launch(app) },
            onLong = { st.menuFor = app }, canFocus = env.interactive)
        }
      }
      AllAppsTile(tileW, env.req("row:all"), env.interactive, onFocused = { env.onAppFocus("row:all"); idx.intValue = favs.size; onLabel("All apps") }) { st.allApps = true }
    }
  }
}

@Composable
private fun AllAppsTile(width: Dp, fr: FocusRequester, canFocus: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
  var f by remember { mutableStateOf(false) }
  val k = remember { Animatable(0f) }
  LaunchedEffect(f) { k.animateTo(if (f) 1f else 0f, FocusSpring) }
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(width)) {
    Box(Modifier.size(width, width * 9f / 16f).graphicsLayer { val s = 1f + 0.10f * k.value; scaleX = s; scaleY = s }
      .focusRequester(fr).onFocusChanged { f = it.isFocused; if (it.isFocused) onFocused() }.dpadClick(onClick).focusProperties { this.canFocus = canFocus }.focusable()
      .frosted(RoundedCornerShape(width * 0.085f), minDark = 0.35f, maxDark = 0.6f)
      .drawWithContent { drawContent(); drawRoundRect(Color.White, alpha = (0.12f + 0.78f * k.value).coerceIn(0f, 1f), style = Stroke(1.5.dp.toPx()), cornerRadius = CornerRadius(size.width * 0.085f)) },
      Alignment.Center) {
      Canvas(Modifier.size(34.dp)) { val d = size.width / 3f; for (i in 0..2) for (j in 0..2) drawCircle(Color.White, d * 0.26f, Offset(d * (i + 0.5f), d * (j + 0.5f))) }
    }
    Box(Modifier.height(30.dp), Alignment.BottomCenter) { T("All apps", 14.sp, Modifier.graphicsLayer { alpha = k.value }, weight = FontWeight.Medium, shadow = TextShadow) }
  }
}

// ---------------------------------------------------------------- 3) Side rail

@Composable
private fun SideRailLayout(env: HomeEnv) {
  val st = env.st
  val favs = st.favoriteApps
  val rest = remember(st.visibleApps, favs) { val f = favs.map { it.pkg }.toSet(); st.visibleApps.filter { it.pkg !in f } }
  Box(Modifier.fillMaxSize()) {
    StaticLayer(env, ScrimKind.RAIL) {
      Column(Modifier.padding(start = 24.dp, top = 24.dp, bottom = 24.dp).width(372.dp).fillMaxHeight()
        .frosted(RoundedCornerShape(32.dp), minDark = 0.45f, maxDark = 0.66f).padding(horizontal = 26.dp, vertical = 24.dp)) {
        ClockFace(st.clock, st.use24h, scale = compactScale(st.clock.style), align = Alignment.Start)
        Spacer(Modifier.height(18.dp))
        CardStack(Look.inSlot(st.cards, CardSlot.LEFT) + Look.inSlot(st.cards, CardSlot.RIGHT), st, env.actions, env.interactive, 320.dp, max = 2)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          GlassPill(Icons.Filled.Star, "Ambient", env.interactive) { env.actions.startAmbient() }
          GlassPill(Icons.Filled.Settings, "Settings", env.interactive) { st.screen = Screen.SETTINGS }
        }
      }
    }
    Column(Modifier.fillMaxSize().padding(start = 420.dp, top = 30.dp, end = 30.dp)) {
      Row(verticalAlignment = Alignment.Bottom) {
        T("Apps", 24.sp, weight = FontWeight.SemiBold, spacing = (-0.5).sp, shadow = TextShadow)
        Spacer(Modifier.width(10.dp))
        T("${st.visibleApps.size}", 15.sp, Modifier.padding(bottom = 3.dp), weight = FontWeight.Medium, color = Ink.copy(alpha = 0.7f), shadow = TextShadow)
        Spacer(Modifier.weight(1f))
        T("Hold OK for options", 11.5.sp, Modifier.padding(bottom = 4.dp), weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.8f), shadow = TextShadow)
      }
      LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 30.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "h") { T("DOCK", 10.5.sp, Modifier.padding(bottom = 6.dp), weight = FontWeight.Bold, spacing = 1.6.sp, color = Ink.copy(alpha = 0.8f), shadow = TextShadow) }
        itemsIndexed(favs, key = { _, a -> "f" + a.pkg }, contentType = { _, _ -> "tile" }) { _, app ->
          val k = "rail:${app.pkg}"
          AppTile(app, 160.dp, env.req(k), onFocused = { env.onAppFocus(k) }, onClick = { env.actions.launch(app) }, onLong = { st.menuFor = app }, canFocus = env.interactive)
        }
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "h") { T("ALL APPS", 10.5.sp, Modifier.padding(top = 10.dp, bottom = 6.dp), weight = FontWeight.Bold, spacing = 1.6.sp, color = Ink.copy(alpha = 0.8f), shadow = TextShadow) }
        itemsIndexed(rest, key = { _, a -> "r" + a.pkg }, contentType = { _, _ -> "tile" }) { _, app ->
          val k = "rail:${app.pkg}"
          AppTile(app, 160.dp, env.req(k), onFocused = { env.onAppFocus(k) }, onClick = { env.actions.launch(app) }, onLong = { st.menuFor = app }, canFocus = env.interactive)
        }
      }
    }
  }
}

// ---------------------------------------------------------------- 4) Minimal

@Composable
private fun MinimalLayout(env: HomeEnv) {
  val st = env.st
  Box(Modifier.fillMaxSize()) {
    StaticLayer(env, ScrimKind.MINIMAL) {
      ClockFace(st.clock, st.use24h, Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 36.dp),
        scale = if (st.clock.style == ClockStyle.CORNER) 1.1f else compactScale(st.clock.style) * 0.8f, align = Alignment.Start)
    }
    val vis = env.dockVisible
    Dock(env, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).graphicsLayer {
      val v = vis?.value ?: 1f
      translationY = (1f - v) * 150.dp.toPx(); alpha = if (v < 0.02f) 0f else 1f
    })
  }
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun GlassPill(icon: ImageVector, label: String, canFocus: Boolean, onClick: () -> Unit) {
  var f by remember { mutableStateOf(false) }
  val k by animateFloatAsState(if (f) 1f else 0f, tween(160), label = "gp")
  Row(
    Modifier.graphicsLayer { scaleX = 1f + 0.07f * k; scaleY = 1f + 0.07f * k }
      .onFocusChanged { f = it.isFocused }
      .dpadClick(onClick)
      .focusProperties { this.canFocus = canFocus }
      .focusable()
      .frosted(RoundedCornerShape(50), minDark = 0.40f, maxDark = 0.66f)
      .background(Color.White.copy(alpha = 0.94f * k))
      .padding(horizontal = 15.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val c = lerpColor(Ink, Dark, k)
    Image(rememberVectorPainter(icon), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(c))
    Spacer(Modifier.width(7.dp))
    T(label, 13.sp, weight = FontWeight.SemiBold, color = c, shadow = if (k < 0.5f) TextShadow else null)
  }
}


/**
 * 1 when the dock has its apps (snapshot or live). Only a first-ever start has neither: the dock then stays hidden and
 * fades in as one unit once apps load, instead of showing "All apps" alone and filling in. Starts at 1 when ready.
 */
@Composable
private fun dockReady(st: LauncherState): State<Float> =
  animateFloatAsState(if (st.appsLoaded || st.favoriteApps.isNotEmpty()) 1f else 0f, tween(260, easing = LinearOutSlowIn), label = "dockReady")

/** Clip that only cuts left/right (focused icons may still grow upward with their name bubble). */
private val SideClip = GenericShape { s, _ -> addRect(androidx.compose.ui.geometry.Rect(0f, -s.height * 4f, s.width, s.height * 2f)) }

@Composable
private fun Dock(env: HomeEnv, modifier: Modifier) {
  val st = env.st; val favs = st.favoriteApps
  val n = favs.size
  val icon = (if (n <= 10) 56 else if (n <= 14) 56 - (n - 10) * 2 else 48).dp
  val gap = 16.dp; val pad = 18.dp; val sep = 1.5.dp
  val d = LocalDensity.current
  val canFocus = env.interactive && st.dockEdit == null
  val prefix = "dock"
  val cfg = LocalConfiguration.current
  val contentW = pad * 2 + icon * (n + 2) + gap * (n + 2) + sep
  val maxW = cfg.screenWidthDp.dp - 120.dp
  val scrolls = contentW > maxW
  val visW = if (scrolls) maxW else contentW
  val focusIdx = remember { mutableIntStateOf(0) }
  val scroll = remember { Animatable(0f) }
  val target = if (!scrolls) 0f else with(d) {
    val i = focusIdx.intValue
    val center = pad.toPx() + i * (icon + gap).toPx() + icon.toPx() / 2 + (if (i >= n) (sep + gap).toPx() else 0f)
    (center - visW.toPx() / 2).coerceIn(0f, (contentW - visW).toPx())
  }
  LaunchedEffect(target) { scroll.animateTo(target, spring(0.9f, 300f)) }
  val ready = dockReady(st)
  Box(modifier.width(visW).graphicsLayer { alpha = if (st.dockEdit != null) 0f else ready.value }) {
    Box(Modifier.matchParentSize().frosted(RoundedCornerShape(30.dp), tint = Color(0x14FFFFFF), sheen = 0.10f, minDark = 0.22f, maxDark = 0.5f))
    Box(Modifier.then(if (scrolls) Modifier.clip(SideClip) else Modifier)) {
      // Fixed slots: focus only scales/translates in graphicsLayer, so nothing re-measures while animating.
      Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).graphicsLayer { translationX = -scroll.value }.padding(horizontal = pad, vertical = 11.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(gap)) {
        favs.forEachIndexed { i, app ->
          key(app.pkg) {
            val k = "$prefix:${app.pkg}"
            DockIcon(app.label, icon, env.req(k), lighten(app.tint, 0.3f), st.moving == app.pkg, canFocus,
              onFocused = { env.onAppFocus(k); focusIdx.intValue = i }, onClick = { env.actions.launch(app) }, onLong = { st.menuFor = app },
              onMove = { st.moveFav(app.pkg, it) }, onMoveDone = { st.moving = null }, onDown = { st.allApps = true }) { AppSquircle(app) }
          }
        }
        Box(Modifier.align(Alignment.CenterVertically).width(sep).height(icon * 0.72f).clip(RoundedCornerShape(1.dp)).background(Color(0x40FFFFFF)))
        DockIcon("All apps", icon, env.req("$prefix:all"), Color(0xFFBFD0FF), false, canFocus,
          onFocused = { env.onAppFocus("$prefix:all"); focusIdx.intValue = n }, onClick = { st.allApps = true }, onLong = null, onDown = { st.allApps = true }) {
          Box(Modifier.fillMaxSize().background(Color(0x33FFFFFF)))
          Canvas(Modifier.fillMaxSize(0.42f).align(Alignment.Center)) {
            val dd = size.width / 3f
            for (i in 0..2) for (j in 0..2) drawCircle(Color.White, dd * 0.26f, Offset(dd * (i + 0.5f), dd * (j + 0.5f)))
          }
        }
        DockIcon("Edit dock", icon, env.req("$prefix:edit"), Color(0xFFBFD0FF), false, canFocus,
          onFocused = { env.onAppFocus("$prefix:edit"); focusIdx.intValue = n + 1 }, onClick = { enterDockEdit(st) }, onLong = null, onDown = { st.allApps = true }) {
          Box(Modifier.fillMaxSize().background(Color(0x22FFFFFF)))
          Image(rememberVectorPainter(Icons.Filled.Edit), null, Modifier.fillMaxSize(0.4f).align(Alignment.Center), colorFilter = ColorFilter.tint(Color.White))
        }
      }
    }
  }
}

/** Normalized full-bleed tile (see [Tiles]) so every dock icon fills the same squircle. */
@Composable
private fun BoxScope.AppSquircle(app: AppEntry) {
  val bg = remember(app.tint) { Brush.linearGradient(listOf(lighten(app.tint, 0.14f), darken(app.tint, 0.32f))) }
  Box(Modifier.fillMaxSize().background(bg))
  val t = app.tile
  if (t != null) Image(t, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
  else T(app.label.take(1).uppercase(), 24.sp, Modifier.align(Alignment.Center), weight = FontWeight.Light)
}

@Composable
private fun DockIcon(
  label: String, iconSize: Dp, fr: FocusRequester, glow: Color, moving: Boolean, canFocus: Boolean,
  onFocused: () -> Unit, onClick: () -> Unit, onLong: (() -> Unit)?, onMove: (Int) -> Unit = {}, onMoveDone: () -> Unit = {},
  onDown: () -> Unit, content: @Composable BoxScope.() -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  // One focus progress drives scale, lift, glow, outline and the name bubble together. It's a State object (not delegated),
  // read only inside layer/draw lambdas: no recomposition per frame.
  val k = animateFloatAsState(if (focused) 1f else 0f, FocusSpring, label = "dk")
  val keys = Modifier.onKeyEvent { e ->
    val n = e.nativeKeyEvent; val down = n.action == NKey.ACTION_DOWN
    if (moving) when (n.keyCode) {
      NKey.KEYCODE_DPAD_LEFT -> { if (down) onMove(-1); true }
      NKey.KEYCODE_DPAD_RIGHT -> { if (down) onMove(1); true }
      NKey.KEYCODE_DPAD_CENTER, NKey.KEYCODE_ENTER, NKey.KEYCODE_BACK -> { if (!down) onMoveDone(); true }
      NKey.KEYCODE_DPAD_UP, NKey.KEYCODE_DPAD_DOWN -> true
      else -> false
    } else if (n.keyCode == NKey.KEYCODE_DPAD_DOWN) { if (down && n.repeatCount == 0) onDown(); true } else false
  }
  Box(Modifier.zIndex(if (focused) 1f else 0f).size(iconSize)) {
    Box(
      Modifier.fillMaxSize()
        .graphicsLayer {
          transformOrigin = TransformOrigin(0.5f, 1f)
          val sc = 1f + 0.22f * k.value; scaleX = sc; scaleY = sc
          translationY = -10.dp.toPx() * k.value
        }
        .drawWithCache {
          // Smooth, band-free halo + contact shadow: one pre-blurred, dithered mask per tile size (see [SoftGlow]),
          // tinted at draw time. Replaces 8 + 6 stacked translucent rects (each edge was a visible ring).
          val glowM = SoftGlow.margin.toPx(); val shM = SoftGlow.shadowMargin.toPx()
          val halo = SoftGlow.halo(size.width.toInt(), size.height.toInt(), glowM)
          val shadow = SoftGlow.shadow(size.width.toInt(), size.height.toInt(), shM)
          val tint = ColorFilter.tint(glow, BlendMode.SrcIn)
          val shY = 3.dp.toPx()
          onDrawBehind {
            val kk = k.value.coerceIn(0f, 1f)
            drawImage(shadow, Offset(-shM, -shM + shY), alpha = 0.55f + 0.25f * kk)
            if (kk > 0.004f) drawImage(halo, Offset(-glowM, -glowM), alpha = kk, colorFilter = tint)
          }
        }
        .then(keys)
        .focusRequester(fr)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .dpadClick(onClick, onLong)
        .focusProperties { this.canFocus = canFocus }
        .focusable()
        .clip(Squircle)
        .drawWithCache {
          val hl = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.Transparent), endY = size.height * 0.6f)
          val o = Squircle.createOutline(size, layoutDirection, this); val st = Stroke(2.dp.toPx())
          onDrawWithContent {
            drawContent(); drawRect(hl)
            if (!moving) drawOutline(o, Color.White, alpha = (0.14f + 0.82f * k.value).coerceIn(0f, 1f), style = st)
          }
        }
    ) {
      content()
      if (moving) MovingRing(Squircle, 2.5.dp)
    }
    Box(
      Modifier.align(Alignment.BottomCenter).wrapContentSize(Alignment.BottomCenter, unbounded = true)
        .graphicsLayer {
          // Same driver as the tile: the bubble rises and fades in over the last 80% of the focus motion.
          val b = ((k.value - 0.2f) / 0.8f).coerceIn(0f, 1f)
          compositingStrategy = CompositingStrategy.ModulateAlpha   // small fade without an offscreen buffer
          alpha = b
          val sc = 0.92f + 0.08f * b; scaleX = sc; scaleY = sc
          translationY = -(iconSize.toPx() * 1.22f + 10.dp.toPx() + 9.dp.toPx()) + (1f - b) * 8.dp.toPx()
        }
        .clip(RoundedCornerShape(50)).background(Color(0xF2FFFFFF)).padding(horizontal = 12.dp, vertical = 5.dp)
    ) { T(label, 11.5.sp, weight = FontWeight.Bold, color = Dark) }
  }
}

@Composable
private fun BoxScope.AllAppsPanel(st: LauncherState, actions: Actions, req: (String) -> FocusRequester) {
  val shown = st.allApps
  // Always composed (prewarmed: grid items, text layouts and textures exist before the first open).
  // The slide is a pure translation of one opaque layer: no alpha on big layers, no per-frame re-recording.
  val p = remember { Animatable(0f) }
  val pState = p.asState()
  LaunchedEffect(shown) {
    if (shown) p.animateTo(1f, tween(320, easing = EmphasizedDecel)) else p.animateTo(0f, tween(200, easing = EmphasizedAccel))
  }
  val lastKey = remember { arrayOfNulls<String>(1) }
  val focusedIndex = remember { IntArray(1) }
  val apps = st.visibleApps
  val grid = rememberLazyGridState()
  LaunchedEffect(shown) {
    if (shown) {
      lastKey[0] = null
      val first = apps.firstOrNull()?.let { req("all:${it.pkg}") }
      delay(16); tryFocus(first)                         // next frame: canFocus has been applied
      delay(120); if (lastKey[0] == null) tryFocus(first) // retry once if the dock's focus loss raced us
    } else { delay(220); grid.scrollToItem(0) }
  }
  LaunchedEffect(st.menuFor) { if (st.menuFor == null && shown) { delay(40); lastKey[0]?.let { tryFocus(req(it)) } } }

  val d = LocalDensity.current
  val corner = with(d) { 32.dp.toPx() }
  val side = with(d) { 42.dp.toPx() }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val screenH = constraints.maxHeight.toFloat()
    val hPx = screenH * 0.86f
    // Scrim only over the strip above the drawer (no full-screen blended pass under an opaque panel).
    Box(Modifier.fillMaxSize().drawBehind {
      val v = pState.value
      if (v > 0.001f) {
        val top = screenH - hPx + (1f - v) * (hPx + 60f)
        val a = 0.45f * v; val cut = (top + corner).coerceAtMost(size.height)
        drawRect(Color.Black, size = Size(size.width, cut), alpha = a)
        if (cut < size.height) {   // the thin strips beside the drawer
          drawRect(Color.Black, Offset(0f, cut), Size(side + 1f, size.height - cut), alpha = a)
          drawRect(Color.Black, Offset(size.width - side - 1f, cut), Size(side + 1f, size.height - cut), alpha = a)
        }
      }
    })
    Column(
      Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.86f)
        .padding(horizontal = 42.dp)
        .graphicsLayer {
          val v = pState.value
          translationY = (1f - v) * (hPx + 60f)
          alpha = if (v > 0.001f) 1f else 0f      // hidden → skipped entirely; never a fractional alpha
          // While sliding, the drawer is ONE pre-rendered texture (rendered once on the first frame, then just moved).
          compositingStrategy = if (p.isRunning) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }
        .onPreviewKeyEvent { e ->
          val n = e.nativeKeyEvent
          if (n.keyCode == NKey.KEYCODE_DPAD_UP && focusedIndex[0] < 5 && st.menuFor == null && shown) {
            if (n.action == NKey.ACTION_DOWN && n.repeatCount == 0) st.allApps = false; true
          } else false
        }
        .frosted(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), tint = Color.Transparent, edge = Color(0x4DFFFFFF), sheen = 0f,
          minDark = 0.52f, maxDark = 0.64f, fixedOrigin = Offset(side, screenH - hPx))
        .padding(horizontal = 34.dp),
    ) {
      Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(44.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF)))
      Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
        T("All apps", 24.sp, weight = FontWeight.SemiBold, spacing = (-0.5).sp)
        Spacer(Modifier.width(10.dp))
        T("${apps.size}", 15.sp, Modifier.padding(bottom = 3.dp), weight = FontWeight.Medium, color = Ink.copy(alpha = 0.6f))
        Spacer(Modifier.weight(1f))
        T("Hold OK for options   ·   ↑ or BACK to close", 11.5.sp, Modifier.padding(bottom = 4.dp), weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.72f), spacing = 0.4.sp)
      }
      LazyVerticalGrid(
        columns = GridCells.Fixed(5), state = grid,
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(top = 26.dp, bottom = 34.dp, start = 2.dp, end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        itemsIndexed(apps, key = { _, a -> a.pkg }, contentType = { _, _ -> "tile" }) { i, app ->
          val kk = "all:${app.pkg}"
          AppTile(app, 146.dp, req(kk), onFocused = { lastKey[0] = kk; focusedIndex[0] = i },
            onClick = { actions.launch(app) }, onLong = { st.menuFor = app }, canFocus = shown, snapFocus = { p.isRunning })
        }
      }
    }
  }
}


/**
 * Soft, banding-free glow and shadow masks for dock tiles. Each is a rounded rect (corner 0.27 × width, like the tile squircle)
 * blurred by three box passes (≈ gaussian), baked once per size into an ARGB bitmap with an ordered (Bayer) dither so the
 * 8-bit alpha never steps into visible rings. All tiles share the cached masks; drawing one is a single drawImage.
 */
internal object SoftGlow {
  val margin = 16.dp; val shadowMargin = 10.dp
  /** Peak alpha of the halo at the tile edge, and of the contact shadow. */
  const val HALO_PEAK = 0.42f; const val SHADOW_PEAK = 0.30f
  private val cache = HashMap<String, ImageBitmap>()
  fun halo(w: Int, h: Int, m: Float) = get("h$w:$h:${m.toInt()}") { mask(w, h, m.toInt(), m / 4f, HALO_PEAK, 0) }
  fun shadow(w: Int, h: Int, m: Float) = get("s$w:$h:${m.toInt()}") { mask(w, h, m.toInt(), m / 4f, SHADOW_PEAK, 0xFF000000.toInt()) }
  private fun get(key: String, make: () -> ImageBitmap) = synchronized(cache) { cache.getOrPut(key, make) }

  private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

  /** Coverage (0..1) of the blurred rounded rect, before peak scaling; exposed for tests. */
  fun coverage(w: Int, h: Int, m: Int, sigma: Float): FloatArray {
    val W = w + 2 * m; val H = h + 2 * m; val r = 0.27f * w
    val a = FloatArray(W * H)
    for (y in 0 until H) for (x in 0 until W) {
      // signed distance to the rounded rect, antialiased over 1px
      val px = x + 0.5f - m - w / 2f; val py = y + 0.5f - m - h / 2f
      val qx = kotlin.math.abs(px) - (w / 2f - r); val qy = kotlin.math.abs(py) - (h / 2f - r)
      val out = kotlin.math.hypot(maxOf(qx, 0f).toDouble(), maxOf(qy, 0f).toDouble()).toFloat() + minOf(maxOf(qx, qy), 0f) - r
      a[y * W + x] = (0.5f - out).coerceIn(0f, 1f)
    }
    // 3 box passes of radius rb ≈ gaussian sigma (sigma² = 3·((2rb+1)²−1)/12  ⇒  2rb+1 = √(4σ²+1))
    val rb = maxOf(1, kotlin.math.round((kotlin.math.sqrt(4.0 * sigma * sigma + 1.0) - 1.0) / 2.0).toInt())
    val tmp = FloatArray(W * H)
    repeat(3) { boxH(a, tmp, W, H, rb); boxV(tmp, a, W, H, rb) }
    return a
  }
  private fun boxH(src: FloatArray, dst: FloatArray, W: Int, H: Int, r: Int) {
    val n = 2 * r + 1
    for (y in 0 until H) { val o = y * W; var sum = 0f
      for (i in -r..r) sum += src[o + i.coerceIn(0, W - 1)]
      for (x in 0 until W) { dst[o + x] = sum / n; sum += src[o + (x + r + 1).coerceAtMost(W - 1)] - src[o + (x - r).coerceAtLeast(0)] } }
  }
  private fun boxV(src: FloatArray, dst: FloatArray, W: Int, H: Int, r: Int) {
    val n = 2 * r + 1
    for (x in 0 until W) { var sum = 0f
      for (i in -r..r) sum += src[i.coerceIn(0, H - 1) * W + x]
      for (y in 0 until H) { dst[y * W + x] = sum / n; sum += src[(y + r + 1).coerceAtMost(H - 1) * W + x] - src[(y - r).coerceAtLeast(0) * W + x] } }
  }
  private fun mask(w: Int, h: Int, m: Int, sigma: Float, peak: Float, rgb: Int): ImageBitmap {
    val W = w + 2 * m; val H = h + 2 * m
    val cov = coverage(w, h, m, sigma)
    // Normalize so the tile edge (≈ 0.5 coverage) reaches [peak]; inside the tile is hidden by the tile itself.
    val px = IntArray(W * H)
    val cr = (rgb shr 16) and 0xFF; val cg = (rgb shr 8) and 0xFF; val cb = rgb and 0xFF
    val white = rgb == 0
    for (i in px.indices) {
      val v = (cov[i] * 2f).coerceAtMost(1f) * peak * 255f
      val d = (bayer[((i / W) and 3) * 4 + ((i % W) and 3)] + 0.5f) / 16f   // 0..1 ordered dither threshold
      val a = (v + d - 0.5f).let { kotlin.math.floor(it + 0.5f).toInt() }.coerceIn(0, 255)
      // premultiplied for Bitmap; white mask gets tinted by the caller (SrcIn), black stays black
      px[i] = if (white) (a shl 24) or (a shl 16) or (a shl 8) or a else (a shl 24) or ((cr * a / 255) shl 16) or ((cg * a / 255) shl 8) or (cb * a / 255)
    }
    val b = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888)
    b.setPixels(px, 0, W, 0, 0, W, H)   // setPixels takes unpremultiplied colors; for white/black masks that's equivalent
    return b.asImageBitmap()
  }
}
