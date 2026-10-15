### Surveyor v1.8.0 — every cabinet at the same volume, if you want it

Blackstar gives every factory cabinet the same energy, and the app's own conversions do the same. The ear does not hear it that way: it is most sensitive between 2 and 5 kHz, so a bright cabinet sounds louder than a dark one carrying the same energy. Across the 285 usable factory cabinets the difference is about 6 dB, a 1x12 with an alnico speaker at the quiet end, the off-axis ribbon 4x12s at the loud end. The 136 shipped cabinets spread the same way.

#### Level the volume across cabinets

A new option on the CabRig page, off unless you turn it on. When it is on, loading a cabinet sets the Cabinet Level to compensate: every factory choice lands within one level step, 0.19 dB, of the others. The line under the option tells you how much the selected cabinet will be corrected before you load it.

It only ever turns down. Every cabinet is brought to the quietest factory one, nothing is raised, and the level never goes above the one you set. The order of the writes is chosen for the PA: the level drops before a louder cabinet is sent and comes back up only after a quieter one is in place, so the louder of the two never plays at the higher level, not even for the length of a transfer.

Your own level is kept. Move the Cabinet Level fader and change cabinet, and the app works from your new level. After anything that sets the level on its own — a slot recall, a preset, an audition, a reconnect, or an error — the app assumes no correction is in place, which can only make the next level lower, never higher. Writing a converted cabinet to a slot applies the same correction, so the slots stay level when you switch them on the pedal's own footswitch. Auditions still start at the minimum level.

The loudness is estimated from the modelled response of each cabinet (pink noise through the same weighting LUFS meters use), not measured on the pedal. `tools/loudness.py` repeats the calculation offline.

#### A shared profile cannot carry unknown metadata

Since 1.7.0 a single profile can be exported as a `.cab.json` and imported by someone else. The header of every profile carries six bytes whose meaning is unknown; all 288 factory profiles have the same six. Until now any value was passed to the pedal. A profile with anything else there is now refused, both when it is imported and immediately before it would be sent. Every factory, shipped and app-made profile already carries the factory value, so nothing you have is affected.

A shared recipe naming a cabinet outside the factory 24 × 6 × 2 matrix used to crash the lab's preview; it is now ignored.

#### Checked before release

All 136 shipped cabinets were re-measured with an independent implementation: every one decodes cleanly, keeps its factory pole bank bit for bit, and stays inside the factory envelope on every metric. 80 unit tests, lint with no errors. No hardware was written for this release: the new option uses only the Cabinet Level parameter, already verified on the AMPED 3.

**Download `Surveyor-v1.8.0.apk` below to install.**
