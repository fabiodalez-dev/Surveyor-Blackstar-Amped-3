# The extra cabinet library

`app/src/main/assets/cab_library.json` ships 29 cabinets on top of Blackstar's 288: 21 fitted from real impulse responses and 8 designed from the factory library. Every entry is a complete CabRig payload (header plus five chunks), the key of the factory pole bank it was fitted on, the fit error overall and per band, and where it came from. The app loads them in the CabRig page under "Extra cabinets"; they are tried, written to a slot and adopted into the user's own profiles exactly like a converted WAV, through the same safety checks.

## How it was built

`LibraryBuildHarness` (a JVM test, `app/src/test/.../LibraryBuildHarness.kt`) reads `app/build/ir_library/manifest.json`, runs each WAV through `IrConversion.convert` with the pole bank chosen automatically among the three nearest factory responses, requires every result to pass `SafetyCheck`, and writes the asset. The designed ones come from `app/build/ir_library/designed.json`, a list of recipes, through `IrConversion.design`. The WAVs are not in the repository; the manifest records each file's sha256 so a rebuild can be checked against the same source. Run it with `./gradlew testDebugUnitTest --rerun --tests '*LibraryBuildHarness*'` after putting the files in place.

Fit errors are the RMS difference in dB between the fitted and the original response, 60 Hz to 12 kHz, level ignored: 1.5 to 2.7 dB for the captured ones, which is what sixteen second-order sections on borrowed poles can do with a real cabinet, and under 0.7 dB for the designed ones, whose targets are built from the same kind of responses. The high band is where most of the error sits.

## Captured cabinets (CC0)

Both packs are by Bastian Karschewski, Jester Dyne Productions, released under CC0 1.0 (public domain dedication): "free to use in your private and commercial projects", redistribution and derivatives allowed with no conditions. The handbooks in the packs carry the licence text. Attribution is not required; it is given here anyway.

**Jester's Brutal Pack 1.0** (2022) — https://jester-dyne-productions.com/brutal-ir-pack/ — a modified, oversized Behringer BG412S 4x12 with Celestion Vintage 30, Celestion Rockdriver Junior (G12F-60) and Eminence DV-77 speakers; Shure SM57, Sennheiser e606 and Pyle PDMIC microphones, some patches a blend of two. 15 impulse responses, 48 kHz / 24 bit, used as shipped.

**Jester's Emerald Pack 1.0** (2023) — https://www.jester-dyne-productions.com/emerald-ir-pack/ — a Marshall 1960AX 4x12 with Celestion G12M Greenback 25 W, SM57 and e606, two speaker positions. 6 impulse responses, 48 kHz / 24 bit, used as shipped.

| id | cabinet | speaker | mic | pole bank | error |
|---|---|---|---|---|---|
| jester-brutal-01…15 | oversized 4x12 | V30 / G12F-60 / DV-77 | SM57 / e606 / PDMIC | nearest of 3 | 1.7–2.7 dB |
| jester-emerald-01…06 | Marshall 1960AX | G12M Greenback | SM57 / e606 | nearest of 3 | 1.5–2.5 dB |

Both packs were downloaded on 2026-10-15 from the mirror at darwinscat.com (which hosts them with the author's licence) and checked against the handbooks inside the zips.

## Designed cabinets

Eight cabinets built in the lab from the factory library, each with its recipe stored so it can be reopened and changed. They are named for the style they aim at ("Cali-style", "Blackface-style", "Tweed-style", "Brit-style") and say in the list which factory cabinet they start from. They are not captures of those amplifiers; they are the pedal's own models blended or shaped.

## What was not included, and why

Several well-known free packs with Mesa, Fender and Marshall cabinets (God's Cab, Seacow, Kalthallen, Forward Audio's free sets, the Tone3000 and ToneHunt uploads, the "650 Assorted Cabinet Impulses" compilation) are free to download and use, but either forbid redistribution, say nothing about it, or in the case of the compilation have no known author behind the "public domain" label. Fitted coefficients are a derivative of the impulse response, so only sources that allow derivatives and redistribution went in. A Fender 1x12 or a Mesa 4x12 capture under CC0 or CC BY would be welcome; the harness needs nothing more than the file and a manifest line.

Users who own other IR packs convert them on the phone with "Convert a WAV", which is the same code path; those stay on the phone and are never distributed by the app.
