# Parameter map verification against the hardware — 20 September 2026

Every offset below was checked on a physical AMPED 3 (firmware 1.03) with Architect 2.1.3, not inferred from file formats. Two methods were used, and where they overlap they agree.

## Method 1: correlation, with nothing written

Architect displays each amplifier knob as a number from 0 to 10. Reading those ten numbers through the accessibility API and then reading the pedal's own 52-byte live block gives two independent views of the same state, so the map falls out of matching them. Ten distinct values against fifty-two bytes leaves no room for coincidence: each knob was compatible with exactly one offset, every residual under half a step.

| Knob | Architect | Expected byte | Offset | Byte read | Error |
|---|---|---|---|---|---|
| Gain | 8.2 | 104.1 | 0 | 104 | −0.14 |
| Preamp Volume | 4.7 | 59.7 | 1 | 60 | +0.31 |
| Boost | 4.6 | 58.4 | 2 | 58 | −0.42 |
| Reverb | 3.4 | 43.2 | 3 | 43 | −0.18 |
| Bass | 6.4 | 81.3 | 4 | 81 | −0.28 |
| Middle | 4.0 | 50.8 | 5 | 51 | +0.20 |
| Treble | 6.0 | 76.2 | 6 | 76 | −0.20 |
| ISF | 4.3 | 54.6 | 7 | 55 | +0.39 |
| Presence | 5.1 | 64.8 | 8 | 65 | +0.23 |
| Master | 8.7 | 110.5 | 9 | 110 | −0.49 |

This settles an old disagreement: an earlier project note had bass at offset 2. It does not. The order matches the tag order of Architect's own `.amped` files, which is what the app had assumed.

The switch offsets were confirmed in the same read, against what Architect showed at that moment: 21 power = 1 for 100 W (previously recorded as only probable), 22 response = 2 for EL84, 24 boost position = 0 for Post, 25 reverb character = 1 for Dark, 26 clean voice = 0 for Bright, 27 crunch voice = 0 for Super Crunch, 28 OD voice = 1 for OD1.

## Method 2: one control at a time, with the traffic captured

For CabRig the values on screen are not all numbers, so each control was nudged one step and put back while a DYLD interpose library logged every report Architect sent. The offset that moved is the offset that control owns.

| Control | Offset | Note |
|---|---|---|
| Cabinet Level | 3 | ±12 dB over 0–127; not exposed in the app yet |
| Room Level | 57 | ±12 dB over 0–127 |
| Master Level | 65 | |
| Low-Cut frequency | **67** | |
| EQ Low / Low Mids / High Mids / High | 70 / 73 / 77 / 81 | (raw − 127.5) / 12.75 dB reproduces all four readouts |
| High-Cut frequency | **83** | |
| Reverb on/off | 33 (amp block) | 0 or 1 |
| Boost on/off | bit 6 of offset 32 (amp block) | write verified: 32 ↔ 96 |

**The two cut frequencies were swapped in the app and in the README.** The slider labelled Low-Cut wrote offset 83. The factory EQ presets shipped in `AmpedProtocol.kt` corroborate the correction independently: "Treble Boost" turns both filters off and leaves 67 at its minimum and 83 at its maximum, while "Cocked Wah" keeps only midrange with 67 at 255 and 83 at 17. `CutOffsetsTest` now locks this down.

## Level calibration — 21 September 2026

Stepping the Architect sliders one notch at a time and pairing each readout with the byte sent gives the Cabinet and Room level scale outright:

| Byte | 71 | 72 | 73 | 74 | 75 | 76 | 77 | 78 | 79 | 80 | 81 | 82 | 83 | 84 | 85 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Architect | 1.4 | 1.6 | 1.8 | 2.0 | 2.2 | 2.4 | 2.6 | 2.7 | 2.9 | 3.1 | 3.3 | 3.5 | 3.7 | 3.9 | 4.1 |

`dB = raw x 24 / 127 - 12` reproduces all fifteen readouts, so the byte range spans -12 to +12 dB. The same formula holds for both offsets, 3 and 57, which is why the app now prints decibels for each.

The Master level at offset 65 does **not** follow it. Stepping down from 105 gives 0.0, -0.2, -0.5, -0.8, -1.0 and -1.4 dB: an uneven, non-linear fader taper heading to -INF, which a straight line cannot describe. Rather than print a number that would be wrong, the app leaves that control raw.

Writing was then checked on the hardware for every offset a local preset restores — 3, 57, 66, 67, 70, 73, 77, 81, 82, 83 — using exactly the packet the app sends. Each accepted the write and read back the value, and the CabRig block was byte-identical to its starting state afterwards.

## Slot recall

`02 11 <slot>` does load the stored preset. After sending it for slot 2, the live parameters became exactly that slot's stored bytes, while Master stayed where it was because the compact format does not store it. Sending it for slot 1 restored the original state exactly.

The 15-byte stored format decodes as: bytes 0–8 are live offsets 0–8 in order, byte 10 is power, byte 11 is the valve response. That is how a one-byte difference in slot 2 was identified as a change of response from 6L6 to EL34.

## Still open

Offset 32 carries channel and boost state as a bitfield; bit 6 is boost and is writable, but the meaning of the low bits is not settled — values 1, 8 and 32 were all observed after channel changes, and writing them back does not switch channels, which `02 11` does. Offsets 10, 23, 40 and 41 hold state that is not identified; 32 and 41 refuse writes and report values of the amp's own choosing.

The hertz figures the app prints for the two cut filters remain an uncalibrated estimate. Architect shows only a 0–10 knob position, never a frequency, so there is nothing to fit against. The direction is right, the numbers are a guess, and the UI now prints them with a leading `~`.

## What was left as it was found

The live state was compared byte for byte at the end of the session against the state at the start: all 52 amplifier bytes and all 84 CabRig bytes identical. No permanent slot was written: the capture log contains zero save opcodes (`02 13`, `02 12`, `02 02`, `af`).
