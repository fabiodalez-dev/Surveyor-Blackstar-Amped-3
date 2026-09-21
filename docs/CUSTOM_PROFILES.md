# Cabinet profiles from an impulse response

Surveyor can fit a WAV impulse response onto the AMPED 3's own cabinet format and load the result
into the pedal. This file is the argument for why that is safe, and the description of what it
actually does. The short version: the fit never touches the poles, nothing is written to permanent
memory, and no candidate is sent unless it sits inside the envelope of the factory library.

## What a transfer can and cannot do

Loading coefficients writes a 260-byte table into the running DSP with the 0xAA/0xAC transfer.
That path carries no opcode that reaches flash, so it cannot brick the pedal and cannot alter the
six stored slots, which take a separate 0xAF command this feature never sends. The amplifier
model, the effects and every other parameter are in different blocks and are not touched.

What remains is an audio risk, and it is real, because the live DSP is what drives the speaker. A
coefficient set can be far louder than any cabinet Blackstar ships, can ring for seconds instead
of milliseconds, and can put energy at DC where the bank's lowest sections sit a few float32 ulps
from z = 1. Those are the failures the checks exist to prevent.

What is not known: the pedal's internal arithmetic. Fixed or floating point, saturating or
wrapping, we cannot inspect it. The response model itself, H = v0 + sum (b0 + b1 z) / (1 + a1 z +
a2 z^2), is inferred from the payload layout and from Architect's coefficient routine and has
never been checked against a measured sweep. Everything below is therefore modelled, not measured,
and the app says so wherever it shows a curve.

## Stability is inherited, not argued

The fit solves for the 33 numerator weights only: one direct term and two per section. The
sixteen denominators are copied bit for bit from a factory profile, so a fitted profile has
exactly the poles of a profile the pedal already plays, and its stability is not a property we
have to defend. A candidate whose a-values differ from its template by a single bit is refused.

Three factory profiles (7:5:0, 22:5:0, 22:5:1) quantise to `1 + a1 + a2 == 0` in one section, a
real pole exactly on the unit circle, nearly cancelled by that section's own zero. They stay
usable as shipped data, but they are never used as a fit template and no generated profile is
allowed to sit there: the factory tolerance is a quantisation accident, not a licence.

## The envelope

Every limit is the extreme reached across the 285 non-marginal factory profiles, rounded outward
by one digit so the profile that sets the extreme still passes. Recompute them with
`tools/safety_envelope.py`.

| check | limit | factory extreme | what it prevents |
|---|---|---|---|
| coefficient magnitude | 45 | 44.97 | nonsense payloads |
| pole distance from z=1 | 4.7e-7 | 4.768e-7 | an integrator at DC |
| pole distance from z=-1 | 0.5 | 0.5006 | ringing at Nyquist |
| peak gain | +6.8 dB | +6.67 | level |
| gain at DC | -20.5 dB | -20.59 | cone excursion |
| gain at 20 Hz | -11.4 dB | -11.48 | infrasonic energy |
| gain at 20 kHz | -32.2 dB | -32.25 | ultrasonic energy |
| gain at Nyquist | -41.8 dB | -41.85 | aliasing-band energy |
| single section peak | 43 dB | 42.95 | fragile cancellation |
| sum of sections over peak | 60.3 dB | 60.18 | fragile cancellation |
| impulse peak | 0.142 | 0.1405 | level |
| impulse L1 norm | 3.55 | 3.5397 | worst-case gain for a bounded input |
| energy | 0.2495 | 0.2493 | loudness against the factory reference |
| t60 | 62 ms | 61.4 ms | ringing |
| tail after 100 ms | -55.2 dB | -55.31 | non-decay |
| float32 vs float64 recursion | -80 dB | -83.8 | cancellation the state precision cannot hold |

The checks run on the float32-quantised values, because that is what the hardware receives, and
cheapest first: structure, coefficient bounds, pole bank identity, frequency response, impulse
response, then the float32 emulation. `CabProfileSafetyTest` asserts that the library passes its
own limits, that the three marginal profiles are refused, and that a profile 20 dB too loud, one
with a pole on z=1 and one with a modified pole bank are all refused.

## The fit

1. **WAV**: PCM 8/16/24/32 bit integer or 32 bit float, any sample rate, first channel.
2. **Trim**: start at the first sample within 60 dB of the peak, keep at most 170 ms (the longest
   factory t60 is 61 ms), fade the last tenth with a half cosine.
3. **Minimum phase** through the real cepstrum. A sixteen-section IIR cannot represent bulk delay
   or a non-minimum-phase reflection, and leaving them in drags the complex fit away from the
   magnitude it is supposed to match.
4. **Target** at 1024 log-spaced frequencies from 5 Hz to 20 kHz, evaluated directly at the file's
   own rate, so a 44.1 kHz impulse response needs no resampling: the rate only enters the
   exponent. A linear grid would spend most of its rows above 10 kHz.
5. **Weights** of 1 / |target|, floored 24 dB below the peak, which approximates a log-magnitude
   error without chasing spectral holes, plus 64 rows above 20 kHz with target zero so the model
   does not park energy where the source says nothing.
6. **Solve** by Householder QR on the equilibrated columns with a ridge term added as extra rows,
   never through the normal equations. If the candidate is refused, the ridge is multiplied by 100
   and the fit repeated, up to five attempts.
7. **Normalise** to the factory energy reference of 0.246, which every shipped profile sits within
   1 dB of; if the peak still exceeds the limit the whole profile is attenuated, because quieter
   is the safe direction.
8. **Report** the RMS log-magnitude deviation from the target over 60 Hz to 12 kHz.

Fitting a factory cabinet onto its own pole bank reproduces it to better than 0.25 dB
(`FitTest`), and the same cabinet resampled to 44.1 kHz lands on the same frequencies.

## Listening to one safely

Before a single byte goes out, the live amplifier and CabRig blocks, the factory payload for the
cabinet in use, the active slot and the original cabinet level are written to disk, not just held
in memory, so an app crash mid-audition still leaves a way back. The cabinet level is then set to
its minimum, because the first time you hear something new it should not be at gig volume, and
only then is the transfer sent.

While a converted profile is loaded the app shows a banner with a single control that restores the
factory cabinet and the original level. The flag lives in that file, so the banner comes back
after a restart. If the pedal stops answering, a power cycle restores the stored slot, which this
feature never wrote.

Saving a converted profile to one of the pedal's permanent slots is deliberately not offered.

## Verified on hardware, 21 September 2026

A profile fitted by the app's own code from a cabinet impulse response was loaded into a physical
AMPED 3. The transfer completed, all five chunks were requested and served, the amplifier block was
unchanged and the CabRig block changed only at the cabinet level, which had deliberately been set
to its minimum beforehand. The same profile was checked independently in Python before it was sent:
peak -1.23 dB, L1 1.62, energy 0.2460, t60 19.3 ms, all matching what the app reported to the digit.

**The live coefficient table does not survive a power cycle.** After switching the pedal off and on
the live state came back identical to the backup taken that morning: all 84 CabRig bytes and 51 of
the 52 amplifier bytes, the only difference being offset 10, one of the unidentified bytes, moving
by one. The custom coefficients were gone, and so were the owner's own unsaved edits. All six
stored slots were byte-identical to the backup afterwards.

This makes a power cycle a guaranteed recovery path: whatever a custom profile does to the sound,
switching the pedal off puts the stored cabinet back.

Writing one into a slot was then tested on the same unit. The destination slot was read and
persisted first, the profile was loaded into the live DSP, `0xAF` stored it, the acknowledgement
arrived and the slot read back with the new name and the expected parameters. After switching the
pedal off and on the slot still carried that name, and the pedal booted from it. The other two
slots were untouched throughout.

One limit is worth stating precisely. A slot stores the cabinet, microphone and axis indices, and
those are the template's, so reading the slot back cannot distinguish "our coefficient table was
stored" from "only the indices were stored and the factory table was reloaded at boot". The
protocol has no command that reads coefficients back; Architect only ever writes them. What
settles it is listening, and the owner reported the slot sounding as expected after the power
cycle. That is a report, not a measurement, and it is recorded here as such.

## Measuring the response: the USB route does not work

The AMPED 3 presents itself as a 4-in 4-out USB audio device at 48 kHz, which at least confirms the
sample rate the model assumes. It does not, however, give a way to measure the cabinet.

Playing a logarithmic sweep into the pedal as the system output device produced digital silence on
all four capture channels, so USB playback joins the signal after the cabinet simulation rather
than passing through it. Recording the capture channels while the owner played the guitar gave a
level flat to within a decibel for twenty seconds, with no transients. Raising the amplifier gain
from 20 to 127 over USB moved the captured noise by 2.6 dB, where a live amplifier path would move
by tens of decibels. Whatever those four channels carry in this configuration, it is not the
processed signal.

Measuring the transfer function therefore needs an audio interface on the pedal's line or
headphone output, or a microphone on a real cabinet. Until someone does that, the response model
stays a hypothesis and every curve the app draws is modelled rather than measured.
