#!/usr/bin/env python3
"""Estimated loudness of CabRig profiles and the level correction that evens it out. Offline only.

Blackstar normalises every factory profile to the same energy, but the ear weighs frequencies
unevenly, so bright cabinets sound louder than dark ones at the same energy. The estimate is
pink noise from 80 Hz to 10 kHz through the modelled cabinet and the ITU-R BS.1770 K-weighting,
on the same 400-point log grid as Loudness.kt; the correction turns every cabinet down to the
quietest non-marginal factory one and never up. The response model is cabrig_dsp.py's, inferred
rather than measured. Run: python3 tools/loudness.py [library.json ...] [--json out.json]
"""
import argparse
import importlib.util
import json
import math
from pathlib import Path

import numpy as np

root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('cabrig_dsp', root / 'tools/cabrig_dsp.py')
dsp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dsp)

RATE = 48000                                  # assumed, not measured
MARGINAL = {'7:5:0', '22:5:0', '22:5:1'}
STEP_DB = 24 / 127                            # one Cabinet Level step
Z = np.exp(-2j * np.pi * np.geomspace(80, 10000, 400) / RATE)


def biquad2(b, a):
    return np.abs((b[0] + b[1] * Z + b[2] * Z * Z) / (a[0] + a[1] * Z + a[2] * Z * Z)) ** 2


K2 = (biquad2([1.53512485958697, -2.69169618940638, 1.19839281085285], [1, -1.69065929318241, 0.73248077421585]) *
      biquad2([1, -2, 1], [1, -1.99004745483398, 0.99007225036621]))


def loudness_db(values):
    v = np.array(values, dtype=np.float32).astype(float)
    h = np.full_like(Z, v[0])
    for k in range(16):
        b0, b1, a1, a2 = v[1 + 4 * k:5 + 4 * k]
        h += (b0 + b1 * Z) / (1 + a1 * Z + a2 * Z * Z)
    return 10 * math.log10(float(np.sum(np.abs(h) ** 2 * K2) / np.sum(K2)))


def key(p):
    # the shipped library names its microphone in "mic" and keeps the index in "mic_index"
    return f"{p['cab']}:{p.get('mic_index', p.get('mic'))}:{p['axis']}"


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('extra', nargs='*', type=Path, help='more libraries, e.g. app/src/main/assets/cab_library.json')
    ap.add_argument('--json', type=Path)
    args = ap.parse_args()
    factory = json.loads((root / 'app/src/main/assets/cab_profiles.json').read_text())
    rows = [{'source': 'factory', 'id': key(p), 'db': loudness_db(dsp.decode(p))}
            for p in factory if key(p) not in MARGINAL]
    reference = min(r['db'] for r in rows)
    for path in args.extra:
        rows += [{'source': path.name, 'id': p.get('id', key(p)), 'db': loudness_db(dsp.decode(p))}
                 for p in json.loads(path.read_text())]
    for r in rows:
        r['correction_db'] = min(0.0, reference - r['db'])
        r['steps'] = min(0, round(r['correction_db'] / STEP_DB))
        r['levelled_db'] = r['db'] + r['steps'] * STEP_DB
    print(f'reference (quietest non-marginal factory profile): {reference:.3f} dB')
    for source in dict.fromkeys(r['source'] for r in rows):
        group = [r for r in rows if r['source'] == source]
        before = [r['db'] for r in group]
        after = [r['levelled_db'] for r in group]
        print(f'{source:20} n={len(group):3}  spread {max(before) - min(before):5.2f} dB -> {max(after) - min(after):4.2f} dB'
              f'  largest cut {min(r["correction_db"] for r in group):+.2f} dB')
    if args.json:
        args.json.write_text(json.dumps(rows, indent=1) + '\n')


if __name__ == '__main__':
    main()
