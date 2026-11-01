# Icons

The sources for the app icon and the menu bar glyph. `just icon` (in `mac/`,
or `just mac icon` from the root) turns them into what Xcode builds; commit
the results with the SVGs.

| Source | Generated |
| --- | --- |
| `concepts/<name>.svg` (the one named in `scripts/icon.bb`) | `AgentMail/AppIcon.icon`, an Icon Composer document |
| `menubar.svg` | `AgentMail/Assets.xcassets/MenuBarIcon.imageset`, 18 and 36 px template PNGs |

## Concepts

Three hand-drawn concepts on a 1024 × 1024 canvas share one palette: a blue
gradient, amber, white, and deep indigo for the eyes.

- `envelope-agent.svg` (shipped): an envelope sealed by a small robot head on the tip of its flap.
- `envelope-prompt.svg`: an envelope opening at the side, so the flap line is a prompt `>`, with a block cursor.
- `two-agents.svg`: two robot heads with letters passing between them.

Each one is also a layer stack for Icon Composer:

- The element with `id="background"` gives the icon's fill: a color, or a
  `linearGradient` with two stops.
- Each top-level `<g id="...">` becomes one group with one layer, back to
  front in file order (Icon Composer allows four). Use flat colors; the
  system adds the glass, highlights and shadows, and masks the corners, so
  draw on the full square.
- Attributes on a `<g>` set what Icon Composer would: `data-translucency="0.2"`
  (`0` turns it off; Icon Composer's default is 0.5), `data-glass="false"`,
  and `opacity`.

## Switching concepts

Change `concept` at the top of `scripts/icon.bb` to another file name in
`concepts/`, without `.svg`, and run `just icon`. To try one without
changing the default: `just icon --concept two-agents`. `project.yml` needs
no change, since the document is always `AppIcon.icon`.

## Previews

`just icon --previews DIR` also writes a 512 px PNG of every concept, rendered
the way macOS shows it (mask and Liquid Glass) by the `ictool` inside Icon
Composer, plus the menu bar glyph at 2x and 8x. Open `AgentMail/AppIcon.icon`
in Icon Composer (it comes with Xcode) to check the dark, clear and tinted
appearances; `just icon` overwrites any changes made there, so carry them
back into the SVG.
