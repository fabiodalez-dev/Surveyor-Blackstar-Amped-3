### Surveyor v1.6.0 — keep a converted cabinet, or just try it

Until now a converted profile lived only in the running DSP: switch the pedal off and the stored
cabinet came back. That was deliberate, and it is now verified rather than assumed, because a power
cycle on a physical AMPED 3 brought the live state back identical to the backup taken that morning:
all 84 CabRig bytes and 51 of the 52 amplifier bytes, the owner's own unsaved edits gone with it.
A power cycle is a guaranteed way back.

Knowing that, the other half can be offered safely: a converted profile can now be **written into
one of the three CabRig slots**, where it survives being switched off.

#### Two ways, and the app says which is which

**Try** loads the profile into the live DSP. It lasts until the pedal is switched off. Nothing in
the pedal's memory is touched.

**To a slot** makes it permanent, and says plainly that it overwrites the cabinet that slot holds
today, showing you the three slots with their current names so you know what you are replacing.
Before the pedal is touched, the slot's contents are read and written to the phone with an fsync;
the save is confirmed by its acknowledgement; and the slot is read back and compared. If any of
that fails the app says the write is unconfirmed and tells you to keep the backup rather than
repeat it.

#### Checked against the bytes, not against a flag

A profile is re-verified immediately before it leaves, by decoding the coefficients that are about
to be sent and running the full acceptance test on them. A verdict stored when the profile was made
describes an older version of the code; this describes these coefficients now. A profile that fails
is not sent, and the reason is shown.

#### Verified on the hardware

A profile fitted by the app's own code was loaded into a physical AMPED 3: the transfer completed,
the amplifier block was untouched, and the CabRig block moved only at the cabinet level, which had
been set to its minimum first. The same profile was independently checked in Python before it was
sent and agreed with the app to the digit. All six stored slots were byte-identical to the backup
afterwards.

`docs/CUSTOM_PROFILES.md` carries the power cycle result and a note on why the USB audio path
cannot be used to measure the cabinet response: a sweep played through the device appears nowhere
on the capture channels, and a gain change from 20 to 127 moves the captured noise by 2.6 dB.

**Download `Surveyor-v1.6.0.apk` below.** Android 8.0 or newer, USB-OTG cable required.
