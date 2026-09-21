### Surveyor v1.5.0 — cabinet profiles from an impulse response

The AMPED 3 has no convolution engine, so an IR cannot be uploaded to it. What it does have is a
cabinet described by sixteen recursive filters, and those can be computed. Surveyor now converts a
WAV impulse response into that format on the phone, shows you what came out, and lets you listen
to it with a way back.

#### What it does

Import a WAV, and the app fits it onto the pole bank of the cabinet currently loaded. The fit
solves only for the numerators: the sixteen denominators are copied bit for bit from the factory
profile, so a converted cabinet has exactly the poles of one the pedal already plays and its
stability is not something anyone has to take on trust.

You get the deviation from the source response in dB, the checks verdict, and the modelled
frequency response drawn over the cabinet it was fitted on, so you can see what actually changed.
Converted profiles can be named, saved and reloaded later, with their provenance and fit error
kept alongside them.

#### Why it will not hurt the pedal

Loading coefficients writes the live DSP. It is not a firmware path, and it cannot reach the six
stored slots, which take a separate command this feature never sends. Saving a converted cabinet
into permanent memory is deliberately not offered.

What remains is an audio risk, so every candidate has to pass a set of checks whose limits are not
a matter of taste: each one is the extreme reached across the 285 non-marginal factory profiles.
Peak gain, DC and infrasonic content, ultrasonic content, worst-case gain, energy, ringing time,
tail decay, section cancellation, and the difference between a float32 and a float64 recursion.
Nothing we generate is allowed to do something Blackstar's own data does not already do.
`tools/safety_envelope.py` recomputes those numbers from the shipped library, and the unit tests
assert that the library passes its own limits while a profile 20 dB too loud, one with a pole on
z = 1 and one with a modified pole bank are refused.

Three factory profiles quantise onto the unit circle. They stay usable as shipped data, but they
are never used as a fit template and nothing generated is allowed to sit there.

#### Listening to one

Before anything is sent, the live state, the factory payload for the cabinet in use and the
original cabinet level are written to disk, so a crash mid-audition still leaves a way back. The
cabinet level then goes to its minimum, because a new sound should not arrive at gig volume. While
a converted profile is loaded a banner offers one control that restores the factory cabinet and
the level, and it survives an app restart.

#### Accuracy

Fitting a factory cabinet onto its own pole bank reproduces it to better than 0.25 dB RMS across
60 Hz to 12 kHz, and the same cabinet resampled to 44.1 kHz lands on the same frequencies. A
44.1 kHz file needs no resampling: the rate enters the maths in one exponent.

#### Honest limits

The response model is inferred from the payload layout and from Architect's coefficient routine.
It has never been compared against a measured sweep at the pedal's output, so every curve the app
draws is modelled, not measured, and says so. Whether the live coefficient table survives a power
cycle is expected but unverified.

#### Also

28 unit tests, all passing. `docs/CUSTOM_PROFILES.md` carries the threat model, the envelope with
its provenance, the fitting algorithm and the recovery procedure. The published APK is a debug
build and carries two inspection hooks that do nothing on their own: `--ez demo true` fills the
interface with a state read from a real AMPED 3, and `--es ir <path>` converts a file without the
picker.

**Download `Surveyor-v1.5.0.apk` below.** Android 8.0 or newer, USB-OTG cable required.
