# Surveyor (Blackstar AMPED 3 Controller)

**Surveyor** is an open-source, mobile-first alternative to the official Blackstar "Architect" desktop software, specifically reverse-engineered and built for the **Blackstar AMPED 3** 100W pedal.

Born out of the frustration of needing a desktop PC to modify CabRig settings or deep EQ parameters, Surveyor gives you full USB-OTG control of your amplifier directly from your Android phone or tablet.

<div align="center">
  <img src="docs/screen_amp.png" width="700" alt="Amp Controls"/><br><br>
  <img src="docs/screen_cab.png" width="700" alt="CabRig Settings"/><br><br>
  <img src="docs/screen_presets.png" width="700" alt="Preset Library"/>
</div>

---

## 🎸 Features

- **No Desktop PC Required:** Connect your Android device directly to the AMPED via USB-OTG. Features live, zero-latency bidirectional syncing with the physical hardware.
- **CabRig DSP Protocol Decoded:** Surveyor includes all 288 factory CabRig DSP profiles, fully extracted and validated. More importantly, we cracked the proprietary 260-byte payload format, discovering it uses an ultra-fast 16-biquad recursive filter cascade (65 float32s) rather than heavy traditional convolutions.
- **Custom IR Conversion Tools:** The repository includes offline Python tools (`tools/cabrig_dsp.py`) capable of mathematically converting standard WAV Impulse Responses into the Blackstar-compatible 65-float structure, opening the door to custom IRs on closed hardware.
- **Deep Parameter Control:** Access hidden DSP parameters not available on the physical pedal. The Low-Cut and High-Cut filters have been mathematically mapped to display actual, usable frequencies (Hertz) instead of raw 0-255 MIDI values.
- **Hardware-Safe Preset Management:** Fully documented the hardware slot saving process. Surveyor performs a pre-save read, local fsync, and post-write verification to safely burn `.amped` patches into the physical hardware slots without risking DSP corruption.

---

## 🛠️ The Reverse Engineering Journey (Protocol Documentation)

The AMPED 3 uses a proprietary chunked USB HID protocol. Through packet capture and custom Python tools, the project reconstructs the messages needed by the controller.

This section outlines the reverse-engineered USB HID communication protocol for the Blackstar AMPED 3 pedal, primarily interacting via firmware version 1.03 as sniffed from Architect 2.1.3.

### Overview

The AMPED 3 uses 64-byte USB HID reports (VID `27d4`, PID `0072`).
Communication consists of:
- **`0x16` Commands:** Standard Amplifier parameters (Gain, EQ, ISF, Master).
- **`0xa9` Commands:** CabRig individual parameters (Cut filters, Room levels).
- **`0xaa`, `0xab`, `0xac` Commands:** Bulk IR/DSP profile transfers for Cabinets and Microphones.

### Core Message Structure

A standard parameter set command is formatted as follows:
`[CMD] [OFFSET_LOW] [OFFSET_HIGH] [COUNT] [VALUE_1] ... [VALUE_N] [PADDED_ZEROS]`

Example for setting an AMP parameter:
`16 00 00 01 7F` -> Command `16` (Amp), Offset `00` (Gain), Count `1`, Value `7F` (127).

Example for setting a CAB parameter:
`a9 39 00 01 7F` -> Command `a9` (CabRig), Offset `39` (57 = Room Level), Count `1`, Value `7F` (127).

### Amp Parameters (`0x16`)

These map 1:1 to the physical knobs on the pedal, with values ranging `0x00` (0) to `0x7f` (127):

| Offset (Dec) | Offset (Hex) | Parameter Name | Range |
|--------------|--------------|----------------|-------|
| 0            | 0x00         | Gain           | 0-127 |
| 1            | 0x01         | Bass           | 0-127 |
| 2            | 0x02         | Middle         | 0-127 |
| 3            | 0x03         | Treble         | 0-127 |
| 4            | 0x04         | ISF            | 0-127 |
| 5            | 0x05         | Response/Power | 0-127 |
| 6            | 0x06         | Master Volume  | 0-127 |

*Note: There are other offsets (7, 8, 9) involved in reverb, presence, and resonance depending on the specific patch or hidden Architect parameters.*

### CabRig DSP Format & Custom IRs (`0xaa` Bulk Transfers)

CabRig is handled entirely differently from standard Amp parameters. Through reverse-engineering the USB packet captures and the JUCE-based Architect binary, we've cracked the proprietary CabRig DSP format.

**The most significant discovery is that CabRig does NOT use conventional WAV Impulse Responses (IR) or long FIR convolution.**

Instead, Architect generates a highly optimized **260-byte DSP coefficient profile** for each cabinet/microphone/axis choice. It sends this to the amp via a bulk transfer (one `0xaa` header and five `0xac` 64-byte chunks, protected by a big-endian **CRC-16/XMODEM**).

#### The 65-Float Biquad Cascade
The 260-byte payload consists entirely of **65 little-endian float32 values**:
- 1 direct coefficient (gain/scaling).
- 16 compact second-order recursive-filter sections (IIR Biquads) (16 sections × 4 floats = 64 floats).

This explains the Amped 3's ultra-low latency: instead of processing heavy convolutions, it runs the signal through a cascade of 16 IIR biquad filters. This perfectly mimics the frequency magnitude and resonances of a physical guitar cabinet without the computational overhead of a room tail.

#### Custom Sounds (IR Conversion)
Because the format has been decoded, **it is technically possible to load custom IRs into the Amped 3**. 
This is achieved via a process called **System Identification (IIR Filter Approximation)**:
1. You take a traditional WAV IR (FIR).
2. You use curve-fitting algorithms to calculate the 65 float32 coefficients that generate an identical EQ curve.
3. To prevent the IIR filter from exploding (unstable poles), Surveyor's offline tools (`tools/cabrig_dsp.py`) use an experimental `--fit-ir` mode that fits the numerator weights against the *already stable* pole bank of an official Blackstar profile.

This guarantees stability while allowing you to technically alter the profile to match your favorite external IRs! 

The checked-in `cab_profiles.json` contains the complete factory 24 × 6 × 2 matrix (288 profiles). Surveyor performs a pre-save read and local fsync, waits for the CabRig acknowledgement, rereads the slot, and verifies its name and data to ensure 100% hardware safety during persistent storage operations.

However, some specific post-EQ parameters can be manipulated individually via `0xa9` without a bulk transfer:

| Offset (Dec) | Offset (Hex) | Parameter Name     | Range   | Notes |
|--------------|--------------|--------------------|---------|-------|
| 57           | 0x39         | Room Level         | 0-127   | Maxes out at 0x7F! |
| 67           | 0x43         | High-Cut Frequency | 0-255   | 0 = 2kHz, 255 = 20kHz (Logarithmic) |
| 83           | 0x53         | Low-Cut Frequency  | 0-255   | 0 = 20Hz, 255 = 400Hz (Logarithmic) |
| 66           | 0x42         | Low-Cut Toggle     | 0-1     | 0 = Off, 1 = On |
| 82           | 0x52         | High-Cut Toggle    | 0-1     | 0 = Off, 1 = On |

*Important discovery: Do not send values > 127 to the Room Level offset (57), as the physical DSP clips at 127 (`0x7F`). Surveyor handles this internally to prevent DSP overflow.*

### Hardware Interaction & Slot Switching
To change channels (slots), send `0x11` (Amp slots) or `0x01` (Cab slots) encapsulated in a `0x02` (System) command:
- `02 11 01 00` -> Change to AMP Slot 1 (Clean)
- `02 01 02 00` -> Change to CAB Slot 2

---

## 🚀 Installation

1. Go to the **[Releases](../../releases/latest)** page on this GitHub repository.
2. Download the latest `Surveyor-vX.X.X.apk` file directly to your Android device.
3. Open the downloaded APK and tap **Install** (you may need to allow "Install from Unknown Sources" in your Android settings).
4. Connect your AMPED 3 pedal to your phone using a USB-C OTG cable.
5. Open Surveyor and grant USB permissions when prompted. You're ready to rock!

*(For developers: You can clone this repository and build it locally using Android Studio and `./gradlew assembleDebug`).*

---

## ⚠️ Disclaimer

*Surveyor is an independent, community-driven project and is in no way affiliated with, endorsed by, or supported by Blackstar Amplification. Use at your own risk.*
