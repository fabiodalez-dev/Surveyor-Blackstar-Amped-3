### Surveyor v1.2.0 — parameter map verified on the hardware

This release is mostly about being able to trust what the app sends. Every parameter offset was checked against a physical AMPED 3 with Architect open, and two of them turned out to be wrong.

#### Fixed: the two cut filters were swapped

The slider labelled **Low-Cut** wrote the high-cut offset, and the one labelled **High-Cut** wrote the low cut. Anyone who adjusted either control was moving the other filter. Offset 67 is the low cut and 83 is the high cut, established by moving one Architect control at a time with the USB traffic captured, and corroborated by the factory EQ presets: "Cocked Wah" keeps only midrange with 67 at its maximum and 83 near its minimum. A unit test now fails if the two are ever swapped again.

The hertz figures shown for those filters are printed with a leading `~` from now on. Architect displays only a 0-10 knob position and never a frequency, so the mapping is a sensible estimate, not a measurement.

#### Fixed: Italian text in the English UI

The status line, the errors and the save messages were hardcoded in Italian and appeared that way regardless of the selected language. All of them are translated now.

#### Verified, and documented in docs/PROTOCOL_VERIFICATION.md

- All ten continuous amplifier parameters, by reading the values Architect displays and matching them against the pedal's own live block. Each knob matched exactly one offset out of fifty-two, every residual under half a step, with nothing written to the amp.
- Power, valve response, boost position, reverb character and the three channel voicings, against what Architect showed at the same moment.
- Reverb on/off is offset 33; boost is bit 6 of offset 32, and both accept writes.
- `02 11 <slot>` really does recall a stored preset, and the 15-byte stored format is the first nine live parameters followed by power and valve response.
- The live state was compared byte for byte before and after the session: all 52 amplifier bytes and all 84 CabRig bytes identical, and no permanent slot was written.

#### Fixed: the offline tool refused three factory profiles

`tools/cabrig_dsp.py` rejected 7:5:0, 22:5:0 and 22:5:1 as unstable. Their section 15 quantises to a real pole at exactly z = 1, cancelled by that section's own zero — marginal by construction and shipped by Blackstar. The rule is now that output may not be less stable than its source, which still refuses genuine instability. `tools/test_cabrig_dsp.py` covers it.

#### Also

- Verified offsets are named constants instead of numbers scattered through the UI.
- Cabinet Level (offset 3) is mapped but not yet exposed in the UI.
- 17 unit tests, all passing.

**Download `Surveyor-v1.2.0.apk` below.** Android 8.0 or newer, USB-OTG cable required.
