# Surveyor (Blackstar AMPED 3 Controller)

**Surveyor** is an open-source, mobile-first alternative to the official Blackstar "Architect" desktop software, specifically reverse-engineered and built for the **Blackstar AMPED 3** 100W pedal.

Born out of the frustration of needing a desktop PC to modify CabRig settings or deep EQ parameters, Surveyor gives you full USB-OTG control of your amplifier directly from your Android phone or tablet.

<div align="center">
  <img src="docs/screen_amp.png" width="200"/>
  <img src="docs/screen_cab.png" width="200"/>
  <img src="docs/screen_presets.png" width="200"/>
  <img src="docs/screen_settings.png" width="200"/>
</div>

## Features

- **Live USB-OTG Sync:** Connect your Android device directly to the AMPED 3 via USB. Bidirectional syncing means moving a physical knob on the amp instantly updates the app, and dragging a slider on the app instantly updates the amp.
- **CabRig Deep Dive:** Access hidden parameters not available on the physical pedal. Swap between all 23 Cabinets, 6 Microphones, toggle Axis, Stereo Width, Room Type, and master levels.
- **Logarithmic EQ Sweeps:** The Low-Cut and High-Cut filters have been mathematically mapped to display actual, usable frequencies (Hertz) instead of raw 0-255 MIDI values (e.g., Low-Cut from 20Hz to 400Hz).
- **Physical "Tolex" Aesthetic:** The UI mimics the physical head. When you adjust the Gain, watch the slider track heat up with an intense, glowing tube-valve gradient!
- **Simulation Mode:** No amp nearby? Tap "Simula" in the settings to activate *Ghost Mode*. The UI instantly unlocks with simulated dummy data, allowing you to build patches, test layouts, and review EQ settings offline.
- **Preset Management (.amped & .cabrig):** Import official XML patches from Architect Desktop directly into your phone. Burn them into the 3 hardware slots, or keep an unlimited number of patches stored locally on your device!

## The Reverse Engineering Journey

The AMPED 3 uses a proprietary, chunked USB HID protocol (`0x1036`) that differs completely from older ID:CORE amps. Through extensive packet sniffing, we uncovered exactly how the pedal communicates.

For example, we discovered that while basic EQ parameters can be updated live with 1-byte payloads, complex CabRig selections require the software to compile an entire DSP IR chunk and upload it in bulk! 

For full technical details, hex maps, and offset ranges (like the critical discovery that Room Level clips at `0x7f`), check out our [Protocol Documentation](docs/PROTOCOL.md).

## Installation and Build

This is a native Android application built with Kotlin and Jetpack Compose.

1. Clone the repository.
2. Open the project in Android Studio.
3. Build and deploy to your Android device (`./gradlew assembleDebug`).
4. Connect your AMPED 3 via a USB-C OTG cable.
5. Grant USB permissions when prompted by Android.

## Disclaimer

*Surveyor is an independent, community-driven project and is in no way affiliated with, endorsed by, or supported by Blackstar Amplification. Use at your own risk.*
