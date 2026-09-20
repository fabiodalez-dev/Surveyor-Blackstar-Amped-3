#!/usr/bin/env python3
"""Extract and validate all AMPED 3 CabRig profiles from a HID capture log.

The input is produced by research/hid_capture_v2.dylib. A valid profile is an
AA header followed by AB requests 0..4, matching AC responses, and an AD end.
The command refuses to write partial profile sets: all 24 cabinets, six
microphones, and both axis positions must be present.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


EXPECTED_KEYS = {
    (cabinet, microphone, axis)
    for axis in range(2)
    for microphone in range(6)
    for cabinet in range(24)
}


def read_reports(path: Path):
    for line_number, line in enumerate(path.read_text().splitlines(), 1):
        fields = line.split()
        if len(fields) < 6 or fields[1] not in {"IN", "OUT"}:
            continue
        try:
            report = bytes.fromhex(fields[-1])
        except ValueError:
            continue
        if len(report) != 64:
            raise ValueError(f"line {line_number}: report is {len(report)} bytes")
        yield line_number, fields[1], report


def extract(path: Path) -> list[dict]:
    cabinet = microphone = axis = None
    current = None
    profiles: dict[tuple[int, int, int], dict] = {}

    for line_number, direction, report in read_reports(path):
        if direction == "OUT":
            if report[:4] == bytes((0xA9, 0, 0, 1)):
                cabinet = report[4]
            elif report[:4] == bytes((0xA9, 1, 0, 1)):
                microphone = report[4]
            elif report[:4] == bytes((0xA9, 2, 0, 1)):
                axis = report[4]
            elif report[0] == 0xAA:
                current = {
                    "cab": cabinet,
                    "mic": microphone,
                    "axis": axis,
                    "header": report.hex(),
                    "chunks": [],
                    "requests": [],
                    "line": line_number,
                }
            elif report[0] == 0xAC and current is not None:
                current["chunks"].append(report.hex())
        elif report[0] == 0xAB and current is not None:
            current["requests"].append(report[1])
        elif report[0] == 0xAD and current is not None:
            key = (current["cab"], current["mic"], current["axis"])
            if (
                None not in key
                and key in EXPECTED_KEYS
                and current["requests"] == [0, 1, 2, 3, 4]
                and len(current["chunks"]) == 5
            ):
                profiles[key] = {
                    field: current[field]
                    for field in ("cab", "mic", "axis", "header", "chunks")
                }
            current = None

    missing = sorted(EXPECTED_KEYS - profiles.keys())
    extra = sorted(profiles.keys() - EXPECTED_KEYS)
    if missing or extra:
        raise ValueError(
            f"capture is incomplete: {len(profiles)}/288 profiles; "
            f"missing={missing}; extra={extra}"
        )

    return [profiles[key] for key in sorted(profiles)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("capture", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    profiles = extract(args.capture)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(json.dumps(profiles, indent=2) + "\n")
    temporary.replace(args.output)
    print(f"wrote {len(profiles)} validated profiles to {args.output}")


if __name__ == "__main__":
    main()
