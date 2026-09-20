#!/usr/bin/env python3
"""Offline CabRig coefficient research. This tool never opens a USB device.

Decode/encode is verified against 288 captured profiles. The parallel filter
response model is a hypothesis supported by Architect's coefficient transform;
it still needs an audio transfer-function measurement against the hardware.
"""
import argparse
import binascii
import cmath
import json
import math
import struct
from pathlib import Path


def crc(data):
    return binascii.crc_hqx(data, 0)


def report(opcode, payload):
    assert 0 < len(payload) <= 60
    return (bytes([opcode]) + crc(payload).to_bytes(2, 'big') +
            bytes([len(payload)]) + payload).ljust(64, b'\0')


def unwrap(encoded, opcode):
    b = bytes.fromhex(encoded)
    assert len(b) == 64 and b[0] == opcode and 0 < b[3] <= 60
    payload = b[4:4+b[3]]
    assert int.from_bytes(b[1:3], 'big') == crc(payload), 'bad report CRC'
    return payload


def decode(profile):
    header = unwrap(profile['header'], 0xaa)
    blocks = [unwrap(s, 0xac) for s in profile['chunks']]
    assert [len(b) for b in blocks] == [60, 60, 60, 60, 20]
    data = b''.join(blocks)
    assert len(header) == 12
    assert int.from_bytes(header[2:4], 'little') == len(data) == 260
    assert int.from_bytes(header[:2], 'little') == crc(data), 'bad full payload CRC'
    assert int.from_bytes(header[4:6], 'little') == len(blocks)
    values = list(struct.unpack('<65f', data))
    assert all(math.isfinite(v) for v in values)
    return values


def poles(values):
    return [p for i in range(1, 65, 4) for p in (
        (-values[i+2] + cmath.sqrt(values[i+2]**2 - 4*values[i+3]))/2,
        (-values[i+2] - cmath.sqrt(values[i+2]**2 - 4*values[i+3]))/2)]


def encode(template, values):
    assert len(values) == 65 and all(math.isfinite(v) for v in values)
    data = struct.pack('<65f', *values)
    quantized = list(struct.unpack('<65f', data))
    # Three factory profiles (7:5:0, 22:5:0, 22:5:1) quantise to 1+a1+a2 == 0 in one
    # section: a real pole at exactly z=1, cancelled by that section's own zero there.
    # A flat "< 1" rejects coefficient sets the hardware itself ships, so the rule is
    # not to leave the source profile less stable than it already was.
    ceiling = max(1.0, max(map(abs, poles(decode(template)))))
    radius = max(map(abs, poles(quantized)))
    assert radius <= ceiling, f'unstable quantized poles: {radius:.9f} > {ceiling:.9f}'
    header = bytearray(unwrap(template['header'], 0xaa))
    header[:2] = crc(data).to_bytes(2, 'little')
    header[2:4] = len(data).to_bytes(2, 'little')
    chunks = [report(0xac, data[i:i+60]).hex() for i in range(0, len(data), 60)]
    header[4:6] = len(chunks).to_bytes(2, 'little')
    return {**template, 'header': report(0xaa, header).hex(), 'chunks': chunks}


def response(values, frequencies):
    """Hypothesis: direct + sum((b0+b1*z^-1)/(1+a1*z^-1+a2*z^-2))."""
    import numpy as np
    z = np.exp(-2j*np.pi*np.asarray(frequencies))
    result = np.full(z.shape, values[0], dtype=complex)
    for i in range(1, 65, 4):
        b0, b1, a1, a2 = values[i:i+4]
        result += (b0+b1*z)/(1+a1*z+a2*z*z)
    return result


def fit_ir(template, sample_rate, samples):
    """Fit 33 real numerator weights on the template's fixed stable poles.

    A fit approximates an IR under the model; it does not upload WAV samples.
    Long delay/reverb cannot generally be reproduced by this fixed pole bank.
    """
    import numpy as np
    values = decode(template)
    nfft = max(8192, 1 << (len(samples)-1).bit_length())
    target = np.fft.rfft(samples, nfft)
    f = np.fft.rfftfreq(nfft)
    z = np.exp(-2j*np.pi*f)
    columns = [np.ones(z.shape, dtype=complex)]
    for i in range(1, 65, 4):
        denominator = 1+values[i+2]*z+values[i+3]*z*z
        columns += [1/denominator, z/denominator]
    basis = np.column_stack(columns)
    # Complex least squares with real coefficients; no unsupported DSP code.
    matrix = np.concatenate([basis.real, basis.imag])
    goal = np.concatenate([target.real, target.imag])
    weights, _, _, _ = np.linalg.lstsq(matrix, goal, rcond=1e-10)
    values[0] = float(weights[0])
    for i in range(16):
        values[1+4*i:3+4*i] = [float(v) for v in weights[1+2*i:3+2*i]]
    result = encode(template, values)
    predicted = response(decode(result), f)
    relative_error = float(np.linalg.norm(predicted-target)/max(np.linalg.norm(target), 1e-12))
    peak = float(np.max(np.abs(predicted)))
    return result, {'sample_rate_assumed': sample_rate, 'relative_complex_fit_error': relative_error,
                    'predicted_peak_gain_db': 20*math.log10(max(peak, 1e-12)),
                    'hardware_audio_validation': False}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('library', type=Path)
    p.add_argument('--key', default='21:5:0')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--attenuate-db', type=float)
    p.add_argument('--fit-ir', type=Path)
    p.add_argument('--sample-rate', type=int, default=48000, help='Model assumption, not detected hardware rate')
    args = p.parse_args()
    library = json.loads(args.library.read_text())
    template = next(x for x in library if f"{x['cab']}:{x['mic']}:{x['axis']}" == args.key)
    values = decode(template)
    metadata = {'hardware_audio_validation': False, 'model': 'parallel second-order sections (hypothesis)'}
    if args.fit_ir:
        import numpy as np
        from scipy.io import wavfile
        rate, samples = wavfile.read(args.fit_ir)
        assert samples.ndim == 1, 'Supply a mono WAV impulse response'
        assert rate == args.sample_rate, 'Resample explicitly to the assumed model rate first'
        if np.issubdtype(samples.dtype, np.integer):
            if samples.dtype == np.uint8: samples = (samples.astype(float)-128)/128
            else: samples = samples.astype(float)/max(abs(np.iinfo(samples.dtype).min), np.iinfo(samples.dtype).max)
        assert len(samples) > 0 and np.all(np.isfinite(samples))
        result, metadata = fit_ir(template, rate, samples)
    else:
        if args.attenuate_db is not None:
            assert -60 <= args.attenuate_db <= 0, 'Only attenuation is supported by this experiment'
            scale = 10**(args.attenuate_db/20)
            values[0] *= scale
            for i in range(1, 65, 4):
                values[i] *= scale
                values[i+1] *= scale
        result = encode(template, values)
    result['research'] = metadata
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({'output': str(args.output), 'max_pole_radius': max(map(abs, poles(decode(result)))), **metadata}, indent=2))


if __name__ == '__main__':
    main()
