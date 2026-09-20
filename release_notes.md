### Surveyor v1.3.0 — Cabinet Level, and levels in real decibels

#### Cabinet Level is now in the app

Architect has always had it; Surveyor did not. Offset 3 was mapped and verified during the previous release but left out of the UI because there was no hardware on hand to test it. It is in now, next to the cabinet controls, and it was tested on a physical AMPED 3: the app's own packet written to the pedal, read back, and the whole CabRig block compared byte for byte afterwards.

#### Cabinet and Room levels read in decibels

Both controls used to show a raw 0-255 number. They now show decibels, from a scale calibrated against the hardware rather than guessed: stepping the Architect sliders one notch at a time and pairing each readout with the byte sent gives `dB = raw x 24 / 127 - 12`, which reproduces all fifteen measured readouts exactly. The byte range spans -12 to +12 dB.

The Master level is deliberately left as a raw value. Its taper is non-linear and heads to -INF — stepping down from 105 gives 0.0, -0.2, -0.5, -0.8, -1.0, -1.4 dB — and a number that looks precise but is invented is worse than an honest raw one.

#### Local presets restore what the UI exposes

Loading a saved preset used to restore only the four EQ bands on the CabRig side, so cabinet level, room level and the two cut filters silently stayed where they were. All of those are restored now. Every offset involved was write-tested on the hardware.

Master level stays out on purpose, for the same reason the amplifier's Master does: loading a preset should not change how loud the rig is.

#### Also

- 20 unit tests, all passing. The level conversion is pinned to the measured readouts, so changing it has to answer to the hardware.
- `docs/PROTOCOL_VERIFICATION.md` carries the calibration table and the write test.

**Download `Surveyor-v1.3.0.apk` below.** Android 8.0 or newer, USB-OTG cable required.
