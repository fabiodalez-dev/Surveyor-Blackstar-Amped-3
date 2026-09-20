# CabRig DSP format and custom-sound research

## Verified findings

The captured library contains all 288 choices: 24 cabinets (including DI), six microphones and two axes. There are 276 distinct coefficient payloads; a few choices intentionally share data.

Each bulk transfer contains one `AA` header and five `AC` reports. Every report is 64 bytes. Bytes 1–2 contain a big-endian CRC-16/XMODEM of the report payload, byte 3 is its length, and data begins at byte 4. The five data lengths are 60, 60, 60, 60 and 20 bytes.

The assembled 260-byte payload is 65 little-endian float32 values. The first two header bytes contain its CRC-16/XMODEM in little-endian order; header bytes 2–3 contain 260 and bytes 4–5 contain the chunk count 5. All 288 captured profiles pass both checksum levels.

Architect's binary contains `DSP_BLOCK_BiquadFilter_init`, `DSP_Block_BiquadFilter.c`, `ercoefs` and `CabRigCoeffsBase`. Disassembly of the coefficient conversion routine shows at most 16 sections, four input floats per section, and a transform using cosine and multiplication by -2. Together with the 65-float payload, this identifies one direct coefficient plus sixteen compact second-order recursive-filter sections. The payload does **not** contain PCM samples, a WAV file, or a conventional long convolution IR.

## Three factory profiles sit exactly on the unit circle

Checking every profile's poles turned up three — 7:5:0, 22:5:0 and 22:5:1 — whose section 15 quantises to `1 + a1 + a2 == 0` in float32 (a1 = −1.99995744, a2 = +0.99995744). The denominator is therefore exactly zero at z = 1: a real pole sitting on the unit circle, not outside it. In each case the section's own numerator satisfies `b0 + b1 ≈ 0` to within 1e-10, so a zero at z = 1 cancels it. These are extremely high-Q resonators near DC, and the hardware plays them every day.

This matters because the tool used to reject them. Its rule was a flat "every pole strictly inside the unit circle", which refuses coefficient sets Blackstar itself ships. The rule is now: the output may not be less stable than the profile it came from. Clearly unstable coefficients are still refused, and a marginal template does not license making it worse — `tools/test_cabrig_dsp.py` checks all four cases.

## What can safely be changed

`tools/cabrig_dsp.py` decodes and validates captured profiles, checks pole stability after float32 quantisation, changes numerator gain, and rebuilds every checksum. It works offline and never opens the USB device. Example:

```bash
python3 tools/cabrig_dsp.py app/src/main/assets/cab_profiles.json \
  --key 21:5:0 --attenuate-db -6 --output /tmp/custom-cab.json
```

This proves that structurally valid custom coefficient packets can be made. Hardware acceptance and the resulting sound still require a controlled listening/measurement test. Start with attenuation only, back up all Cab slots, load into the active DSP, measure output, and restore without saving if anything is abnormal.

The tool also has an experimental `--fit-ir mono.wav` mode. It fits the numerator weights against the stable pole bank of an existing profile; it does not upload the WAV. The assumed topology, 48 kHz sample rate, section order, and gain normalisation have not yet been confirmed from an audio measurement, so fitted results must remain experimental.

## Limits and next experiments

A 16-section recursive model can approximate cabinet magnitude and resonance efficiently, but cannot reproduce an arbitrary long-delay IR exactly. The most useful safe hacks are custom cabinet/EQ curves, resonance damping, tonal morphs between measured profiles, and gain-normalised variants. Verify the transfer function through the USB/interface output with logarithmic sweeps before enabling custom uploads in the Android UI.

This coefficient table does not define the amplifier's drive, reverb, delay, modulation, or firmware program. New effect algorithms cannot be installed through the CabRig transfer. Additional effects would require finding another parameter or firmware protocol, or placing an external DSP/audio processor in the signal chain. Firmware modification has substantially greater brick risk and is outside the verified protocol.

## Persistent storage

The save procedure and recovery steps are in [STORAGE_VERIFICATION.md](STORAGE_VERIFICATION.md). The physical Mac-side test backed up all six slots, changed and recalled an Amp slot and Cab slot, then restored every slot byte-for-byte. Android now performs a pre-save read and local fsync, waits for the CabRig acknowledgement, rereads the slot, and verifies its name and data before reporting success. A physical power-cycle retention test and a complete Android-to-amp save test remain the final hardware checks.
