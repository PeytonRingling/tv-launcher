# Styled homes (v4.4)

Eight home styles beside the original four (Glass dock, Cinematic, Side rail, Minimal). Pick one in
**Settings › Home layout**; the live preview runs the style's ambient motion. Every style reuses the same data:
dock apps (in dock order), then **All apps** and **Settings** as the last two items; clock (12/24 h setting);
weather (ZIP from Settings); verse of the day (API.Bible, FUMS reported exactly like the verse card; the short copyright
notice follows *Show copyright on card*).

## Shared rules (StyleKit.kt)

| Rule | How |
|---|---|
| One focus driver per style | `FocusDriver.pos` springs (damping 0.86, stiffness 260) toward the focused index. Every focus visual reads `k(i) = 1 − |pos − i|` inside `graphicsLayer`/draw lambdas, so a move animates continuously and nothing recomposes per frame. |
| D-pad first | `StyleNav` makes every move explicit (list, row, grid, or ring). DOWN past the last row opens All apps (same as the dock). OK opens the item; hold OK or MENU opens the app menu. |
| Focus restore | Each item has the requester `"<style>:<pkg>"`, so HomeContent restores the last item after an overlay closes or the app returns home. |
| Entry transition | `StyleFx.enter` 0→1 (900 ms) on first show and every time the launcher comes back to the foreground. |
| Launch transition | `StyleFx.launch` 0→1 (520 ms), then the app starts; it resets behind the app so coming home plays the entry again. |
| Ambient motion | `rememberAmbientTime`: capped at 24–30 fps. It pauses when the activity isn't resumed, an overlay is open, or ambient mode is on. Values are read only in draw/layer lambdas. |
| Band-free glows | Glows come from `SoftGlow.custom`: a pre-blurred (3× box ≈ gaussian), Bayer-dithered mask baked once per size and tinted at draw time. Text glows use the paint shadow's gaussian blur. The long neon sky gradient is baked with an ordered dither. |
| No per-frame allocation | Paths, brushes, baked textures (paper, wood, Ben-Day dots, vinyl, sun surface, planets) are built in `drawWithCache`/`Baked` once. Per frame: transforms, `drawLine/Circle/Image` with value-class arguments. |
| Fonts | Bundled OFL fonts: VT323, Press Start 2P, Bangers, Monoton (new), plus Playfair Display, DM Serif Display, Space Grotesk, Inter. Licences in `assets/licenses/OFL-fonts.txt`. All art is drawn in code; no logos or third-party artwork. |

## The styles

1. **Retro terminal** (`RETRO`). Green-phosphor CRT.
   - Look: scanlines, vignette, curved bezel mask and a glass glare, all in one static layer. A soft scan band rolls down every 7.5 s, with a faint mains flicker. The VT323 type has a phosphor glow.
   - Clock and weather: the clock types itself in after power-on, with a block cursor that rides the text and then blinks. Weather and the hourly forecast print as `> wx` output, and the verse as `> cat verse.txt`.
   - Menu: apps are a numbered PROGRAMS menu in a double-ruled box. The inverse selection bar is one green bar plus a dark copy of the rows clipped to it, so no row recomposes. A phosphor ghost trails the bar, with strength set by the spring's velocity.
   - Transitions: entry is CRT power-on (a white-hot line opens vertically). Launch is power-off (collapse to a line, then a dot) with `> run <app> ...`.
2. **Console dashboard** (`CONSOLE`). An original console-style home.
   - Hero: the focused app's art fills the backdrop, pre-blurred from a 24-px copy and bilinear-upscaled (works on every API level), and crossfades as focus moves. Sharp key art sits on the right with the big title, a kicker (JUMP BACK IN / PINNED / SYSTEM) and pill hints.
   - Top bar: profile initial, app count, weather and clock. A "for today" strip holds the verse and the hourly weather.
   - Tiles: a row of large rounded tiles. Focus scales the tile to 1.1× with a bold 3 dp white outline (3 dp gap) and a soft shadow, and a light sheen sweeps across it, driven by the same focus progress.
   - Transitions: tiles rise in a stagger on entry. On launch the tile grows into the whole screen in its colour.
3. **Magazine** (`MAGAZINE`). Editorial front page on grained paper.
   - Masthead: issue number (day of year), volume, edition time and date, double rules, and a dateline strip.
   - Columns, separated by column rules:
     - DM Serif clock with a red am/pm, the long-form date ("Thursday, the twenty-second of October"), and the verse as a pull quote. The quote has a measured drop cap: the first three lines wrap beside the cap, the rest run full width.
     - Hero photo of the day with a caption, plus a forecast sidebar with dotted leaders.
     - "In this issue" table of contents with numbered entries and leaders. Focus draws a red marker and an underline in from the left and nudges the entry right.
   - Transitions: page-turn from the spine on entry, with shading. On launch the page turns away.
4. **Neon synthwave** (`NEON`).
   - Scene:
     - Dithered sunset sky with twinkling stars.
     - Striped sun: real gaps cut in an offscreen layer, stripes drifting down.
     - Two mountain ridges with neon rims.
     - Perspective grid floor scrolling toward you (horizontal lines in depth, verticals converging), double-stroked for glow.
     - The sun reflected in the floor with scanline gaps, and a horizon haze.
   - Lighting: the Monoton clock, the date and the side signs (weather, verse) ignite with per-tube flicker on first show.
   - Signs: apps are neon signs centred on focus. Focus ignites the tube with a flicker driven by the focus progress, a band-free halo, a white core and a lift.
   - Launch: a white-hot flare in the sign's colour while the grid speeds up, then lights out.
5. **Space** (`SPACE`).
   - Backdrop: three-layer parallax starfield (twinkle, slow drift, parallax that follows the orbit's rotation) and two nebulae.
   - Sun: the clock sits on the sun, which has a slowly rotating noise surface, a corona, an anamorphic streak and lens ghosts.
   - Planets: apps are pre-rendered lit spheres on a tilted elliptical orbit. They are depth-sorted every frame without allocating: back half behind the sun, front half in front. Size and brightness come from depth. Left/right spins the ring the short way round (wrapping).
   - Motion: orbit trails follow the spin velocity. The focused planet gets an aura and a ring.
   - Transitions: warp streaks on entry and launch; on launch the planet rushes at the camera.
6. **Comic book** (`COMIC`).
   - Page: panels in black gutters. Each panel has an ink border with a white keyline, Ben-Day dot screens (baked tile, tinted live), shading and speed lines.
   - Content: the clock in outlined Bangers lettering with a "MEANWHILE, AT HOME…" caption. A drawn sun-and-cloud weather panel with a caption. The verse sits in a speech bubble, with the reference (and notice) in a caption box. Apps are panels with an inked icon and a lettered caption.
   - Focus: a POW pop (starburst, scale 1.12, tilt). Hand-jittered "boiling" ink outlines cycle about 8 times a second while focused.
   - Transitions: panels stamp in on entry. Launch shows onomatopoeia (POW!/BAM!/ZAP!…) in a starburst, then an ink wipe.
7. **Arcade** (`ARCADE`).
   - Cabinet: side-art stripes and a backlit marquee with the clock in Press Start 2P. Bulbs chase around the marquee: a pre-baked glow sprite with a lit/unlit pattern.
   - Rack: apps are cartridges with grips and notches on a two-shelf rack. Label art is built from each app's colours (stripes, gloss), its icon and its name. Focus slides the cartridge up out of its slot and tilts it, with a halo.
   - Status bar: INSERT COIN blinks, with the weather and HI date. A verse ticker scrolls along the bottom.
   - Attract mode: after 25 s with no key, INSERT COIN, a walking pixel sprite and bobbing cartridges, with a faster light chase. The first key only wakes it.
   - Transitions: PLAYER 1 READY plus cartridges dropping in with a bounce on entry. LOADING bar on launch.
8. **Record shelf** (`SHELF`).
   - Room: wood-plank wall (baked grain), lamp falloff and vignette. A window light beam carries drifting dust motes, visible only inside the beam.
   - Wall pieces: "NOW PLAYING" clock plaque, weather tag, and the verse as a framed print with a wood frame and drop shadow.
   - Records: sleeves stand on a grained ledge with brass name plates. Each sleeve has spine embossing, ring wear, gloss and a 33⅓ sticker.
   - Focus: the sleeve lifts, grows and tilts toward you, and the vinyl slides out to the right and spins at 33⅓ rpm. The disc is a baked groove-and-sheen texture with a live label in the app colour.
   - Transitions: sleeves slide onto the shelf on entry. On launch the record leaves the sleeve, grows and spins up, then the room goes dark.

## Tests and renders

`ScreensTest.h1Retro … h8Shelf` render each style mid-entry, at rest, mid focus move, focused and mid-launch to
`screens/styles/H<n>_<style>_{a_home,b_focused,c_entry_mid,d_focus_moving,e_launch_mid}.png`, and assert that the first
app is focused and that two D-pad presses move focus two items. `h7ArcadeAttract` checks attract mode and that the
waking key doesn't move focus. `stylesMenuOpensFromHoldOk` checks MENU and focus restore.

## Performance notes

- The first time a style is shown, it bakes its textures on the UI thread (≈ 10–60 ms total on a phone-class CPU: wood
  grain, paper, planets, glow masks). They are cached for the process.
- The Neon sun is two small offscreen layers redrawn at ≤ 30 fps. Space draws ≈ 200 stars and the planets per frame.
  Both are frame-capped and paused off-screen.
