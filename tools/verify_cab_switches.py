#!/usr/bin/env python3
"""Write test for the CabRig offsets mapped only from Architect's .cabrig files.

Live state only: no save opcode (02 13, 02 12, 02 02, af) is ever sent, and the cabinet
selectors 0-2 are never touched, so the loaded DSP coefficients stay as they are.
Before the first write it stores the live state and all six slots in a fresh backup file.
Each offset is written with the same a9 packet the app sends, the whole state is reread,
and the original value is put back. Architect must be closed; keep the amplifier silent.
"""
import argparse
import json
from pathlib import Path

from verify_slot_storage import Device

# offset -> values to try; the original value is always restored afterwards
TARGETS = {
    5: 'cabinet solo', 6: 'cabinet mute',
    56: 'room type', 58: 'room solo', 59: 'room mute', 60: 'room width',
    74: 'EQ bypass',
}
RANGES = {56: range(6), 60: range(3)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('backup', type=Path)
    args = parser.parse_args()
    assert not args.backup.exists(), 'Use a fresh backup file; never replace a recovery snapshot'
    d = Device()
    initial = d.state()
    slots = d.slots()
    args.backup.parent.mkdir(parents=True, exist_ok=True)
    args.backup.write_text(json.dumps({'initial': initial, 'saved': slots}, indent=2))
    print('PASS backup persisted:', args.backup, flush=True)

    results = {}
    try:
        for offset, label in TARGETS.items():
            old = initial['cab'][offset]
            values = [v for v in RANGES.get(offset, range(2)) if v != old]
            outcome = []
            for value in values:
                d.send([0xa9, offset, 0, 1, value])
                after = d.state()
                others = [i for i in range(84) if i != offset and after['cab'][i] != initial['cab'][i]]
                amp = [i for i in range(52) if after['amp'][i] != initial['amp'][i]]
                outcome.append({'written': value, 'read': after['cab'][offset],
                                'otherCabChanged': others, 'ampChanged': amp})
                d.send([0xa9, offset, 0, 1, old])
                assert d.state()['cab'][offset] == old, f'offset {offset} did not return to {old}'
            ok = all(o['read'] == o['written'] and not o['otherCabChanged'] for o in outcome)
            results[offset] = {'label': label, 'original': old, 'tries': outcome, 'pass': ok}
            print(('PASS' if ok else 'FAIL'), offset, label, 'original', old,
                  [(o['written'], o['read'], o['otherCabChanged']) for o in outcome], flush=True)
    finally:
        for offset in TARGETS:
            d.send([0xa9, offset, 0, 1, initial['cab'][offset]])
        final = d.state()
        same = final['cab'] == initial['cab'] and final['amp'] == initial['amp']
        print('PASS live state restored byte-for-byte' if same else 'FAIL live state differs from backup', flush=True)
        assert d.slots() == slots, 'persistent slots differ from backup'
        print('PASS six persistent slots untouched', flush=True)
        data = json.loads(args.backup.read_text())
        data['results'] = results
        data['final'] = final
        args.backup.write_text(json.dumps(data, indent=2))


if __name__ == '__main__':
    main()
