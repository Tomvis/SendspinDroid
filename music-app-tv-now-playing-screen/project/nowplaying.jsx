// Now Playing — Android TV music screen (no controls; remote-driven)
// Three cinematic layout variants, all at 1920×1080.
// Props: track, theme ("dark"|"light"), accent (hex), variant ("focus"|"split"|"bleed")

const DEFAULT_ACCENT = "#E8567A";

function fmtTime(s) {
  s = Math.max(0, Math.floor(s));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${r.toString().padStart(2, "0")}`;
}

// Shared ambient background: blurred album art + subtle vignette + grain.
// On OLED, the corners fade to pure black. When paused, the background
// drifts slowly (parallax) to prevent OLED burn-in.
function AmbientBG({ cover, theme, accent, paused }) {
  const bgBase = theme === "light" ? "#efeae4" : "#05040a";
  return (
    <div style={{ position: "absolute", inset: 0, overflow: "hidden", background: bgBase }}>
      {/* blurred cover, pushed slightly up; ambient drift while paused */}
      <div
        aria-hidden
        className={paused ? "np-bg-drift" : ""}
        style={{
          position: "absolute",
          inset: "-8%",
          backgroundImage: `url("${cover}")`,
          backgroundSize: "cover",
          backgroundPosition: "center 30%",
          filter: "blur(80px) saturate(1.3)",
          opacity: theme === "light" ? 0.55 : 0.7,
          transform: "scale(1.15)",
          transition: "filter 600ms ease, opacity 600ms ease",
        }}
      />
      {/* accent wash — cools to ~30% strength when paused */}
      <div
        aria-hidden
        style={{
          position: "absolute",
          inset: 0,
          background: `radial-gradient(60% 55% at 30% 35%, ${accent}${paused ? "14" : "33"} 0%, transparent 60%)`,
          mixBlendMode: theme === "light" ? "multiply" : "screen",
          transition: "background 500ms ease",
        }}
      />
      {/* vignette — deep black on OLED */}
      <div
        aria-hidden
        style={{
          position: "absolute",
          inset: 0,
          background:
            theme === "light"
              ? "radial-gradient(120% 90% at 50% 50%, transparent 40%, rgba(30,20,25,0.25) 100%)"
              : "radial-gradient(130% 100% at 50% 50%, transparent 30%, #000 100%)",
        }}
      />
      {/* grain */}
      <svg aria-hidden style={{ position: "absolute", inset: 0, width: "100%", height: "100%", opacity: 0.25, mixBlendMode: "overlay" }}>
        <filter id="np-grain">
          <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed="2" />
          <feColorMatrix values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 0.6 0" />
        </filter>
        <rect width="100%" height="100%" filter="url(#np-grain)" />
      </svg>
    </div>
  );
}

// Top-right ambient clock — shown on all layouts.
function Clock({ theme }) {
  const [now, setNow] = React.useState(new Date());
  React.useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 15000);
    return () => clearInterval(t);
  }, []);
  const hh = now.getHours().toString().padStart(2, "0");
  const mm = now.getMinutes().toString().padStart(2, "0");
  const dow = now.toLocaleDateString(undefined, { weekday: "long" });
  const date = now.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  const c = theme === "light" ? "rgba(30,20,25,0.78)" : "rgba(255,255,255,0.75)";
  return (
    <div style={{ color: c, textAlign: "right", fontVariantNumeric: "tabular-nums" }}>
      <div style={{ fontSize: 42, fontWeight: 300, letterSpacing: -0.5, lineHeight: 1, fontFamily: "'Inter', sans-serif" }}>
        {hh}:{mm}
      </div>
      <div style={{ fontSize: 14, letterSpacing: 2, textTransform: "uppercase", marginTop: 8, opacity: 0.75 }}>
        {dow} · {date}
      </div>
    </div>
  );
}

// Top-left source / device affordance — shows playback status via dot color
// and label. Dot goes green (playing) → amber (paused).
function SourceBadge({ theme, paused }) {
  const c = theme === "light" ? "rgba(30,20,25,0.75)" : "rgba(255,255,255,0.7)";
  const playDot = theme === "light" ? "#4a9d5a" : "#7ee07e";
  const pauseDot = "#f5a524";
  const dot = paused ? pauseDot : playDot;
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 12, color: c, fontSize: 14, letterSpacing: 2.5, textTransform: "uppercase", fontWeight: 500, transition: "color 500ms ease" }}>
      <span style={{ width: 8, height: 8, borderRadius: 4, background: dot, boxShadow: `0 0 10px ${dot}`, transition: "background 500ms ease, box-shadow 500ms ease" }} />
      {paused ? "Paused" : "Now Playing"} · Living Room
    </div>
  );
}

// Format/bitrate chips
function SpecChips({ track, theme, size = "md" }) {
  const chipBg = theme === "light" ? "rgba(30,20,25,0.07)" : "rgba(255,255,255,0.08)";
  const chipBorder = theme === "light" ? "rgba(30,20,25,0.12)" : "rgba(255,255,255,0.14)";
  const chipFg = theme === "light" ? "rgba(30,20,25,0.82)" : "rgba(255,255,255,0.88)";
  const pad = size === "lg" ? "10px 18px" : "8px 14px";
  const fs = size === "lg" ? 18 : 15;
  const style = {
    padding: pad,
    fontSize: fs,
    letterSpacing: 1.8,
    textTransform: "uppercase",
    fontWeight: 600,
    fontFamily: "'JetBrains Mono', ui-monospace, monospace",
    color: chipFg,
    background: chipBg,
    border: `1px solid ${chipBorder}`,
    borderRadius: 999,
    whiteSpace: "nowrap",
  };
  return (
    <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
      <span style={style}>{track.format}</span>
      <span style={style}>{track.bitDepth}{"\u2011"}BIT</span>
      <span style={style}>{track.sampleRate}&nbsp;KHZ</span>
      <span style={style}>{track.bitrate.replace(/\s+/g, "\u00a0")}</span>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Layout A — FOCUS: album art centered, info stacked, progress at bottom
// Classic cinematic "focus" treatment. Art dominates vertical center.
// ─────────────────────────────────────────────────────────────
function NPFocus({ track, theme, accent, paused }) {
  const fg = theme === "light" ? "#1a1318" : "#faf6f0";
  const fgDim = theme === "light" ? "rgba(26,19,24,0.62)" : "rgba(250,246,240,0.6)";
  const pct = (track.elapsed / track.duration) * 100;
  return (
    <div style={{ position: "relative", width: 1920, height: 1080, overflow: "hidden", fontFamily: "'Inter', sans-serif", color: fg }}>
      <AmbientBG cover={track.cover} theme={theme} accent={accent} paused={paused} />

      {/* top chrome — inside 5% safe zone */}
      <div style={{ position: "absolute", top: 54, left: 96, right: 96, display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <SourceBadge theme={theme} paused={paused} />
        <Clock theme={theme} />
      </div>

      {/* art + info: art left (square), info right */}
      <div style={{ position: "absolute", inset: "140px 96px 140px 96px", display: "flex", gap: 88, alignItems: "center" }}>
        {/* art — lifts + softens when paused */}
        <div style={{ flexShrink: 0, position: "relative", transform: paused ? "scale(0.98)" : "scale(1)", transition: "transform 500ms cubic-bezier(.2,.7,.3,1)" }}>
          <div
            style={{
              width: 620,
              height: 620,
              borderRadius: 8,
              backgroundImage: `url("${track.cover}")`,
              backgroundSize: "cover",
              backgroundPosition: "center",
              filter: paused ? "brightness(0.92) saturate(0.95)" : "none",
              boxShadow: paused
                ? `0 40px 90px rgba(0,0,0,0.45), 0 0 0 1px ${theme === "light" ? "rgba(30,20,25,0.08)" : "rgba(255,255,255,0.06)"}`
                : `0 60px 120px rgba(0,0,0,0.55), 0 0 0 1px ${theme === "light" ? "rgba(30,20,25,0.08)" : "rgba(255,255,255,0.06)"}`,
              transition: "filter 500ms ease, box-shadow 500ms ease",
            }}
          />
          {/* glow — fades out when paused */}
          <div
            aria-hidden
            style={{
              position: "absolute",
              inset: -40,
              borderRadius: 20,
              background: `radial-gradient(50% 50% at 50% 55%, ${accent}${paused ? "22" : "55"} 0%, transparent 70%)`,
              filter: "blur(30px)",
              zIndex: -1,
              transition: "background 500ms ease",
            }}
          />
        </div>

        {/* info */}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 16, letterSpacing: 4, textTransform: "uppercase", color: fgDim, fontWeight: 600, marginBottom: 28 }}>
            Track 03 · From the Album
          </div>
          <div
            style={{
              fontSize: 88,
              lineHeight: 0.95,
              fontWeight: 500,
              letterSpacing: -2.5,
              fontFamily: "'Fraunces', 'Playfair Display', Georgia, serif",
              marginBottom: 32,
              textWrap: "balance",
            }}
          >
            {track.title}
          </div>
          <div style={{ fontSize: 32, fontWeight: 500, letterSpacing: -0.4, marginBottom: 10 }}>
            {track.artist}
          </div>
          <div style={{ fontSize: 24, color: fgDim, fontWeight: 400, letterSpacing: -0.2, marginBottom: 56 }}>
            {track.album} · {track.year}
          </div>

          <SpecChips track={track} theme={theme} size="lg" />
        </div>
      </div>

      {/* progress pinned to bottom */}
      <ProgressRail track={track} pct={pct} theme={theme} accent={accent} paused={paused} />
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Layout B — EDITORIAL: large art left, typographic info right w/ up-next
// Magazine-style spread with asymmetric composition.
// ─────────────────────────────────────────────────────────────
function NPEditorial({ track, theme, accent }) {
  const fg = theme === "light" ? "#1a1318" : "#faf6f0";
  const fgDim = theme === "light" ? "rgba(26,19,24,0.62)" : "rgba(250,246,240,0.6)";
  const hair = theme === "light" ? "rgba(26,19,24,0.18)" : "rgba(250,246,240,0.18)";
  const pct = (track.elapsed / track.duration) * 100;
  return (
    <div style={{ position: "relative", width: 1920, height: 1080, overflow: "hidden", fontFamily: "'Inter', sans-serif", color: fg }}>
      <AmbientBG cover={track.cover} theme={theme} accent={accent} />

      {/* top chrome */}
      <div style={{ position: "absolute", top: 54, left: 96, right: 96, display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <SourceBadge theme={theme} />
        <Clock theme={theme} />
      </div>

      {/* art, flush to left edge of safe zone, full-bleed vertically */}
      <div
        style={{
          position: "absolute",
          top: 150,
          bottom: 150,
          left: 96,
          aspectRatio: "1 / 1",
          borderRadius: 4,
          backgroundImage: `url("${track.cover}")`,
          backgroundSize: "cover",
          backgroundPosition: "center",
          boxShadow: "0 40px 100px rgba(0,0,0,0.5)",
        }}
      />

      {/* info column */}
      <div style={{ position: "absolute", top: 150, bottom: 150, left: 960, right: 96, display: "flex", flexDirection: "column" }}>
        {/* slug */}
        <div style={{ display: "flex", alignItems: "center", gap: 16, color: fgDim, fontSize: 14, letterSpacing: 4, textTransform: "uppercase", fontWeight: 600, marginBottom: 40 }}>
          <span style={{ width: 60, height: 1, background: hair }} />
          <span>Technical Death Metal · 2009</span>
        </div>

        <div
          style={{
            fontSize: 120,
            lineHeight: 0.9,
            fontWeight: 400,
            letterSpacing: -3,
            fontFamily: "'Fraunces', 'Playfair Display', Georgia, serif",
            fontStyle: "italic",
            textWrap: "balance",
          }}
        >
          {track.title}
        </div>

        <div style={{ marginTop: 32, display: "flex", alignItems: "baseline", gap: 20, flexWrap: "wrap" }}>
          <div style={{ fontSize: 36, fontWeight: 600, letterSpacing: -0.5 }}>{track.artist}</div>
          <div style={{ fontSize: 22, color: fgDim }}>— {track.album}</div>
        </div>

        <div style={{ flex: 1 }} />

        {/* metadata grid */}
        <div style={{ display: "grid", gridTemplateColumns: "repeat(4, auto)", gap: "6px 48px", marginBottom: 32 }}>
          <MetaCell label="Format" value={track.format} theme={theme} />
          <MetaCell label="Bit Depth" value={`${track.bitDepth}-bit`} theme={theme} />
          <MetaCell label="Sample Rate" value={`${track.sampleRate} kHz`} theme={theme} />
          <MetaCell label="Bitrate" value={track.bitrate} theme={theme} />
        </div>

        {/* up next */}
        <div style={{ borderTop: `1px solid ${hair}`, paddingTop: 20, display: "flex", alignItems: "center", gap: 20 }}>
          <div style={{ fontSize: 13, letterSpacing: 3, textTransform: "uppercase", color: fgDim, fontWeight: 600 }}>Up Next</div>
          <div style={{ fontSize: 20, fontWeight: 500 }}>{track.upNext}</div>
          <div style={{ fontSize: 16, color: fgDim }}>· {track.upNextDur}</div>
        </div>
      </div>

      <ProgressRail track={track} pct={pct} theme={theme} accent={accent} />
    </div>
  );
}

function MetaCell({ label, value, theme }) {
  const fg = theme === "light" ? "#1a1318" : "#faf6f0";
  const fgDim = theme === "light" ? "rgba(26,19,24,0.55)" : "rgba(250,246,240,0.55)";
  return (
    <>
      <div style={{ fontSize: 12, letterSpacing: 2.5, textTransform: "uppercase", color: fgDim, fontWeight: 600, alignSelf: "end" }}>{label}</div>
      <div style={{ fontSize: 22, fontWeight: 600, color: fg, fontFamily: "'JetBrains Mono', ui-monospace, monospace", letterSpacing: -0.3 }}>{value}</div>
    </>
  );
}

// ─────────────────────────────────────────────────────────────
// Layout C — IMMERSIVE: full-bleed huge art with info overlaid
// ─────────────────────────────────────────────────────────────
function NPImmersive({ track, theme, accent }) {
  const fg = "#faf6f0";
  const fgDim = "rgba(250,246,240,0.72)";
  const pct = (track.elapsed / track.duration) * 100;
  return (
    <div style={{ position: "relative", width: 1920, height: 1080, overflow: "hidden", fontFamily: "'Inter', sans-serif", color: fg, background: "#000" }}>
      {/* giant cover, letterboxed right-shift */}
      <div
        aria-hidden
        style={{
          position: "absolute",
          top: -100,
          right: -120,
          width: 1280,
          height: 1280,
          backgroundImage: `url("${track.cover}")`,
          backgroundSize: "cover",
          backgroundPosition: "center",
        }}
      />
      {/* left-side gradient scrim for text legibility */}
      <div
        aria-hidden
        style={{
          position: "absolute",
          inset: 0,
          background: "linear-gradient(90deg, rgba(5,4,10,0.95) 0%, rgba(5,4,10,0.85) 32%, rgba(5,4,10,0.35) 55%, transparent 78%)",
        }}
      />
      {/* bottom scrim */}
      <div
        aria-hidden
        style={{
          position: "absolute",
          inset: 0,
          background: "linear-gradient(180deg, transparent 55%, rgba(5,4,10,0.9) 100%)",
        }}
      />
      {/* accent bleed */}
      <div
        aria-hidden
        style={{
          position: "absolute",
          inset: 0,
          background: `radial-gradient(40% 50% at 20% 80%, ${accent}2e 0%, transparent 60%)`,
        }}
      />

      {/* top chrome */}
      <div style={{ position: "absolute", top: 54, left: 96, right: 96, display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
        <SourceBadge theme="dark" />
        <Clock theme="dark" />
      </div>

      {/* composition: info anchored bottom-left */}
      <div style={{ position: "absolute", left: 96, bottom: 150, width: 1020 }}>
        <div style={{ fontSize: 15, letterSpacing: 4, textTransform: "uppercase", color: fgDim, fontWeight: 600, marginBottom: 24, display: "flex", alignItems: "center", gap: 14 }}>
          <span style={{ width: 40, height: 2, background: accent }} />
          {track.album}
        </div>
        <div
          style={{
            fontSize: 112,
            lineHeight: 0.92,
            fontWeight: 500,
            letterSpacing: -3,
            fontFamily: "'Fraunces', 'Playfair Display', Georgia, serif",
            marginBottom: 36,
            textWrap: "balance",
          }}
        >
          {track.title}
        </div>
        <div style={{ fontSize: 38, fontWeight: 500, letterSpacing: -0.5, marginBottom: 36 }}>
          {track.artist}
        </div>

        <SpecChips track={track} theme="dark" size="lg" />
      </div>

      <ProgressRail track={track} pct={pct} theme="dark" accent={accent} />
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Shared progress rail (bottom of screen)
// ─────────────────────────────────────────────────────────────
function ProgressRail({ track, pct, theme, accent, paused }) {
  const fg = theme === "light" ? "#1a1318" : "#faf6f0";
  const fgDim = theme === "light" ? "rgba(26,19,24,0.55)" : "rgba(250,246,240,0.58)";
  const barBg = theme === "light" ? "rgba(26,19,24,0.12)" : "rgba(250,246,240,0.14)";
  // Paused: neutralize the bar gradient and replace the glowing head with
  // an outlined ring + pause-glyph — status indicator, not a control.
  const elapsedColor = paused ? fgDim : fg;
  const headRing = theme === "light" ? "rgba(26,19,24,0.55)" : "rgba(250,246,240,0.7)";
  return (
    <div style={{ position: "absolute", left: 96, right: 96, bottom: 54, color: fg, fontFamily: "'JetBrains Mono', ui-monospace, monospace" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", marginBottom: 14, fontSize: 20, fontVariantNumeric: "tabular-nums", letterSpacing: 0.5 }}>
        <span style={{ display: "flex", alignItems: "center", gap: 12, fontWeight: 600, color: elapsedColor, transition: "color 400ms ease" }}>
          {/* status glyph — swaps play ↔ pause */}
          <svg width="14" height="16" viewBox="0 0 14 16" aria-hidden style={{ opacity: paused ? 1 : 0.85, transition: "opacity 300ms ease" }}>
            {paused ? (
              <g fill="currentColor">
                <rect x="2" y="2" width="3.2" height="12" rx="0.4" />
                <rect x="8.8" y="2" width="3.2" height="12" rx="0.4" />
              </g>
            ) : (
              <path fill="currentColor" d="M2 1.5 L12.5 8 L2 14.5 Z" />
            )}
          </svg>
          {fmtTime(track.elapsed)}
        </span>
        <span style={{ color: fgDim, fontWeight: 500 }}>−{fmtTime(track.duration - track.elapsed)}</span>
      </div>
      <div style={{ position: "relative", height: 4, borderRadius: 2, background: barBg, overflow: "hidden" }}>
        <div
          style={{
            position: "absolute",
            inset: 0,
            width: `${pct}%`,
            background: paused
              ? `linear-gradient(90deg, ${fgDim}, ${fgDim})`
              : `linear-gradient(90deg, ${accent}, #fff)`,
            boxShadow: paused ? "none" : `0 0 16px ${accent}88`,
            borderRadius: 2,
            transition: "background 500ms ease, box-shadow 500ms ease",
          }}
        />
        {/* head */}
        <div
          style={{
            position: "absolute",
            top: "50%",
            left: `${pct}%`,
            transform: "translate(-50%, -50%)",
            width: 14,
            height: 14,
            borderRadius: 7,
            background: paused ? "transparent" : "#fff",
            border: paused ? `1.5px solid ${headRing}` : "none",
            boxShadow: paused ? "none" : `0 0 0 4px ${accent}44, 0 0 20px ${accent}`,
            transition: "background 400ms ease, box-shadow 400ms ease, border 400ms ease",
          }}
        />
      </div>
      <div style={{ display: "flex", justifyContent: "space-between", marginTop: 10, fontSize: 13, letterSpacing: 2, textTransform: "uppercase", color: fgDim, fontWeight: 600 }}>
        <span>Total {fmtTime(track.duration)}</span>
        <span>{track.trackNum} of {track.trackTotal}</span>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// IDLE — "Nothing playing" ambient screen.
// Conceptual direction: a slow-morphing generative gradient mesh
// with an oversized typographic clock as the hero, anchored by the
// SendspinDroid wordmark. Feels intentional — not a dead screen.
// ─────────────────────────────────────────────────────────────
function NPIdle({ theme, accent }) {
  const [now, setNow] = React.useState(new Date());
  React.useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 15000);
    return () => clearInterval(t);
  }, []);

  const fg = theme === "light" ? "#1a1318" : "#faf6f0";
  const fgDim = theme === "light" ? "rgba(26,19,24,0.55)" : "rgba(250,246,240,0.6)";
  const fgFaint = theme === "light" ? "rgba(26,19,24,0.35)" : "rgba(250,246,240,0.35)";
  const base = theme === "light" ? "#f2ece3" : "#07060d";

  const hh = now.getHours().toString().padStart(2, "0");
  const mm = now.getMinutes().toString().padStart(2, "0");
  const dow = now.toLocaleDateString(undefined, { weekday: "long" });
  const date = now.toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" });

  // Derive two complementary tints from accent for the mesh
  return (
    <div style={{ position: "relative", width: 1920, height: 1080, overflow: "hidden", fontFamily: "'Inter', sans-serif", color: fg, background: base }}>
      {/* Generative gradient mesh — four slow-moving radial blobs */}
      <div aria-hidden style={{ position: "absolute", inset: 0, overflow: "hidden" }}>
        <div className="np-blob np-blob-1" style={{ background: `radial-gradient(closest-side, ${accent}55, transparent 70%)` }} />
        <div className="np-blob np-blob-2" style={{ background: `radial-gradient(closest-side, ${accent}33, transparent 70%)` }} />
        <div className="np-blob np-blob-3" style={{ background: `radial-gradient(closest-side, ${theme === "light" ? "#2a1f3d66" : "#6a4d9a66"}, transparent 70%)` }} />
        <div className="np-blob np-blob-4" style={{ background: `radial-gradient(closest-side, ${theme === "light" ? "#d98c5866" : "#d98c58aa"}, transparent 70%)` }} />
      </div>
      {/* Vignette — OLED-friendly black corners */}
      <div aria-hidden style={{ position: "absolute", inset: 0, background: theme === "light"
        ? "radial-gradient(140% 100% at 50% 50%, transparent 40%, rgba(30,20,25,0.2) 100%)"
        : "radial-gradient(140% 100% at 50% 50%, transparent 30%, #000 100%)" }} />
      {/* Grain */}
      <svg aria-hidden style={{ position: "absolute", inset: 0, width: "100%", height: "100%", opacity: 0.2, mixBlendMode: "overlay" }}>
        <filter id="np-idle-grain">
          <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed="7" />
          <feColorMatrix values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 0.6 0" />
        </filter>
        <rect width="100%" height="100%" filter="url(#np-idle-grain)" />
      </svg>

      {/* Top-left status badge */}
      <div style={{ position: "absolute", top: 54, left: 96, display: "flex", alignItems: "center", gap: 12, color: fgDim, fontSize: 14, letterSpacing: 2.5, textTransform: "uppercase", fontWeight: 500 }}>
        <span style={{ width: 8, height: 8, borderRadius: 4, background: fgFaint }} />
        Standby · Living Room
      </div>

      {/* Wordmark — top-right */}
      <div style={{ position: "absolute", top: 54, right: 96, textAlign: "right" }}>
        <div style={{ fontFamily: "'Fraunces', Georgia, serif", fontStyle: "italic", fontWeight: 400, fontSize: 32, letterSpacing: -0.5, color: fg, lineHeight: 1 }}>
          Sendspin<span style={{ color: accent, fontStyle: "normal", fontWeight: 500 }}>Droid</span>
        </div>
        <div style={{ fontSize: 12, letterSpacing: 3, textTransform: "uppercase", color: fgFaint, marginTop: 8, fontWeight: 600 }}>
          Audio · Ready
        </div>
      </div>

      {/* Hero clock — centered, oversized.
          Colon: two hand-sized dots positioned at optical centers.
          For 420px Fraunces Light, cap-height ≈ 294px. The colon's
          upper dot sits at ~62% of cap-height from baseline, lower
          at ~22%. Using a 420px-tall flex column with absolute dots
          keeps the baseline aligned with the digits and the dots
          sized proportionally to the glyph weight. */}
      <div style={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center" }}>
        <div style={{
          fontFamily: "'Fraunces', Georgia, serif",
          fontSize: 420,
          fontWeight: 300,
          lineHeight: 1,
          fontVariantNumeric: "tabular-nums",
          fontFeatureSettings: '"lnum" 1, "tnum" 1',
          color: fg,
          display: "flex",
          alignItems: "baseline",
          gap: 130,
          letterSpacing: -8,
        }}>
          <span>{hh}</span>
          {/* Colon — optically-placed dots, accent colored, with a soft halo */}
          <span aria-hidden style={{
            position: "relative",
            display: "inline-block",
            width: 48,
            height: "1em",
            verticalAlign: "baseline",
            alignSelf: "stretch",
            color: accent,
            flex: "0 0 auto",
          }}>
            <span style={{
              position: "absolute",
              left: "50%",
              top: "30%",
              width: 46, height: 46, borderRadius: 23,
              transform: "translate(-50%, -50%)",
              background: "currentColor",
              boxShadow: `0 0 56px ${accent}99, 0 0 14px ${accent}66`,
              animation: "np-colon-pulse 2.4s ease-in-out infinite",
            }} />
            <span style={{
              position: "absolute",
              left: "50%",
              top: "58%",
              width: 46, height: 46, borderRadius: 23,
              transform: "translate(-50%, -50%)",
              background: "currentColor",
              boxShadow: `0 0 56px ${accent}99, 0 0 14px ${accent}66`,
              animation: "np-colon-pulse 2.4s ease-in-out infinite",
              animationDelay: "1.2s",
            }} />
          </span>
          <span>{mm}</span>
        </div>
        <div style={{ marginTop: 32, fontSize: 22, letterSpacing: 8, textTransform: "uppercase", color: fgDim, fontWeight: 500 }}>
          {dow} · {date}
        </div>
      </div>

      {/* Bottom flourish — horizon line + tagline */}
      <div style={{ position: "absolute", left: 96, right: 96, bottom: 80, display: "flex", alignItems: "center", gap: 24 }}>
        <div style={{ flex: 1, height: 1, background: `linear-gradient(90deg, transparent, ${fgFaint}, transparent)` }} />
        <div style={{ fontFamily: "'Fraunces', Georgia, serif", fontStyle: "italic", fontSize: 18, color: fgDim, letterSpacing: 0.3, whiteSpace: "nowrap" }}>
          silence, in its own key
        </div>
        <div style={{ flex: 1, height: 1, background: `linear-gradient(90deg, transparent, ${fgFaint}, transparent)` }} />
      </div>
    </div>
  );
}

// Dispatcher
function NowPlaying({ variant = "focus", track, theme = "dark", accent = DEFAULT_ACCENT, paused = false, idle = false }) {
  if (idle) return <NPIdle theme={theme} accent={accent} />;
  if (variant === "editorial") return <NPEditorial track={track} theme={theme} accent={accent} paused={paused} />;
  if (variant === "immersive") return <NPImmersive track={track} theme={theme} accent={accent} paused={paused} />;
  return <NPFocus track={track} theme={theme} accent={accent} paused={paused} />;
}

// Slow ambient drift keyframes (for paused bg anti-burn-in)
if (typeof document !== "undefined" && !document.getElementById("np-keyframes")) {
  const s = document.createElement("style");
  s.id = "np-keyframes";
  s.textContent = `
    @keyframes np-drift {
      0%   { transform: scale(1.15) translate(0, 0); }
      50%  { transform: scale(1.17) translate(-1.5%, -1%); }
      100% { transform: scale(1.15) translate(0, 0); }
    }
    .np-bg-drift { animation: np-drift 40s ease-in-out infinite; }

    /* Idle mesh blobs — each drifts on its own slow period for organic mesh */
    .np-blob { position: absolute; border-radius: 50%; filter: blur(80px); will-change: transform; }
    .np-blob-1 { width: 1200px; height: 1200px; top: -200px; left: -300px;
      animation: np-mesh-1 38s ease-in-out infinite; }
    .np-blob-2 { width: 1000px; height: 1000px; top: 300px; right: -250px;
      animation: np-mesh-2 46s ease-in-out infinite; }
    .np-blob-3 { width: 900px; height: 900px; bottom: -250px; left: 40%;
      animation: np-mesh-3 52s ease-in-out infinite; }
    .np-blob-4 { width: 700px; height: 700px; top: 20%; left: 45%;
      animation: np-mesh-4 60s ease-in-out infinite; }
    @keyframes np-mesh-1 { 0%,100%{transform:translate(0,0) scale(1)} 50%{transform:translate(8%,6%) scale(1.1)} }
    @keyframes np-mesh-2 { 0%,100%{transform:translate(0,0) scale(1)} 50%{transform:translate(-6%,-4%) scale(0.92)} }
    @keyframes np-mesh-3 { 0%,100%{transform:translate(0,0) scale(1.05)} 50%{transform:translate(-4%,5%) scale(1)} }
    @keyframes np-mesh-4 { 0%,100%{transform:translate(0,0) scale(0.9)} 50%{transform:translate(6%,-5%) scale(1.08)} }

    /* Colon breathing — subtle accent pulse, slow and ambient */
    @keyframes np-colon-pulse {
      0%, 100% { opacity: 0.95; transform: translate(-50%, -50%) scale(1); }
      50%      { opacity: 1;    transform: translate(-50%, -50%) scale(1.06); }
    }
  `;
  document.head.appendChild(s);
}

Object.assign(window, { NowPlaying, NPFocus, NPEditorial, NPImmersive, NPIdle, DEFAULT_ACCENT });
