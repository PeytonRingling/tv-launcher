package com.peyton.tvlauncher

import android.view.KeyEvent as NKey
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

enum class EditKey { LEFT, RIGHT, UP, DOWN, OK, BACK, MENU }

/**
 * Remote-friendly dock editor (pure logic, no UI): focus walks over the icons, then "+ Add apps", then "Done".
 * OK picks an icon up / drops it; Left/Right moves a picked icon live; Down focuses the icon's ✕ badge
 * (OK removes), MENU removes directly; Up undoes the last removal; BACK drops / leaves the badge / exits.
 * Every change is reported through [onChange] so the dock is saved as you go.
 */
class DockEditor(initial: List<String>, startFocus: Int = 0, private val onChange: (List<String>) -> Unit = {}) {
  var items by mutableStateOf(initial.distinct())
    private set
  var focus by mutableIntStateOf(startFocus.coerceIn(0, initial.distinct().size + 1))
    private set
  var picked by mutableStateOf(false)
    private set
  var onBadge by mutableStateOf(false)
    private set
  /** Last removal (package, old index) while its undo hint is showing. */
  var undo by mutableStateOf<Pair<String, Int>?>(null)
  var checklist by mutableStateOf(false)
  var done by mutableStateOf(false)
    private set
  val addIndex get() = items.size
  val doneIndex get() = items.size + 1
  val focusedPkg get() = items.getOrNull(focus)

  fun key(k: EditKey) {
    if (done || checklist) return
    val onIcon = focus in items.indices
    when (k) {
      EditKey.LEFT -> if (picked) move(-1) else { onBadge = false; focus = (focus - 1).coerceAtLeast(0) }
      EditKey.RIGHT -> if (picked) move(1) else { onBadge = false; focus = (focus + 1).coerceAtMost(doneIndex) }
      EditKey.DOWN -> if (onIcon && !picked) onBadge = true
      EditKey.UP -> if (onBadge) onBadge = false else if (!picked) undoLast()
      EditKey.OK -> when {
        onIcon && onBadge -> remove()
        onIcon -> picked = !picked
        focus == addIndex -> checklist = true
        focus == doneIndex -> done = true
      }
      EditKey.MENU -> if (onIcon && !picked) remove()
      EditKey.BACK -> when { picked -> picked = false; onBadge -> onBadge = false; else -> done = true }
    }
  }

  private fun move(d: Int) {
    val j = focus + d
    if (j !in items.indices) return
    val l = items.toMutableList(); val p = l.removeAt(focus); l.add(j, p)
    items = l; focus = j; onChange(l)
  }

  private fun remove() {
    val p = items.getOrNull(focus) ?: return
    undo = p to focus
    items = items - p
    onBadge = false
    focus = focus.coerceAtMost(addIndex)
    onChange(items)
  }

  fun undoLast(): Boolean {
    val (p, i) = undo ?: return false
    if (p in items) { undo = null; return false }
    val l = items.toMutableList(); l.add(i.coerceIn(0, l.size), p)
    items = l; focus = l.indexOf(p); undo = null; onChange(l)
    return true
  }

  /** Checklist toggle: adds at the end, or removes (no undo needed, the checklist shows it). */
  fun toggle(pkg: String) {
    items = if (pkg in items) items - pkg else if (items.size < MAX_DOCK) items + pkg else items
    focus = focus.coerceAtMost(doneIndex)
    onChange(items)
  }

  fun closeChecklist() { checklist = false; focus = addIndex }
  fun exit() { picked = false; done = true }

  fun hint(): String = when {
    checklist -> "OK  Add / remove      ↑ ↓ ◀ ▶  Browse      BACK  Done"
    picked -> "◀ ▶  Move      OK  Drop here      BACK  Drop"
    onBadge -> "OK  Remove from dock      ↑  Back to icon      BACK  Cancel"
    focus == addIndex -> "OK  Choose apps for the dock      ◀ ▶  Select      BACK  Done"
    focus == doneIndex -> "OK  Finish editing      ◀ ▶  Select"
    else -> "OK  Pick up      ▼  Remove      MENU  Remove now" + (if (undo != null) "      ↑  Undo" else "") + "      BACK  Done"
  }
}

private fun keyOf(code: Int): EditKey? = when (code) {
  NKey.KEYCODE_DPAD_LEFT -> EditKey.LEFT; NKey.KEYCODE_DPAD_RIGHT -> EditKey.RIGHT
  NKey.KEYCODE_DPAD_UP -> EditKey.UP; NKey.KEYCODE_DPAD_DOWN -> EditKey.DOWN
  NKey.KEYCODE_DPAD_CENTER, NKey.KEYCODE_ENTER, NKey.KEYCODE_NUMPAD_ENTER, NKey.KEYCODE_BUTTON_A -> EditKey.OK
  NKey.KEYCODE_BACK, NKey.KEYCODE_ESCAPE -> EditKey.BACK
  NKey.KEYCODE_MENU -> EditKey.MENU
  else -> null
}

/**
 * Edit-mode overlay. One focus target receives all keys (no focus hopping between icons), and every icon sits in a
 * fixed-size box moved only by graphicsLayer translation, so reflow and wiggle never re-measure anything.
 */
@Composable
fun DockEditOverlay(st: LauncherState, ed: DockEditor) {
  val fr = remember { FocusRequester() }
  LaunchedEffect(ed.checklist) { if (!ed.checklist) { delay(16); tryFocus(fr) } }
  LaunchedEffect(ed.done) { if (ed.done) st.dockEdit = null }
  LaunchedEffect(ed.undo) { if (ed.undo != null) { delay(6000); ed.undo = null } }
  val byPkg = remember(st.apps) { st.apps.associateBy { it.pkg } }
  val okDown = remember { BooleanArray(1) }
  val lastRepeat = remember { LongArray(1) }

  Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color(0xEB060709), 0.42f to Color(0xC2060709), 1f to Color(0xB3060709)))
    .focusRequester(fr)
    .onKeyEvent { e ->
      val n = e.nativeKeyEvent; val k = keyOf(n.keyCode) ?: return@onKeyEvent false
      val down = n.action == NKey.ACTION_DOWN
      when (k) {
        EditKey.OK, EditKey.BACK, EditKey.MENU -> {   // act on release, only if the press started here
          if (down && n.repeatCount == 0) okDown[0] = true
          if (!down && okDown[0]) { okDown[0] = false; ed.key(k) }
        }
        else -> if (down) {
          if (n.repeatCount > 0) { if (n.eventTime - lastRepeat[0] < 140) return@onKeyEvent true; lastRepeat[0] = n.eventTime }
          ed.key(k)
        }
      }
      true
    }.focusable()) {
    Column(Modifier.align(Alignment.TopCenter).padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      T("Edit dock", 40.sp, weight = FontWeight.Light, spacing = (-0.5).sp)
      T("${ed.items.size} app${if (ed.items.size == 1) "" else "s"}  ·  saved as you go", 15.sp, Modifier.padding(top = 4.dp), color = Ink.copy(alpha = 0.7f))
    }
    EditStrip(ed, byPkg, Modifier.align(Alignment.Center).offset(y = 40.dp))

    // Undo toast
    val u = ed.undo
    val ua = remember { Animatable(0f) }
    LaunchedEffect(u) { ua.animateTo(if (u != null) 1f else 0f, tween(220)) }
    var lastLabel by remember { mutableStateOf("") }
    if (u != null) lastLabel = byPkg[u.first]?.label ?: u.first
    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp)
      .graphicsLayer { alpha = ua.value; translationY = (1f - ua.value) * 16.dp.toPx() }
      .clip(RoundedCornerShape(50)).background(Color(0xF2FFFFFF)).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      T("Removed $lastLabel", 14.sp, weight = FontWeight.SemiBold, color = Color(0xFF101115))
      Spacer(Modifier.width(14.dp))
      T("↑  Undo", 14.sp, weight = FontWeight.Bold, color = Color(0xFF2A55D4))
    }

    // Remote hint bar
    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))
      .padding(horizontal = 22.dp, vertical = 10.dp)) {
      T(ed.hint(), 13.5.sp, weight = FontWeight.SemiBold, spacing = 0.4.sp)
    }

    if (ed.checklist) AddAppsChecklist(st, ed)
  }
}

@Composable
private fun EditStrip(ed: DockEditor, byPkg: Map<String, AppEntry>, modifier: Modifier) {
  val icon = 64.dp; val gap = 22.dp
  val d = LocalDensity.current
  val pitch = with(d) { (icon + gap).toPx() }
  val n = ed.items.size + 2                       // icons + "+ Add apps" + "Done"
  BoxWithConstraints(modifier.fillMaxWidth().height(icon + 120.dp).drawWithContent {
    // Only the tray's width is visible when the strip scrolls (vertical room kept for lift, badge and labels).
    val vw = minOf(n * pitch - gap.toPx() + 40.dp.toPx(), size.width - 120.dp.toPx())
    clipRect((size.width - vw) / 2, -size.height, (size.width + vw) / 2, size.height * 2) { this@drawWithContent.drawContent() }
  }) {
    val maxW = constraints.maxWidth.toFloat() - with(d) { 120.dp.toPx() }
    val contentW = n * pitch - with(d) { gap.toPx() } + with(d) { 40.dp.toPx() }
    val scroll = remember { Animatable(0f) }
    val target = if (contentW <= maxW) 0f else (ed.focus * pitch + pitch / 2 - maxW / 2).coerceIn(0f, contentW - maxW)
    LaunchedEffect(target) { scroll.animateTo(target, spring(0.9f, 300f)) }
    val visW = minOf(contentW, maxW)
    val startX = (constraints.maxWidth - visW) / 2f + with(d) { 20.dp.toPx() }
    val wiggle = rememberInfiniteTransition(label = "wig").animateFloat(-1f, 1f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "w")
    // Frosted tray behind the strip
    Box(Modifier.align(Alignment.Center).width(with(d) { visW.toDp() }).height(icon + 44.dp).clip(RoundedCornerShape(34.dp)).background(Color(0x26FFFFFF)))
    ed.items.forEachIndexed { i, pkg ->
      key(pkg) {
        val app = byPkg[pkg]
        EditIcon(app, pkg, i, icon, pitch, startX, scroll, ed.focus == i, ed.focus == i && ed.picked, ed.focus == i && ed.onBadge, wiggle, i)
      }
    }
    // "+ Add apps" and "Done" are slots after the icons
    SlotBox(ed.addIndex, icon, pitch, startX, scroll, ed.focus == ed.addIndex) {
      Box(Modifier.fillMaxSize().clip(IconSquircle).background(Color(0x33FFFFFF)), Alignment.Center) {
        Image(rememberVectorPainter(Icons.Filled.Add), null, Modifier.size(30.dp), colorFilter = ColorFilter.tint(Color.White))
      }
    }
    SlotBox(ed.doneIndex, icon, pitch, startX, scroll, ed.focus == ed.doneIndex, label = "Done") {
      Box(Modifier.fillMaxSize().clip(CircleShape).background(Accent), Alignment.Center) {
        Image(rememberVectorPainter(Icons.Filled.Check), null, Modifier.size(30.dp), colorFilter = ColorFilter.tint(Color(0xFF0B0D14)))
      }
    }
  }
}

@Composable
private fun BoxScope.SlotBox(index: Int, icon: Dp, pitch: Float, startX: Float, scroll: Animatable<Float, *>, focused: Boolean, label: String = "Add apps", content: @Composable BoxScope.() -> Unit) {
  val x = remember { Animatable(index * pitch) }
  LaunchedEffect(index) { x.animateTo(index * pitch, spring(0.82f, 380f)) }
  val k = remember { Animatable(0f) }
  LaunchedEffect(focused) { k.animateTo(if (focused) 1f else 0f, spring(0.8f, 420f)) }
  Column(Modifier.align(Alignment.CenterStart).graphicsLayer { translationX = startX + x.value - scroll.value }, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(icon).graphicsLayer { val s = 1f + 0.16f * k.value; scaleX = s; scaleY = s }.drawWithContent {
      drawContent(); if (k.value > 0.01f) drawRoundRect(Color.White, alpha = k.value, style = Stroke(2.5.dp.toPx()), cornerRadius = CornerRadius(size.width * 0.27f))
    }, content = content)
    T(label, 12.sp, Modifier.padding(top = 14.dp).graphicsLayer { alpha = 0.55f + 0.45f * k.value }, weight = FontWeight.SemiBold)
  }
}

@Composable
private fun BoxScope.EditIcon(
  app: AppEntry?, pkg: String, index: Int, icon: Dp, pitch: Float, startX: Float, scroll: Animatable<Float, *>,
  focused: Boolean, picked: Boolean, badge: Boolean, wiggle: State<Float>, seed: Int,
) {
  val x = remember { Animatable(index * pitch) }
  LaunchedEffect(index) { x.animateTo(index * pitch, spring(0.82f, 380f)) }      // smooth reflow when neighbours move
  val k = remember { Animatable(0f) }; val lift = remember { Animatable(0f) }; val b = remember { Animatable(0f) }
  val appear = remember { Animatable(0f) }
  LaunchedEffect(Unit) { appear.animateTo(1f, spring(0.7f, 300f)) }
  LaunchedEffect(focused) { k.animateTo(if (focused) 1f else 0f, spring(0.8f, 420f)) }
  LaunchedEffect(picked) { lift.animateTo(if (picked) 1f else 0f, spring(0.7f, 360f)) }
  LaunchedEffect(badge) { b.animateTo(if (badge) 1f else 0f, spring(0.7f, 420f)) }
  val phase = if (seed % 2 == 0) 1f else -1f
  Column(Modifier.align(Alignment.CenterStart).graphicsLayer { translationX = startX + x.value - scroll.value }, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(icon).graphicsLayer {
      val s = (1f + 0.14f * k.value + 0.08f * lift.value) * appear.value; scaleX = s; scaleY = s
      translationY = -18.dp.toPx() * lift.value
      // Subtle wiggle (alternating phase) for every icon not picked up; graphicsLayer rotation only.
      rotationZ = if (picked) 0f else wiggle.value * 1.6f * phase
      transformOrigin = TransformOrigin(0.5f, 0.5f)
    }.drawBehind {
      val l = lift.value
      if (l > 0.01f) for (i in 5 downTo 1) {
        val sp = 4.dp.toPx() * i
        drawRoundRect(Color.Black, Offset(-sp / 2, sp * 1.4f), Size(size.width + sp, size.height + sp), CornerRadius(size.width * 0.27f + sp), alpha = 0.06f * l)
      }
    }) {
      if (app != null) UniformIcon(app, icon) else Box(Modifier.fillMaxSize().clip(IconSquircle).background(Color(0x33FFFFFF)))
      // focus / picked ring
      Box(Modifier.matchParentSize().drawWithContent {
        val a = maxOf(k.value * (1f - b.value), lift.value)
        if (a > 0.01f) drawRoundRect(if (lift.value > 0.5f) Accent else Color.White, alpha = a.coerceIn(0f, 1f), style = Stroke(2.5.dp.toPx()), cornerRadius = CornerRadius(size.width * 0.27f))
      })
      // ✕ badge (focused with ▼)
      Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).size(24.dp).graphicsLayer {
        val s = 1f + 0.35f * b.value; scaleX = s; scaleY = s; alpha = if (picked) 0f else 1f
      }.clip(CircleShape).background(lerpColor(Color(0xE63A3D48), Color(0xFFFF5A4E), b.value)), Alignment.Center) {
        Image(rememberVectorPainter(Icons.Filled.Close), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(Color.White))
      }
      if (picked) {   // ◀ ▶ arrows beside a lifted icon
        Image(rememberVectorPainter(Icons.Filled.KeyboardArrowLeft), null, Modifier.align(Alignment.CenterStart).offset(x = (-30).dp).size(26.dp), colorFilter = ColorFilter.tint(Accent))
        Image(rememberVectorPainter(Icons.Filled.KeyboardArrowRight), null, Modifier.align(Alignment.CenterEnd).offset(x = 30.dp).size(26.dp), colorFilter = ColorFilter.tint(Accent))
      }
    }
    T(app?.label ?: pkg.substringAfterLast('.'), 12.sp, Modifier.padding(top = 14.dp).width(icon + 18.dp).graphicsLayer { alpha = 0.55f + 0.45f * k.value },
      weight = FontWeight.SemiBold, align = androidx.compose.ui.text.style.TextAlign.Center)
  }
}

/** Frosted checklist of every app; OK toggles it in/out of the dock; header shows the current dock order. */
@Composable
fun AddAppsChecklist(st: LauncherState, ed: DockEditor) {
  val apps = st.visibleApps
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(16); tryFocus(first) }
  val byPkg = remember(st.apps) { st.apps.associateBy { it.pkg } }
  Box(Modifier.fillMaxSize().background(Color(0x99000000)).onKeyEvent { e ->
    val n = e.nativeKeyEvent
    if (n.keyCode == NKey.KEYCODE_BACK || n.keyCode == NKey.KEYCODE_ESCAPE) { if (n.action == NKey.ACTION_UP) ed.closeChecklist(); true } else false
  }, Alignment.Center) {
    Column(Modifier.fillMaxWidth(0.86f).fillMaxHeight(0.84f).frosted(RoundedCornerShape(32.dp), minDark = 0.55f, maxDark = 0.7f).padding(horizontal = 30.dp, vertical = 24.dp)) {
      Row(verticalAlignment = Alignment.Bottom) {
        T("Add apps to the dock", 26.sp, weight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        T("${ed.items.size} of $MAX_DOCK", 15.sp, Modifier.padding(bottom = 3.dp), color = Ink.copy(alpha = 0.65f))
        Spacer(Modifier.weight(1f))
        T("OK  Add / remove     BACK  Done", 12.5.sp, Modifier.padding(bottom = 4.dp), weight = FontWeight.SemiBold, color = Ink.copy(alpha = 0.75f))
      }
      // Current dock order
      Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        T("DOCK", 10.5.sp, weight = FontWeight.Bold, spacing = 1.6.sp, color = Ink.copy(alpha = 0.8f))
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ed.items.take(18).forEachIndexed { i, p ->
            byPkg[p]?.let { a ->
              Box { UniformIcon(a, 30.dp); T("${i + 1}", 8.5.sp, Modifier.align(Alignment.BottomEnd).clip(CircleShape).background(Color(0xCC000000)).padding(horizontal = 3.dp), weight = FontWeight.Bold) }
            }
          }
          if (ed.items.size > 18) T("+${ed.items.size - 18}", 12.sp, Modifier.align(Alignment.CenterVertically), color = Ink.copy(alpha = 0.7f))
        }
      }
      LazyVerticalGrid(GridCells.Fixed(3), Modifier.padding(top = 14.dp).fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
        itemsIndexed(apps, key = { _, a -> a.pkg }) { i, app ->
          CheckRow(app, app.pkg in ed.items, ed.items.indexOf(app.pkg), if (i == 0) first else null) { ed.toggle(app.pkg) }
        }
      }
    }
  }
}

@Composable
private fun CheckRow(app: AppEntry, on: Boolean, pos: Int, fr: FocusRequester?, onClick: () -> Unit) {
  var f by remember { mutableStateOf(false) }
  val k = remember { Animatable(0f) }
  LaunchedEffect(f) { k.animateTo(if (f) 1f else 0f, tween(140)) }
  val c = remember { Animatable(if (on) 1f else 0f) }
  LaunchedEffect(on) { c.animateTo(if (on) 1f else 0f, spring(0.6f, 500f)) }
  Row(Modifier.fillMaxWidth().then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
    .onFocusChanged { f = it.isFocused }.dpadClick(onClick).focusable()
    .clip(RoundedCornerShape(16.dp))
    .drawBehind { drawRect(Color.White, alpha = 0.08f + 0.86f * k.value) }
    .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
    UniformIcon(app, 38.dp)
    Spacer(Modifier.width(12.dp))
    val ink = lerpColor(Ink, Color(0xFF101115), k.value)
    Column(Modifier.weight(1f)) {
      T(app.label, 15.sp, weight = FontWeight.SemiBold, color = ink)
      T(if (on) "In dock · #${pos + 1}" else "Not in dock", 11.5.sp, color = ink.copy(alpha = 0.7f))
    }
    Canvas(Modifier.size(24.dp)) {
      val r = size.minDimension / 2
      drawCircle(if (k.value > 0.5f) Color(0xFF101115) else Color.White, r - 1.dp.toPx(), alpha = 0.6f * (1f - c.value), style = Stroke(2.dp.toPx()))
      drawCircle(Color(0xFF4F7BFF), r * c.value)
      if (c.value > 0.3f) {
        val p = androidx.compose.ui.graphics.Path().apply { moveTo(r * 0.55f, r * 1.02f); lineTo(r * 0.88f, r * 1.35f); lineTo(r * 1.48f, r * 0.7f) }
        drawPath(p, Color.White, alpha = c.value, style = Stroke(2.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
      }
    }
  }
}
