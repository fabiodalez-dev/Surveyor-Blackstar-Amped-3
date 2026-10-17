#!/usr/bin/env python3
"""Brand, size, speaker, microphone and source of every shipped cabinet, for the app's filters.

The library's own fields are free text copied from wherever each file came from: a Tone3000 tone
title and its gear list, a pack's folder names, a file name such as "M25 UL 1960TV 4x12 SM57
1.75in". This script reads that text and writes a `facets` object into each entry of
`app/src/main/assets/cab_library.json`, with values from a small fixed vocabulary, so the app can
filter on them without parsing anything. Run it again after `LibraryBuildHarness` rebuilds the
asset; it only reads the other fields, so running it twice changes nothing.

What the text does not say is left empty rather than guessed. A few entries are settled by hand
in OVERRIDES, each from the source's own description (the Tone3000 tone page or the pack's
readme); the comment beside each says what it rests on.

Run: python3 tools/cab_facets.py [--check]   (--check fails if the asset is not up to date)
"""
import argparse
import json
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
ASSET = root / 'app/src/main/assets/cab_library.json'

# brand: first pattern that matches the cabinet text wins, so the specific ones come first
BRANDS = [
    ('EVH', r'\bevh\b|5150\s?iii|g12-evh'),
    ('Mesa', r'mesa|boogie|recto|rectifier|road ?king|lone ?star|\bos 4x12'),
    ('Marshall', r'marshall|\b1960|jcm ?800|jcm ?900|plexi|\b1959|m25 |\bavt'),
    ('Fender', r'fender|deluxe|twin reverb|bassman|tweed|blackface|princeton'),
    ('Orange', r'orange|ppc ?412|\borng\b|o412'),
    ('Vox', r'\bvox\b|ac ?30'),
    ('Bogner', r'bogner|uber'),
    ('ENGL', r'\bengl\b'),
    ('Friedman', r'friedman'),
    ('Two-Rock', r'two.?rock'),
    ('Hiwatt', r'hiwatt|whowatt'),
    ('Ampeg', r'ampeg|\bsvt\b'),
    ('Roland', r'roland|jc-?120|jazz chorus'),
    ('Peavey', r'peavey|6505'),
    ('Matamp', r'matamp'),
    ('Framus', r'framus'),
    ('Behringer', r'behringer'),
    ('Driftwood', r'driftwood'),
]

# speaker families: the label the filter shows, and what it is written as in the sources
SPEAKERS = [
    ('Vintage 30', r'vintage ?30|\bv30\b|\bv30s\b'),
    ('Greenback', r'greenback|g12m\b|g12m-?25|\bm25\b|g12h\b|g12-?h\b|pulsonic|\b25w\b'),
    ('G12H30', r'g12h ?30|h30\b'),
    ('G12T-75', r'g12 ?t-?75|\bt75\b|g12t75'),
    ('G12-65', r'g12-?65'),
    ('G12-EVH', r'g12-?evh|evh 20w|evh celestion'),
    ('Redback', r'redback'),
    ('Creamback', r'creamback'),
    ('Alnico Blue', r'alnico blue|t530|\bblue\b.*alnico|vox ac30 blue'),
    ('Jensen', r'jensen|p12n|c12k|c12n|p10r'),
    ('Oxford', r'oxford'),
    ('Fane', r'\bfane\b'),
    ('CTS', r'\bcts\b'),
    ('JBL D120', r'\bjbl\b|d120'),
    ('Eminence DV-77', r'dv-?77'),
    ('Rockdriver', r'rockdriver'),
    ('Swamp Thang', r'swamp thang'),
    ('Black Shadow', r'black shadow'),
    ('Karnivore', r'karnivore'),
    ('Omega', r'vm ?12-?65|omega'),
    ('C90', r'\bc90\b'),
    ('Kappalite', r'kappalite'),
]

# microphones: file names abbreviate them, so the patterns are generous
MICS = [
    ('SM57', r'sm ?57|\b57\b|beta ?57'),
    ('SM58', r'sm ?58|\b58\b'),
    ('MD421', r'md ?421|\b421\b'),
    ('e906', r'e ?906|\b906\b'),
    ('e606', r'e ?606'),
    ('e609', r'e ?609'),
    ('R121', r'r ?-?121|\b121\b'),
    ('M160', r'm ?160'),
    ('M201', r'm ?201'),
    ('C414', r'c ?414|\b414\b'),
    ('U87', r'u ?87'),
    ('U47', r'u ?47'),
    ('i5', r'audix i5|\bi5\b'),
    ('RE20', r're ?20'),
    ('PR30', r'pr ?30'),
    ('Beta 52', r'beta ?52'),
    ('KSM313', r'ksm ?313'),
    ('VR2', r'\bvr2\b'),
    ('V7X', r'\bv7x\b'),
    ('RB500', r'rb ?500'),
    ('PDMIC75', r'pdmic ?75'),
    ('RM700', r'rm ?700'),
    ('C636', r'c ?636'),
    ('AT250', r'at ?250'),
]

SIZES = r'\b([1248])\s?x\s?(10|12|15)\b'
SIZE_BY_MODEL = [  # cabinets everybody knows the size of, when the text does not say it
    ('2x12', r'twin reverb|ac ?30|jc-?120|jazz chorus'),
    ('1x12', r'deluxe reverb|65 deluxe|lone ?star'),
    ('4x12', r'1960|ppc ?412|o412|road ?king|412|rectifier|recto|oversized|\bos\b|e412|uber|avt'),
    ('2x15', r'bassman 2x15|science 215'),
    ('8x10', r'8x10'),
]

# a whole-rig mix named after a record: no single brand, size, speaker or microphone
MIX = {'brand': 'Mix', 'size': '', 'speakers': [], 'mics': []}

OVERRIDES = {
    # one file of a tone whose gear list names several cabinets: the file title names the one used
    't3k-79467-688267': {'brand': 'Framus', 'size': '4x12', 'speakers': ['Greenback']},
    't3k-79467-688268': {'brand': 'Orange', 'size': '4x12', 'speakers': ['Vintage 30']},
    't3k-79467-688269': {'brand': 'Fender', 'size': '1x12', 'speakers': ['Jensen']},
    't3k-84863-721115': {'brand': 'Marshall', 'size': '4x12', 'speakers': ['Vintage 30']},
    't3k-84863-721116': {'brand': 'ENGL', 'size': '4x12', 'speakers': ['Vintage 30']},
    't3k-84863-721057': {'brand': 'Mesa', 'size': '4x12', 'speakers': ['Vintage 30']},
    # Tone3000 page: "2000s Mesa oversized straight cab ... SM57 ... M160"
    't3k-1612-81565': {'mics': ['SM57', 'M160']},
    # TJ3 pack: 70ORNG412 H30 is a 1970 Orange 4x12 with G12H30s, mic 121 then 545
    't3k-82871-710024': {'brand': 'Orange', 'size': '4x12', 'speakers': ['G12H30'], 'mics': ['R121']},
    # Will Putney album blends and the song-named Metallica/SOAD files are whole-rig mixes
    't3k-47291-258998': MIX, 't3k-47291-259016': MIX, 't3k-57989-336773': MIX, 't3k-57989-336865': MIX,
    't3k-57989-336926': MIX, 't3k-57989-336988': MIX, 't3k-49430-272608': MIX, 't3k-83759-715609': MIX,
    't3k-62933-499030': MIX, 't3k-4685-69637': MIX,
    # Marshall's own spec: the 1960AV and 1960BV are loaded with Vintage 30s; the file titles say
    # which of the BV tone's two speakers each one is
    't3k-87072-737681': {'speakers': ['Vintage 30']},
    't3k-27221-105345': {'speakers': ['Vintage 30']},
    't3k-51086-284261': {'speakers': ['G12T-75']},
    't3k-51086-284295': {'speakers': ['Vintage 30']},
    # Tone3000 tone "Vintage Warmth": a Science 2x15 bass cabinet with Kappalite 3015s
    't3k-67574-473712': {'brand': 'Science', 'size': '2x15', 'speakers': ['Kappalite'], 'mics': ['SM57', 'RE20']},
    # Tone3000 tone page: Peavey 6505 into a DIY 4x12 with V30 and Rockdriver
    't3k-88597-801885': {'brand': 'Peavey', 'size': '4x12', 'speakers': ['Vintage 30', 'Rockdriver']},
    # "IR" by Metal Band Tones: file is named Engl CLEAN
    't3k-62933-372249': {'brand': 'ENGL', 'size': '4x12'},
    # the 6505 "BS1" capture is a Peavey head into an unnamed 4x12
    'orig-6505-bs1-421-api': {'brand': 'Peavey'},
    # Live Ready Sound file names: "Red 609" is an e609, "EVH 57" an SM57 on an EVH speaker
    'lrs-lrs-red-609-2pc': {'mics': ['e609']},
    'lrs-lrs-evh-57-2pc': {'brand': 'EVH', 'speakers': ['G12-EVH'], 'mics': ['SM57']},
    'lrs-lrs-jsn-c12k-1pc': {'speakers': ['Jensen']},
    # Tone Factor TJ3 / XR packs say the mic blend in the file title: "421 U5 M160 70-30"
    't3k-90730-762694': {'mics': ['MD421', 'M160']},
    # the Driftwood tone: Driftwood 4x12 with V30, AT250DE in the file title
    't3k-89673-755461': {'brand': 'Driftwood', 'size': '4x12', 'speakers': ['Vintage 30'], 'mics': ['AT250']},
    # "Marshall Box - 57 A": an SM57 on a 1960 with G12M
    't3k-28968-118340': {'mics': ['SM57']},
    # TF MESA ST 4X12A V30 57: SM57
    't3k-79857-762812': {'mics': ['SM57']},
}

BASS = {'Ampeg', 'Science'}


def source_of(e):
    if e.get('kind') == 'designed':
        return 'Progettate'
    if 'tone3000.com' in e.get('url', ''):
        return 'Tone3000'
    if e.get('license') == 'CC0 1.0':
        return 'CC0'
    return 'Collezione'


def hits(table, text):
    return [label for label, pattern in table if re.search(pattern, text)]


def facets(e):
    # the file title (kept in `mic` for Tone3000 files) and the entry name describe this file;
    # the cabinet field is the tone's gear list and can name gear that is not in this file
    own = ' '.join([e['name'], e['mic'], e['speaker']]).lower()
    gear = ' '.join([e['cabinet'], e['pack']]).lower()
    both = own + ' ' + gear
    f = {}
    brand = next((b for b, p in BRANDS if re.search(p, own)), None) or next((b for b, p in BRANDS if re.search(p, gear)), '')
    f['brand'] = 'Blackstar' if e.get('kind') == 'designed' else brand
    m = re.search(SIZES, own) or re.search(SIZES, gear)
    f['size'] = f'{m.group(1)}x{m.group(2)}' if m else next((s for s, p in SIZE_BY_MODEL if re.search(p, both)), '')
    if e.get('kind') == 'designed':
        m = re.search(SIZES, e['cabinet'].lower())
        f['size'] = f'{m.group(1)}x{m.group(2)}' if m else ''
    f['speakers'] = hits(SPEAKERS, own) or hits(SPEAKERS, gear)
    f['mics'] = [] if e.get('kind') == 'designed' else hits(MICS, own)
    f.update(OVERRIDES.get(e['id'], {}))
    f['speakers'] = f['speakers'][:3]
    f['source'] = source_of(e)
    f['bass'] = f['brand'] in BASS or ('bassman' in both and f['size'] == '2x15')
    return f


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true')
    ap.add_argument('--table', action='store_true', help='print what was derived, one line per cabinet')
    a = ap.parse_args()
    lib = json.loads(ASSET.read_text())
    changed = 0
    for e in lib:
        f = facets(e)
        if e.get('facets') != f:
            changed += 1
            e['facets'] = f
        if a.table:
            print(f"{e['id'][:28]:28} {f['brand']:10} {f['size']:5} {','.join(f['speakers'])[:30]:30} {','.join(f['mics'])[:22]:22} {f['source']:10} {'BASS' if f['bass'] else ''}")
    if a.check:
        sys.exit(1 if changed else 0)
    if changed:
        ASSET.write_text(json.dumps(lib, indent=1, ensure_ascii=False) + '\n')
    print(f'{changed} of {len(lib)} entries updated', file=sys.stderr)


if __name__ == '__main__':
    main()
