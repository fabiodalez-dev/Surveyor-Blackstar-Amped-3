# Blackstar AMPED 3 - Reverse Engineered USB HID Protocol

This document outlines the reverse-engineered USB HID communication protocol for the Blackstar AMPED 3 pedal, primarily interacting via firmware version 1.03 as sniffed from Architect 2.1.3.

## Overview

The AMPED 3 uses standard USB HID interrupt transfers (Report ID 1) with 64-byte payloads.
Communication consists of:
- **`0x16` Commands:** Standard Amplifier parameters (Gain, EQ, ISF, Master).
- **`0xa9` Commands:** CabRig individual parameters (Cut filters, Room levels).
- **`0xaa`, `0xab`, `0xac` Commands:** Bulk IR/DSP profile transfers for Cabinets and Microphones.

## Core Message Structure

A standard parameter set command is formatted as follows:
`[CMD] [OFFSET_LOW] [OFFSET_HIGH] [COUNT] [VALUE_1] ... [VALUE_N] [PADDED_ZEROS]`

Example for setting an AMP parameter:
`16 00 00 01 7F` -> Command `16` (Amp), Offset `00` (Gain), Count `1`, Value `7F` (127).

Example for setting a CAB parameter:
`a9 39 00 01 7F` -> Command `a9` (CabRig), Offset `39` (57 = Room Level), Count `1`, Value `7F` (127).

## Amp Parameters (`0x16`)

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

## CabRig Parameters (`0xa9` and Bulk)

CabRig is handled differently. Architect performs PC-side DSP processing to generate a complete Cab/Mic/Room IR profile. When changing significant parameters like Cab Type, Mic Type, Axis, or Stereo Width, Architect initiates a **bulk transfer** (`aa`, `ab`, `ac` packets) which flashes the entire generated profile to the pedal. 

However, some specific post-EQ parameters can be manipulated individually via `0xa9` without a bulk transfer:

| Offset (Dec) | Offset (Hex) | Parameter Name     | Range   | Notes |
|--------------|--------------|--------------------|---------|-------|
| 57           | 0x39         | Room Level         | 0-127   | Maxes out at 0x7F! |
| 67           | 0x43         | High-Cut Frequency | 0-255   | 0 = 2kHz, 255 = 20kHz (Logarithmic) |
| 83           | 0x53         | Low-Cut Frequency  | 0-255   | 0 = 20Hz, 255 = 400Hz (Logarithmic) |
| 66           | 0x42         | Low-Cut Toggle     | 0-1     | 0 = Off, 1 = On |
| 82           | 0x52         | High-Cut Toggle    | 0-1     | 0 = Off, 1 = On |

*Important discovery: Do not send values > 127 to the Room Level offset (57), as the physical DSP clips at 127 (`0x7F`).*

## Hardware Interaction & Simulation
To change channels (slots), send `0x11` (Amp slots) or `0x01` (Cab slots) encapsulated in a `0x02` (System) command:
- `02 11 01 00` -> Change to AMP Slot 1 (Clean)
- `02 01 02 00` -> Change to CAB Slot 2

When connecting without a physical pedal, Surveyor can operate in "Ghost Mode" (Simulation), artificially bypassing the USB write buffer and updating the UI state machine instantly to preview DSP logic and UI layout.
