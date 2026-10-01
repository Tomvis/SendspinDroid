package com.sendspindroid.ui.main.components.nowplaying

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for the TV Now Playing surface (the idle screen, the focus
 * screen, and the chrome they share).
 *
 * ## The 1920x1080 design canvas
 *
 * `NowPlayingTv` wraps this entire surface in
 *
 * ```
 * CompositionLocalProvider(
 *     LocalDensity provides Density(density = designScale, fontScale = platformFontScale)
 * )
 * ```
 *
 * where `designScale = min(windowWidth / 1920, windowHeight / 1080)`.
 *
 * The consequence is the single most important fact about every number in this
 * file: **inside this surface `1.dp` and `1.sp` are one pixel on a 1920x1080
 * design canvas, not one Android density-independent pixel.** They stop being
 * device-relative units and become coordinates in a fixed layout the design was
 * drawn at.
 *
 * That is why values here look nothing like ordinary Android dimensions.
 * `Type.HeroClock = 240.sp` is not an accessibility disaster and
 * `Dimen.AlbumArt = 620.dp` is not a typo -- they are "240px tall" and "620px
 * square" on a 1920x1080 sheet, i.e. 22% of the canvas height and 32% of its
 * width. The mechanism makes those proportions exact and resolution-independent:
 * on a 1080p window `designScale` resolves to 1.0 and the numbers are literal
 * pixels; on a 4K window it resolves to 2.0 and every element doubles in
 * physical pixels while occupying the identical fraction of the screen. Nothing
 * needs a breakpoint, and the layout can never reflow into a shape the design
 * was never drawn for.
 *
 * `fontScale` is deliberately *not* pinned -- it is read back off the platform
 * density and passed through, so the user's system font-size setting still
 * scales text on top of the canvas mapping.
 *
 * ## Why this surface does not use ui/adaptive/AdaptiveDefaults.kt
 *
 * `AdaptiveDefaults` answers a different question. It is a per-form-factor
 * lookup (`titleTextSize(FormFactor.TV) = 36.sp`, `albumArtMaxSize(FormFactor.TV)
 * = 500.dp`) whose job is to make one *flowing, component-composed* layout
 * legible on a phone, a tablet, a head unit and a TV. Its values are true
 * density-independent pixels chosen to be safe defaults at each size, and they
 * are consumed by screens that reflow around them.
 *
 * This surface is the opposite: a single fixed composition, art-directed at one
 * aspect ratio, that must reproduce a specific 1920x1080 layout rather than adapt
 * to an unknown one. Feeding it `AdaptiveDefaults` values would be wrong twice
 * over. First, they would be reinterpreted as design-canvas pixels and come out
 * *under*-scaled: a value that means 36 real sp is about 72 physical px on the
 * Shield's 1080p window, but read as a canvas value it renders 36px -- a title
 * 3.3% of the canvas height where the design wants 88px. (It is under-scaled,
 * not scaled twice: the canvas mapping replaces the platform density, it does
 * not compound with it.) Second, their whole point, adapting across form
 * factors, is dead weight on a screen that only ever renders on Android TV. The
 * two systems are not in competition; they answer "what size is safe here?" and
 * "where exactly does this go?" respectively.
 *
 * The one place they do meet is the outer margin, and it runs straight into the
 * caveat above. The design specifies 54dp top/bottom and 96dp side margins. 48dp
 * of that comes from `overscanSafe()`, which is exactly
 * `AdaptiveDefaults.screenPadding(FormFactor.TV)` -- and because it is applied
 * inside this surface, its 48.dp is reinterpreted like every other dp here:
 * 48 canvas px, roughly 24 real dp on the Shield's 1080p window, not the 48 real
 * dp it means elsewhere. That is accepted knowingly rather than overlooked. The
 * token is used for its *intent* -- the fork's overscan policy, honoured
 * explicitly and greppably -- while the number that lands is the design's, with
 * [Space.ChromeInset] / [Space.SideInset] topping it up. And the design's own
 * margins already satisfy the policy on their own terms: 96/1920 and 54/1080 are
 * 5% of the canvas on both axes, the standard TV overscan allowance. Net visual
 * margin is the design's, and it is still overscan-safe.
 *
 * ## Using these tokens
 *
 * Tokens capture the design system: the type scale, the spacing rhythm, named
 * component dimensions and corner radii. They deliberately do **not** capture
 * every literal on the surface. Art-directed one-offs with no reusable meaning
 * -- the idle screen's four drifting ambient blobs, the vector coordinates of the
 * play/pause glyph, hairline strokes, optical nudges -- stay inline where they
 * are read, because naming them would imply a reuse contract that does not exist.
 *
 * Anything added here should be a decision the design makes repeatedly or a
 * dimension another component has to agree with. Anything used once, in one
 * place, whose value only means something in the six lines around it, should not.
 */
internal object NowPlayingTvTokens {

    /**
     * The canvas the design was authored at. `NowPlayingTv` divides the real
     * window size by these to derive the density it installs, which is what
     * makes every other token in this file a design-canvas pixel value.
     */
    object DesignCanvas {
        const val WidthPx: Float = 1920f
        const val HeightPx: Float = 1080f
    }

    /**
     * Type scale, in design-canvas pixels.
     *
     * Nine sizes spanning 240px down to 16px. The two ends do different jobs.
     * The large end ([HeroClock], [Title], [TopClock]) carries *negative*
     * tracking, because display type at this scale reads loose; all three
     * are light Rubik (the one face on this surface, HW-48). The small end ([Label],
     * [Caption]) is uppercased Rubik (tabular figures for numbers) with heavy *positive* tracking,
     * which is what buys legibility for a short label at 10 feet where simply
     * making it bigger would unbalance the composition. The idle tagline is the
     * one thing at the small end that opts out: it borrows [Label] as a size
     * only and sets it lowercase in italic Rubik with its own inline nudge.
     *
     * `Track*` values are letter spacing for the size of the same name. Sizes
     * without an explicit leading token set `lineHeight` to their own size
     * (solid leading) -- only [Title] wraps to two lines and needs
     * [TitleLeading] to pull them tighter than solid.
     */
    object Type {
        /** Idle screen hour/minute. Also its own lineHeight. */
        val HeroClock: TextUnit = 240.sp

        /** Focus screen track title. Wraps to at most two lines. */
        val Title: TextUnit = 88.sp

        /** Leading for [Title] -- deliberately tighter than solid on the wrap. */
        val TitleLeading: TextUnit = 84.sp

        /** Top-bar clock. Also its own lineHeight. */
        val TopClock: TextUnit = 42.sp

        /** Artist line, and the idle wordmark. Wordmark uses it as lineHeight too. */
        val Heading: TextUnit = 32.sp

        /** Album / year line under the artist. */
        val Subheading: TextUnit = 24.sp

        /** Rail timecodes and the idle date strip. */
        val Body: TextUnit = 20.sp

        /**
         * The tracked-uppercase workhorse: date strip, track slug, rail meta,
         * spec chips. The idle tagline shares the size but not the treatment --
         * it is lowercase italic Rubik with an inline optical nudge, so none
         * of the `Track*` values below apply to it.
         */
        val Label: TextUnit = 18.sp

        /** Status badge label and the idle wordmark sublabel. */
        val Caption: TextUnit = 16.sp

        // Optical tightening -- large display type.
        val TrackHeroClock: TextUnit = (-8).sp
        val TrackTitle: TextUnit = (-2.5).sp

        /** Shared by the top-bar clock and the idle wordmark. */
        val TrackDisplay: TextUnit = (-0.5).sp
        val TrackHeading: TextUnit = (-0.4).sp
        val TrackSubheading: TextUnit = (-0.2).sp

        // Tracked uppercase ladder -- small labels, widest where the label is
        // shortest and has the most room to breathe.
        val TrackTimecode: TextUnit = 0.5.sp
        val TrackChip: TextUnit = 1.8.sp

        /** Top-bar date strip and both rail meta labels. */
        val TrackMeta: TextUnit = 2.sp
        val TrackBadge: TextUnit = 2.5.sp
        val TrackSublabel: TextUnit = 3.sp
        val TrackSlug: TextUnit = 4.sp

        /** The idle date strip, the widest tracking in the system. */
        val TrackHeroDate: TextUnit = 8.sp
    }

    /**
     * Spacing rhythm, in design-canvas pixels.
     *
     * The vertical rhythm of the focus screen's info column runs
     * [SlugGap] -> [SectionGap] -> [TightGap] -> [ChipsGap] as the eye moves down
     * from the smallest label to the largest, then back out to the chips.
     */
    object Space {
        /**
         * Tops up `overscanSafe()`'s 48dp to the design's 54dp top/bottom margin.
         * Applied at the focus screen's top chrome row and progress rail.
         */
        val ChromeInset: Dp = 6.dp

        /** Gap above a sublabel that hangs off the line above it. */
        val SublabelGap: Dp = 8.dp

        /** Album/year spacer, rail meta row, spec-chip flow gaps. */
        val TightGap: Dp = 10.dp

        /** Status dot to its label, and the rail's glyph to its timecode. */
        val BadgeGap: Dp = 12.dp

        /** Timecode row down to the progress bar. */
        val RailBarGap: Dp = 14.dp

        /** Idle screen's horizon divider row, down from the hero clock block. */
        val HorizonTop: Dp = 16.dp

        /** Idle tagline to the rules on either side of it. */
        val HorizonGap: Dp = 24.dp

        /** Track slug up to the title. */
        val SlugGap: Dp = 28.dp

        /** Title to artist, and the idle hero clock to its date strip. */
        val SectionGap: Dp = 32.dp

        /**
         * Horizontal margin *inside* `overscanSafe()`, taking the design's side
         * margin to 96dp. Not every 48dp on this surface is this token -- the
         * idle hero clock's colon gutter is coincidentally the same number and
         * is unrelated.
         */
        val SideInset: Dp = 48.dp

        /** Album line down to the spec chips -- the info column's widest break. */
        val ChipsGap: Dp = 56.dp

        /** Album art to the info column. */
        val ArtToInfo: Dp = 88.dp
    }

    /** Component dimensions, in design-canvas pixels. */
    object Dimen {
        /** The focus screen's album card: 620px square on a 1080px-tall canvas. */
        val AlbumArt: Dp = 620.dp

        /**
         * The accent glow behind the card. [AlbumArt] + 40px of overhang on each
         * side; must be applied with `requiredSize`, since the parent hands down
         * fixed [AlbumArt] constraints that a plain `size` would clamp to.
         */
        val AlbumGlow: Dp = 700.dp

        /** Drop-shadow elevation on the album card. */
        val AlbumElevation: Dp = 60.dp

        /** The status dot itself, shared by the focus and idle badges. */
        val StatusDot: Dp = 8.dp

        /** The halo around the focus badge's dot, which the idle badge omits. */
        val StatusDotGlow: Dp = 20.dp

        /** Thickness of the progress bar track and its fill. */
        val RailBarHeight: Dp = 4.dp

        /** Height of the box the bar sits in -- sized to give the playhead room. */
        val RailTrackHeight: Dp = 14.dp

        val RailHeadWidth: Dp = 16.dp
        val RailHeadHeight: Dp = 6.dp

        /**
         * Glow box for one dot of the idle clock's pulsing colon. A radial
         * gradient auto-fits its radius to half the box, so the glow reaches
         * [ColonDot] radius past the dot's own edge.
         */
        val ColonGlow: Dp = 72.dp

        /** The solid inner disc of one colon dot -- half of [ColonGlow]. */
        val ColonDot: Dp = 36.dp

        val ChipPaddingH: Dp = 18.dp
        val ChipPaddingV: Dp = 14.dp
    }

    /** Corner radii, in design-canvas pixels. */
    object Radius {
        /** The album card: its shadow shape, its clip, and the hairline stroked over it. */
        val AlbumArt: Dp = 8.dp

        /**
         * Fully-rounded spec chips. A large fixed radius rather than a
         * percentage so it reads identically whatever the chip's text length.
         */
        val Pill: Dp = 999.dp
    }
}
