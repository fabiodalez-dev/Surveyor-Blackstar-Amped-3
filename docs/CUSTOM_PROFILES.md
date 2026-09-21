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

## Still to verify on hardware

Nobody has yet confirmed that the live coefficient table does not survive a power cycle. It is
expected not to, since boot reloads the stored slot, but it is one test worth doing with a profile
that has passed the checks and the cabinet level at minimum. Until then, treat it as unknown.

The response model has never been compared against a measured sweep at the pedal's output. A
logarithmic sweep would settle both the topology and the sample rate assumption in an afternoon
and is the single most useful thing anyone with a measurement rig could contribute.
