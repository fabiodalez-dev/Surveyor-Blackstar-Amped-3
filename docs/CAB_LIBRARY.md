# The extra cabinet library

`app/src/main/assets/cab_library.json` ships 136 cabinets on top of Blackstar's 288: 128 fitted from real impulse responses and 8 designed from the factory library. Every entry is a complete CabRig payload (header plus five chunks), the key of the factory pole bank it was fitted on, the fit error overall and per band, and where it came from. The app loads them in the CabRig page under "Extra cabinets", filtered by brand, style, microphone, source and text; they are tried, written to a slot and adopted into the user's own profiles exactly like a converted WAV, through the same safety checks.

What ships is never the impulse response itself. A library entry is 65 numbers: a direct gain and sixteen second-order sections whose poles are borrowed from a factory cabinet and whose zeros are fitted to the magnitude of the original response. It is a model of the cabinet's tone curve, within 0.4 to 3 dB RMS of the original between 60 Hz and 12 kHz, and nothing else of the file survives: no phase, no room, no length. The WAVs stay on the machine that built the library; the manifest records each file's sha256 so a rebuild can be checked against the same source.

## How it was built

Three JVM tests in `app/src/test/.../LibraryBuildHarness.kt` do the work, all gated on files under `app/build/ir_library/` that are not in the repository:

- `LibrarySurveyHarness` reads every `candidates*.json` list, fits every WAV with the app's own `IrConversion.convert` (pole bank chosen automatically among the three nearest factory responses) and writes `survey.json`: pass or refusal, error overall and per band, pole bank, and the fitted curve on the 240-point grid. It never fails on a bad file.
- An offline selection script reads the survey and chooses the shipped set: only fits that pass `SafetyCheck` with at most 3 dB of error, one copy of any file that appears twice, a quota per brand (Mesa 26, Marshall 26, Fender 16, Orange 9, Vox 4, Bogner 4, ENGL 3, EVH 3, two or three each for Friedman, Peavey, Roland, Two-Rock, Hiwatt and bass cabinets, 10 unbranded), at most four entries from any one pack, and inside each brand a greedy farthest-point pick on the fitted curves (RMS distance, level ignored) seeded by the best-known entry, so each new pick is the one that sounds least like what is already there. Rare speakers and cabinets (Oxford, Fane, Jensen, alnico, Lone Star, Road King, 2x15, 8x10, Driftwood, Karnivore, G12H30, G12-65) get a bonus. It writes `manifest.json`.
- `LibraryBuildHarness` reads `manifest.json` and `designed.json` (recipes), fits each entry again, requires every result to pass `SafetyCheck`, and writes the asset. Run it with `./gradlew testDebugUnitTest --rerun --tests '*LibraryBuildHarness*'`.

The survey of 1330 candidates took about an hour: 1144 usable fits, 74 refused by the safety check or unreadable, 83 over 3 dB. Fit errors of the shipped set run from 0.4 to 3.0 dB, median 2.1 dB; the high band is where most of the error sits.

## Where the 128 captured cabinets come from

**Tone3000, 64 entries from 44 tones.** The cabinet IRs on tone3000.com were listed through the site's own search, sorted by favourites, and the top 60 cabinet tones were downloaded (up to eight files each, spread over the pack). Each entry records the tone title, the uploader and the tone's licence as Tone3000 publishes it (`t3k`, Tone3000's own sharing terms, for all of them). Among them: Mesa OS 4x12 (SM57 + M160), Mesa Standard Oversized V30, Mesa Boogie ST 4x12A, a Mesa OS with Black Shadow, DV-77 and Swamp Thang speakers, Marshall 1960TV with Greenbacks and M201, 1960BV V30 + G12T-75, 1960A G12-65, 1960 Lead with Fane speakers, a '71 full stack, Fender Twin Reverb with Oxford 12T6-10, Deluxe Reverb, a 1970 Bassman 2x15 with CTS speakers, Orange PPC412 and 2x12, '64 Vox AC30 Blue, EVH 5150III, ENGL E412 with Eminence Karnivore, Roland JC-120, Driftwood 4x12, Ampeg SVT 8x10, and album-named blends (Nirvana, Metallica, Will Putney).

**CC0 packs, 21 entries.** Jester's Brutal Pack 1.0 and Emerald Pack 1.0 by Bastian Karschewski, Jester Dyne Productions, CC0 1.0: a modified oversized 4x12 with Vintage 30, Rockdriver Junior and DV-77 speakers, and a Marshall 1960AX with Greenbacks. https://jester-dyne-productions.com/

**A local collection, 43 entries.** Impulse responses the author of this app had on disk, from packs bought or downloaded over the years, surveyed the same way and sampled for variety: God's Cab 1.4 (Wilkinson Audio / Signals Audio, Mesa Rectifier 4x12, four microphones), the Line 6 Allure Pack by Dan Boul of 65 Amps ('64 US Deluxe P12N, '67 Brit Greenback, '70s WhoWatt), Live Ready Sound's Powercab pack (Redback, EVH, Jensen C12K), Choptones' Boogie Road King Redback (RE20, PR30, MD421 mix), OwnHammer's H30L 2011A, Kalthallen Cabs, Aegean Music's 1960A, forward audio's faIR Modern Rock and Post Grunge, Javier Serrano's WS, Bogner 2x12 and 4x12 captures, a Two-Rock 2x12, a Friedman, a Fender Twin, a Roland JC-120, a Marshall AVT and MG412A, an Orange 4x12, the "6100" and "6505" Orange and BS1 captures, Catharsis' Recto cab, and a few files named after songs. Entries from files whose origin was not written down say "origin not recorded".

Every entry's `license` field says what the source stated: CC0; Tone3000's terms; free or donation-ware with redistribution not addressed; free with a readme that forbids redistribution of the files; paid pack. The field is shown in the list so the user knows what sort of source a model came from. Shipping the fitted model rather than the file is the reason the paid and "do not redistribute" sources are included at all; if a source's author objects to even that, the entry comes out with one line in the manifest.

## Designed cabinets

Eight cabinets built in the lab from the factory library, each with its recipe stored so it can be reopened and changed. They are named for the style they aim at ("Cali-style", "Blackface-style", "Tweed-style", "Brit-style") and say in the list which factory cabinet they start from. They are not captures of those amplifiers; they are the pedal's own models blended or shaped.

## Not included

Bass cabinets beyond two Ampeg SVTs, acoustic-guitar body responses, reverb and pedal responses, and everything the survey refused or fitted worse than 3 dB. Users who own other IR packs convert them on the phone with "Convert a WAV", which is the same code path; those stay on the phone and are never distributed by the app.
