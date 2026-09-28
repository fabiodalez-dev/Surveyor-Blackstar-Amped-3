#!/usr/bin/env python3
"""Identifies the unmapped bytes (9, 12, 13, 14) of the 15-byte stored AMP record.

This one WRITES slot memory. Before the first write it stores the live state, all six slots
and each AMP slot's recalled state in a fresh backup file. Then, one live offset at a time,
it saves the active AMP slot from a 52-byte block that differs from the original in that
offset only, reads the stored record back and notes which bytes moved. Finally it saves the
original block and name again and requires all six slots and the live state to match the
backup byte for byte. Architect must be closed; keep the amplifier silent; never interrupt.
"""
import argparse
import json
from pathlib import Path

from verify_slot_storage import Device

# live offset -> meaning, as mapped in the app
PROBES = {0: 'gain (control)', 9: 'master', 21: 'power', 22: 'valve response', 24: 'boost position', 25: 'reverb character', 26: 'clean voice',
          27: 'crunch voice', 28: 'OD voice', 33: 'reverb on', 32: 'boost bit (64)'}


def stored(d, slot):
    return bytes.fromhex(d.slots()[f'21:{slot}']['15'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('backup', type=Path)
    parser.add_argument('--offsets', help='comma-separated live offsets to probe instead of the default set')
    parser.add_argument('--live', action='store_true', help='also write the changed value live before saving')
    args = parser.parse_args()
    assert not args.backup.exists(), 'Use a fresh backup file; never replace a recovery snapshot'
    d = Device()
    initial = d.state()
    slots = d.slots()
    backup = {'initial': initial, 'saved': slots, 'recalled': {}}
    args.backup.parent.mkdir(parents=True, exist_ok=True)
    args.backup.write_text(json.dumps(backup, indent=2))
    for s in (1, 2, 3):
        backup['recalled'][s] = d.recall(False, s)
    slot = initial['ampSlot']
    base = d.recall(False, slot)['amp']
    args.backup.write_text(json.dumps(backup, indent=2))
    print('PASS backup persisted:', args.backup, flush=True)

    name = bytes.fromhex(next(iter(slots[f'20:{slot}'].values()))).split(b'\0')[0].decode('ascii')
    original = bytes.fromhex(slots[f'21:{slot}']['15'])
    results = {}
    try:
        probes = {int(o): PROBES.get(int(o), f'offset {o}') for o in args.offsets.split(',')} if args.offsets else PROBES
        for offset, label in probes.items():
            block = list(base)
            if offset == 32: block[offset] ^= 64
            elif offset in (21, 22): block[offset] = 3 if block[offset] != 3 else 2  # valid selector values only
            else: block[offset] = 1 - block[offset] if block[offset] in (0, 1) else block[offset] - 1
            if args.live:
                d.send([0x16, offset, 0, 1, block[offset]])
                d.read(.3)
            d.save(False, slot, {'amp': block}, name)
            if args.live:
                d.send([0x16, offset, 0, 1, base[offset]])
                d.read(.3)
            record = stored(d, slot)
            moved = {i: (original[i], record[i]) for i in range(15) if record[i] != original[i]}
            results[offset] = {'label': label, 'from': base[offset], 'to': block[offset], 'moved': moved}
            print(f'{offset:2} {label:18} {base[offset]} -> {block[offset]}  stored bytes moved: {moved}', flush=True)
    finally:
        d.save(False, slot, {'amp': base}, name)
        final_slots = d.slots()
        backup['results'] = results
        backup['finalSlots'] = final_slots
        args.backup.write_text(json.dumps(backup, indent=2))
        ok_slots = final_slots == slots
        print('PASS all six persistent slots restored byte-for-byte' if ok_slots else 'FAIL slots differ from backup', flush=True)
        d.recall(False, slot)
        live = d.state()
        for i in range(52):
            if live['amp'][i] != initial['amp'][i]:
                d.send([0x16, i, 0, 1, initial['amp'][i]])
        final = d.state()
        same = final['amp'] == initial['amp'] and final['cab'] == initial['cab']
        backup['final'] = final
        args.backup.write_text(json.dumps(backup, indent=2))
        print('PASS live state restored byte-for-byte' if same else 'FAIL live state differs from backup', flush=True)
        assert ok_slots and same


if __name__ == '__main__':
    main()
