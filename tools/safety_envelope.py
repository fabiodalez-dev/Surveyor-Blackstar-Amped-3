#!/usr/bin/env python3
"""Empirical safety envelope of the 288 factory CabRig profiles. Offline only.

Every metric is computed under the parallel second-order model of cabrig_dsp.py
(a hypothesis, not a measured fact) on the float32 values the hardware receives.
The point is a box that Blackstar's own data sits inside: a generated profile is
accepted only if it sits inside the same box, so it can do nothing the factory
data does not already do. Run: python3 tools/safety_envelope.py [--json out.json]
"""
import argparse
import importlib.util
import json
import math
from pathlib import Path

import numpy as np
from scipy.signal import lfilter

root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('cabrig_dsp', root / 'tools/cabrig_dsp.py')
dsp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dsp)

RATE = 48000                    # assumed, not measured
IR_SECONDS = 8                  # long enough for a pole at radius 0.99998 (-60 dB in ~3.4 s)
# Union grid: linear (Parseval-friendly energy) plus a log tail down to 0.01 Hz so that
# resonators a few hertz from DC are actually sampled at their peak.
LINEAR = np.fft.rfftfreq(1 << 17, 1 / RATE)[1:]
GRID = np.unique(np.concatenate([LINEAR, np.geomspace(0.01, 200, 4000)]))   # never exactly 0 Hz


def db(x):
    return 20 * math.log10(max(float(x), 1e-12))


def section_response(b0, b1, a1, a2, f):
    z = np.exp(-2j * np.pi * f / RATE)
    return (b0 + b1 * z) / (1 + a1 * z + a2 * z * z)


def dc_gain(b0, b1, a1, a2):
    """Signed DC gain of one section. A pole exactly on z=1 whose zero is not exactly there
    (the three marginal factory profiles: b0+b1 ~ 1e-9, not 0) is an integrator: infinite."""
    den = 1 + a1 + a2
    if den == 0:
        return math.inf if b0 + b1 != 0 else b0 / (1 - a2)
    return (b0 + b1) / den


def analyse(values):
    v = np.array(values, dtype=np.float32).astype(float)
    b0, b1, a1, a2 = v[1::4], v[2::4], v[3::4], v[4::4]
    poles = np.array(dsp.poles(list(v)))
    radius = np.abs(poles).reshape(16, 2).max(1)
    dc_margin = 1 + a1 + a2                     # |1 - p|^2 for a complex pair; 0 means a pole at z=1
    ny_margin = 1 - a1 + a2                     # same at z = -1
    marginal = np.where(dc_margin == 0)[0]
    dc = v[0] + sum(dc_gain(b0[k], b1[k], a1[k], a2[k]) for k in range(16))

    parts = np.array([section_response(b0[k], b1[k], a1[k], a2[k], GRID) for k in range(16)])
    H = v[0] + parts.sum(0)
    mag = np.abs(H)
    peak = int(np.argmax(mag))
    on_linear = np.isin(GRID, LINEAR)
    rms = float(np.sqrt(np.mean(mag[on_linear] ** 2)))    # = sqrt(sum h^2) by Parseval
    audio = (GRID >= 20) & (GRID <= 20000)
    section_peak = np.abs(parts).max(1)

    # Impulse response by direct recursion of each section, float64, then the same
    # recursion in float32 to see what the numerics of the hardware's own precision do.
    n = RATE * IR_SECONDS
    x = np.zeros(n); x[0] = 1
    h = v[0] * x
    for k in range(16):
        h += lfilter([b0[k], b1[k]], [1, a1[k], a2[k]], x)
    h32 = (v[0] * x).astype(np.float32)
    for k in range(16):
        h32 += lfilter(np.array([b0[k], b1[k]], np.float32), np.array([1, a1[k], a2[k]], np.float32),
                       x.astype(np.float32)).astype(np.float32)
    env = np.maximum.accumulate(np.abs(h)[::-1])[::-1]   # decay envelope: max |h[k]| for k >= n
    hpeak = float(np.abs(h).max())
    below = np.where(env < hpeak * 1e-3)[0]
    t60 = below[0] / RATE if len(below) else float('inf')
    below40 = np.where(env < hpeak * 1e-2)[0]
    t40 = below40[0] / RATE if len(below40) else float('inf')

    return {
        'coef_abs_max': float(np.abs(v).max()),
        'v0': float(v[0]),
        'b_abs_max': float(max(np.abs(b0).max(), np.abs(b1).max())),
        'a2_min': float(a2.min()), 'a2_max': float(a2.max()),
        'a1_abs_max': float(np.abs(a1).max()),
        'pole_radius_max': float(radius.max()),
        'dc_margin_min': float(dc_margin.min()),
        'ny_margin_min': float(ny_margin.min()),
        'sections_on_unit_circle': int(len(marginal)),
        'peak_db': db(mag[peak]), 'peak_hz': float(GRID[peak]),   # over 0.01 Hz..Nyquist, DC excluded
        'peak_audio_db': db(mag[audio].max()),
        'dc_db': db(abs(dc)),
        'gain_20hz_db': db(np.interp(20, GRID, mag)),
        'gain_20khz_db': db(np.interp(20000, GRID, mag)),
        'nyquist_db': db(mag[on_linear][-1]),
        'rms_db': db(rms),
        'crest_db': db(mag[peak]) - db(rms),
        'section_peak_max_db': db(section_peak.max()),
        'section_sum_over_peak_db': db(section_peak.sum()) - db(mag[peak]),
        'ir_peak': hpeak,
        'ir_l1': float(np.abs(h).sum()),
        'ir_energy_db': db(math.sqrt(float((h * h).sum()))),
        'ir_sum': float(h.sum()),
        'ir_t40_s': t40, 'ir_t60_s': t60,
        'ir_tail_100ms_db': db(math.sqrt(float((h[RATE // 10:] ** 2).sum()))),
        'ir_f32_vs_f64_db': db(np.abs(h32 - h).max() / hpeak),
        'ir_f32_end_abs': float(np.abs(h32[-RATE:]).max()),
    }


METRICS = [
    ('coef_abs_max', 'largest |coefficient| in the payload'),
    ('b_abs_max', 'largest |b0| or |b1|'),
    ('a2_min', 'smallest a2 (min pole radius^2)'),
    ('a2_max', 'largest a2 (max pole radius^2)'),
    ('a1_abs_max', 'largest |a1|'),
    ('pole_radius_max', 'max pole radius after float32'),
    ('dc_margin_min', 'min 1+a1+a2 (0 = pole on z=1)'),
    ('ny_margin_min', 'min 1-a1+a2 (pole distance^2 from z=-1)'),
    ('peak_db', 'peak |H| over 0.01 Hz..Nyquist, dB'),
    ('peak_hz', 'frequency of that peak, Hz'),
    ('peak_audio_db', 'peak |H| within 20 Hz..20 kHz, dB'),
    ('dc_db', '|H| at exactly DC, dB (inf = integrator)'),
    ('gain_20hz_db', '|H| at 20 Hz, dB'),
    ('gain_20khz_db', '|H| at 20 kHz, dB'),
    ('nyquist_db', '|H| at Nyquist, dB'),
    ('rms_db', 'RMS |H| over 0..Nyquist (= IR energy), dB'),
    ('crest_db', 'peak minus RMS, dB (resonance)'),
    ('section_peak_max_db', 'largest single-section peak gain, dB'),
    ('section_sum_over_peak_db', 'sum of section peaks over |H| peak, dB (cancellation)'),
    ('ir_peak', 'IR peak |sample|'),
    ('ir_l1', 'IR L1 norm (worst-case bounded-input gain)'),
    ('ir_energy_db', 'IR energy sqrt(sum h^2), dB'),
    ('ir_sum', 'IR sum (= H at DC, signed)'),
    ('ir_t40_s', 'IR time to fall 40 dB below peak, s'),
    ('ir_t60_s', 'IR time to fall 60 dB below peak, s'),
    ('ir_tail_100ms_db', 'IR energy after 100 ms, dB'),
    ('ir_f32_vs_f64_db', 'float32 vs float64 recursion, max error re peak, dB'),
    ('ir_f32_end_abs', 'float32 IR max |sample| in last second'),
]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('library', nargs='?', type=Path, default=root / 'app/src/main/assets/cab_profiles.json')
    p.add_argument('--json', type=Path)
    args = p.parse_args()
    library = json.loads(args.library.read_text())
    rows = []
    for profile in library:
        key = f"{profile['cab']}:{profile['mic']}:{profile['axis']}"
        rows.append({'key': key, **analyse(dsp.decode(profile))})
    print(f'{len(rows)} profiles, {IR_SECONDS} s IR at {RATE} Hz (assumed)\n')
    print(f'{"metric":26} {"min":>12} {"median":>12} {"p99":>12} {"max":>12}  argmax   description')
    for name, text in METRICS:
        col = np.array([r[name] for r in rows])
        worst = rows[int(np.argmax(col))]['key']
        finite = col[np.isfinite(col)]
        p99 = np.percentile(finite, 99) if len(finite) else math.nan
        print(f'{name:26} {col.min():12.6g} {np.median(col):12.6g} {p99:12.6g} '
              f'{col.max():12.6g}  {worst:8} {text}' + (f' [{len(col) - len(finite)} inf]' if len(finite) < len(col) else ''))
    print('\nmarginal (pole on z=1):', [r['key'] for r in rows if r['sections_on_unit_circle']])
    print('peak above 20 kHz or below 20 Hz:',
          [(r['key'], round(r['peak_hz'], 1), round(r['peak_db'], 1)) for r in rows
           if not 20 <= r['peak_hz'] <= 20000])
    if args.json:
        args.json.write_text(json.dumps(rows, indent=1) + '\n')


if __name__ == '__main__':
    main()
