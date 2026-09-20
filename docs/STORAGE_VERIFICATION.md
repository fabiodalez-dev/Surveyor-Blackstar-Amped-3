# Hardware storage verification — 2026-09-20

Firmware 1.03, USB 27d4:0072. `tools/verify_slot_storage.py` was run against
the physical AMPED 3 with Architect closed. Before the first write it saved
all six slot names/data plus each slot's recalled live state to a new local
backup outside the repository (`backups/storage-verification-20260920.json`).

Results:

```
PASS six-slot backup persisted
PASS saved and recalled: amp 1 104 -> 103
Restored original amp 1
PASS saved and recalled: cab 3 89 -> 88
Restored original cab 3
PASS all six persistent slots restored byte-for-byte
PASS original live parameters restored
```

For each test the script changed one parameter, wrote the target slot,
recalled another slot, then recalled the target and checked the changed
value. It restored the original values and compared all six stored payloads
and names to the backup. Finally it restored the original active slots,
cabinet coefficients and unsaved live parameter state.

This verifies storage/recall through the Mac HID transport. It does not
constitute a physical power-cycle test or an Android USB transport test.

## Android changes

- Fixed `System.arraycopy(List<Int>, ByteArray)`, which cannot encode AMP data.
- Freeze the intended AMP/CabRig state before enqueueing a save.
- Read the destination slot afresh and fsync a backup before writing.
- Respect the observed name capacities (AMP 23, CabRig 21 ASCII characters).
- Wait for the CabRig AF acknowledgement.
- Reread name/data after saving. Verify all 84 CabRig bytes; AMP verification
  currently covers the first nine continuous parameters of the compact
  15-byte stored format. Do not claim verification of all AMP switches yet.
- Fail with an explicit unconfirmed-save status if backup, ACK or readback
  fails. A successful resynchronization alone is not a save confirmation.

The pre-save backup is included in library exports. It preserves the raw
stored representation; there is not yet an automated Android restore flow
for every part of that representation. Do not delete backups after failure.

## Repeat procedure

1. Close Architect and other applications owning the HID device.
2. Use the existing `research/venv` containing `hidapi`, or an isolated Python
   environment with `hidapi` installed.
3. Choose a fresh backup path. Never overwrite an earlier recovery snapshot.
4. Run `python tools/verify_slot_storage.py /absolute/fresh-backup.json
   --exercise`. The default without `--exercise` reads all slots and restores
   the initial live state, without writing slot memory.
5. Keep the USB cable connected through restoration. Confirm every PASS line.
6. To complete end-to-end validation, repeat on Android and physically power
   cycle the amp before checking that the saved target is retained.

Do not interrupt the experiment. If any assertion fails, retain the backup
and inspect the live/stored state before further writes. A failed readback
must never be interpreted as proof that a write did not occur.
