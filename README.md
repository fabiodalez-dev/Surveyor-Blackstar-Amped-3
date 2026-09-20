# Surveyor for Blackstar Amped 3

*Because while the "Architects" stay in the office (Mac/Windows), the "Surveyors" do the actual work on the field (Android).*

## The Story
Blackstar released the amazing Amped 3, a 100W 3-channel pedalboard amplifier. They also provided an official desktop software called **Architect** to tweak the deep DSP settings, CabRig emulations, and EQ biases. 

But what if you are on a stage or in a rehearsal room and all you have is your Android phone? You can't run Architect. You are left entirely in the dark. There is no official Android app. 

So, we reversed-engineered the entire proprietary USB HID protocol of the Amped 3, sniffed the packets, mathematically mapped the offsets of every single DSP parameter (and discovered some funny bugs in their logic along the way), and built **Surveyor**.

## What it does
Surveyor is a blazing-fast, native Jetpack Compose Android app that talks directly to your Amped 3 via a USB OTG cable.

- **Full Amp Control**: Tweak Gain, Volume, EQ (Bass, Middle, Treble, ISF), Master, and Presence in real-time.
- **Deep Voice Editing**: Change the power amp response (EL84, EL34, 6L6), Reverb character (Dark/Light), and Pre/Post boost routing.
- **CabRig Mastery**: Select all 24 cabinets, all 6 microphones, tweak Room levels, Stereo Width, and Master EQ.
- **Hardware Saving**: Burn your presets directly into the pedal's EEPROM flash memory without ever touching a PC.
- **Live Sync**: Turn a physical knob on the amp, and watch the app update instantly.
- **Auto Backups**: Every time you plug in, Surveyor secretly backs up all your Amp and CabRig slots into standard JSON files.

## How to build
Open the project in Android Studio, hit `Run`, and grab a USB-C to USB-C cable. 
Give Android permission to access the USB device when prompted.

## Disclaimer
This project is not affiliated with, endorsed by, or in any way officially connected to Blackstar Amplification. We just really wanted to use our pedals with our phones.

*Built with ❤️ (and a lot of hex dumps).*
