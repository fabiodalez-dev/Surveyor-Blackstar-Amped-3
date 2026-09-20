#!/usr/bin/env python3
"""AMPED 3 storage experiment. Architect must be closed. No firmware writes.

Snapshots all six slots and their recalled live state before testing one AMP
and one CabRig slot. An exercise changes one value, saves it, recalls another
slot and the target, then restores and compares every saved slot byte-for-byte.
Use --exercise only with the amplifier silent. Never interrupt the restore.
"""
import argparse
import json
import time
from pathlib import Path
import hid


class Device:
    def __init__(self):
        self.h = hid.device()
        self.h.open(0x27d4, 0x72)

    def send(self, values):
        b = bytes(values)
        assert len(b) <= 64
        assert self.h.write(b'\0' + b.ljust(64, b'\0')) in (64, 65)

    def read(self, seconds=.3):
        end = time.monotonic() + seconds
        reports = []
        while time.monotonic() < end:
            b = self.h.read(64, 50)
            if b:
                reports.append(bytes(b))
        return reports

    def state(self):
        self.read(.1)
        self.send([7])
        self.send([2, 0x16, 0, 0])
        self.send([2, 6, 0, 0])
        result = {'amp': [-1] * 52, 'cab': [-1] * 84}
        for b in self.read(.5):
            if b[0] in (0x16, 0xa9):
                target = result['amp' if b[0] == 0x16 else 'cab']
                offset = int.from_bytes(b[1:3], 'little')
                target[offset:offset+b[3]] = b[4:4+b[3]]
            if b[:2] == bytes([2, 0x16]): result['ampSlot'] = b[2]
            if b[:2] == bytes([2, 6]): result['cabSlot'] = b[2]
        assert -1 not in result['amp'] + result['cab'], result
        return result

    def slots(self):
        result = {}
        for slot in range(1, 4):
            for kind in (0x14, 0x15, 4, 5):
                self.send([2, kind, slot, 0])
                data = {str(b[3]): b[4:4+b[3]].hex() for b in self.read(.18)
                        if b[:3] == bytes([2, kind, slot])}
                assert len(data) == (2 if kind == 5 else 1), (slot, kind, data)
                result[f'{kind}:{slot}'] = data
        return result

    def recall(self, cab, slot):
        self.send([2, 1 if cab else 0x11, slot, 0])
        self.read(.7)
        return self.state()

    def save(self, cab, slot, state, name):
        if cab:
            self.send([0xaf, slot-1, 0, 0])
        else:
            self.send([2, 0x13, slot, 52] + state['amp'])
        name = name.encode('ascii')
        self.send(bytes([2, 2 if cab else 0x12, slot, len(name)]) + name)
        reports = self.read(1.2)
        if cab:
            assert any(b[:2] == bytes([0xaf, slot-1]) for b in reports), 'missing CabRig save ACK'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('backup', type=Path)
    parser.add_argument('--exercise', action='store_true')
    args = parser.parse_args()
    assert not args.backup.exists(), 'Use a fresh backup file; never replace a recovery snapshot'
    d = Device()
    initial = d.state()
    saved = d.slots()
    backup = {'initial': initial, 'saved': saved, 'recalled': {}}
    args.backup.parent.mkdir(parents=True, exist_ok=True)
    args.backup.write_text(json.dumps(backup, indent=2))
    for cab in (False, True):
        for slot in range(1, 4):
            backup['recalled'][f'{cab}:{slot}'] = d.recall(cab, slot)
    args.backup.write_text(json.dumps(backup, indent=2))
    print('PASS six-slot backup persisted:', args.backup, flush=True)
    if args.exercise:
        for cab in (False, True):
            slot = initial['cabSlot' if cab else 'ampSlot']
            field, offset = ('cab', 70) if cab else ('amp', 0)
            original = d.recall(cab, slot)
            old = original[field][offset]
            modified = old-1 if old else 1
            name_record = saved[f'{4 if cab else 20}:{slot}']
            name = bytes.fromhex(next(iter(name_record.values()))).split(b'\0')[0].decode('ascii')
            try:
                d.send([0xa9 if cab else 0x16, offset, 0, 1, modified])
                changed = d.state()
                assert changed[field][offset] == modified
                d.save(cab, slot, changed, name)
                d.recall(cab, slot % 3 + 1)
                recalled = d.recall(cab, slot)
                assert recalled[field][offset] == modified, 'saved parameter was not recalled'
                print('PASS saved and recalled:', field, slot, old, '->', modified, flush=True)
            finally:
                d.send([0xa9 if cab else 0x16, offset, 0, 1, old])
                restored = d.state()
                if not cab: restored['amp'] = original['amp']
                d.save(cab, slot, restored, name)
                print('Restored original', field, slot, flush=True)
        assert d.slots() == saved, 'persistent slots differ from backup'
        print('PASS all six persistent slots restored byte-for-byte', flush=True)
    d.recall(False, initial['ampSlot'])
    d.recall(True, initial['cabSlot'])
    profiles = json.loads((Path(__file__).resolve().parents[1] / 'app/src/main/assets/cab_profiles.json').read_text())
    p = next(p for p in profiles if [p['cab'], p['mic'], p['axis']] == initial['cab'][:3])
    for offset, value in enumerate(initial['cab'][:3]):
        d.send([0xa9, offset, 0, 1, value])
    d.send(bytes.fromhex(p['header']))
    end = time.monotonic() + 5
    complete = False
    while time.monotonic() < end:
        b = d.h.read(64, 100)
        if not b: continue
        if b[0] == 0xab: d.send(bytes.fromhex(p['chunks'][b[1]]))
        if b[0] == 0xad:
            complete = True
            break
    assert complete, 'original DSP profile restore timed out'
    # Restore unsaved live parameters after the storage test, without burning.
    for cab, field in ((False, 'amp'), (True, 'cab')):
        current = d.state()[field]
        for offset, value in enumerate(initial[field]):
            if current[offset] != value:
                d.send([0xa9 if cab else 0x16, offset, 0, 1, value])
    final = d.state()
    assert final['amp'] == initial['amp'] and final['cab'] == initial['cab']
    print('PASS original live parameters restored', flush=True)
    d.h.close()


if __name__ == '__main__':
    main()
