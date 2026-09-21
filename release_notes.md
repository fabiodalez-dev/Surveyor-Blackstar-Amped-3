### Surveyor v1.5.1 — say what the file has to be, and a proper logo

#### The app now states what it wants

v1.5.0 accepted a WAV and got on with it: it took the first channel of a multi-channel file, used
the first 170 ms and discarded the rest, and never said so. That is fine until the result is not
what you expected and there is nothing on screen to explain why.

The CabRig page states the requirements before you pick anything: WAV, any sample rate with no
resampling needed, 8/16/24/32-bit PCM or 32-bit float, first channel of a multi-channel file, and
a cabinet impulse response rather than a reverb, because sixteen filters cannot render a tail.

The conversion card then reports what was actually used: the file's sample rate, whether it was
mono or the first of several channels, how many milliseconds went into the fit, and whether the
file was truncated to get there.

#### The Surveyor badge

The embossed Surveyor logo is now the app's own asset, in the header over the tolex and on the
launch screen where it fades in over the warming filament. The source file is kept in
`docs/brand/` and the drawable ships as lossless WebP. The README opens with the banner.

#### More Italian text that reached the English UI

The pedal memories and backup headings, the save destinations, the save button and the
import/export results were hardcoded. They are translated now. All five README screenshots were
retaken from the current build.

**Download `Surveyor-v1.5.1.apk` below.** Android 8.0 or newer, USB-OTG cable required.
