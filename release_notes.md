### Surveyor v1.4.0 — built to be read on stage

A phone sitting by your feet in a dim room is read in a glance, or it is useless. This release is mostly about that, plus two corrections that came out of testing it.

#### The controls

Values are now set in large tabular figures, so they do not shift around while you drag and they stay readable at arm's length. The faders have a taller track and a fader cap instead of a dot.

**They only respond to a drag.** A Material slider jumps to wherever you tap it, which on a phone lying next to a pedalboard means a brush against the screen can throw your gain to maximum. Dragging is deliberate; tapping now does nothing.

The gain fader runs from yellow to red as it climbs, the way a valve looks when it is being worked, and the glow grows with it.

#### The cabinet drawing was always a 4x12

It drew whatever cabinet was loaded, not the one you were browsing, so picking a 2x12 in the list left a 4x12 on screen. It now follows the selection, reads the cone count and size from the cabinet name, marks a combo, and says NOT LOADED while your choice differs from what the pedal is running. A 10 inch cone is drawn smaller than a 12.

#### Power is in the app

The 100 W / 20 W / 1 W selector was missing. Its values were read off Architect's own control one step at a time: 1 is 100 W, 3 is 20 W, 2 is 1 W.

#### Italian text in the English app

The status line, errors, save destinations and several labels were hardcoded in Italian and showed up that way with the app set to English. They are translated.

#### Elsewhere

- The launch screen is charcoal instead of white, and the Surveyor mark fades in over a warming filament rather than appearing cold.
- Navigation icons are drawn in the same vocabulary as the controls: a knob, a cabinet, a stack, faders.
- The three channel buttons sit in one row, with the active one warmed and lit.
- Section headings match the parameter labels instead of using a different style.
- README screenshots are current and in English.

20 unit tests, all passing.

**Download `Surveyor-v1.4.0.apk` below.** Android 8.0 or newer, USB-OTG cable required.
