#!/usr/bin/env python3
"""Offline checks for cabrig_dsp.py. Run: python3 tools/test_cabrig_dsp.py

Covers the stability rule, which is subtle: three factory profiles quantise to a
real pole at exactly z=1 (1+a1+a2 == 0 in float32), cancelled by the section's own
zero there. The rule must accept those while still refusing anything that leaves a
profile less stable than it started.
"""
import importlib.util
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('cabrig_dsp', root / 'tools/cabrig_dsp.py')
dsp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dsp)
library = json.loads((root / 'app/src/main/assets/cab_profiles.json').read_text())
MARGINAL = [(7, 5, 0), (22, 5, 0), (22, 5, 1)]
failures = []


def profile(key):
    return next(p for p in library if (p['cab'], p['mic'], p['axis']) == key)


def check(name, condition):
    print(('PASS ' if condition else 'FAIL ') + name)
    if not condition:
        failures.append(name)


def raises(template, values):
    try:
        dsp.encode(template, values)
        return False
    except AssertionError:
        return True


check('every profile decodes and passes both checksum layers',
      all(len(dsp.decode(p)) == 65 for p in library))
check('library holds the full 24x6x2 matrix',
      {(p['cab'], p['mic'], p['axis']) for p in library} ==
      {(c, m, a) for c in range(24) for m in range(6) for a in range(2)})
check('276 distinct payloads, a few choices share data',
      len({tuple(dsp.decode(p)) for p in library}) == 276)

for key in MARGINAL:
    template = profile(key)
    check(f'marginal factory profile {key} re-encodes',
          dsp.encode(template, dsp.decode(template))['chunks'] is not None)

template = profile((21, 5, 0))
values = dsp.decode(template)
values[3], values[4] = -3.0, 2.0                 # roots at z=1 and z=2
check('clearly unstable coefficients are refused', raises(template, values))
values = dsp.decode(template)
values[3], values[4] = -2.001, 1.001001          # radius just above 1
check('slightly unstable coefficients are refused', raises(template, values))
marginal = profile((22, 5, 0))
values = dsp.decode(marginal)
values[3], values[4] = -2.001, 1.001001
check('a marginal template does not license making it worse', raises(marginal, values))

payload_of = lambda p: b''.join(bytes.fromhex(c)[4:4 + bytes.fromhex(c)[3]] for c in p['chunks'])
check('re-encoding preserves the payload byte-for-byte',
      payload_of(dsp.encode(marginal, dsp.decode(marginal))) == payload_of(marginal))

print(f"\n{len(failures)} failed" if failures else "\nall checks passed")
sys.exit(1 if failures else 0)
