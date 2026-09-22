# G-one wearable protocol

The single description of what the wearable and the phone say to each other. The firmware
(`firmware/g_one_wearable/g_one_wearable.ino`), the app's parser
(`AsciiKeyValuePacketParser`), its sync logic (`BacklogAckTracker`, `BleUartVitalsSource`)
and the PC emulator (`tools/wearable-emulator/emulate.py`) all follow this file. Change it
first.

**The current firmware sends live only.** The build has no SD card, so it uses *Live* below,
`T:` and the status bits, and nothing else: no stored records, no catch-up, and it ignores `ACK`
and `STOP`. A reading taken while the phone is out of range is lost. The app implements the
whole protocol, so a wearable that keeps readings on a card works with it unchanged.

## Transport

- Bluetooth LE, the HM-10 serial profile: service `FFE0`, characteristic `FFE1`.
- Wearable → phone: notifications on `FFE1`. The phone requests an MTU of 185, so a line
  usually fits one notification, but lines may be split across notifications or several
  lines may share one. The phone reassembles on `\n`.
- Phone → wearable: writes (without response) to `FFE1`, one command per write, ending
  in `\n`.
- Advertising name: `G-one Wearable`, with `FFE0` in the advertisement.

## Wearable → phone: lines

ASCII, one record per line, `KEY:VALUE` fields separated by commas, ending in `\n` (a
trailing `\r` is ignored). Field order does not matter. Unknown keys are ignored, so new
fields never break an older app. A field that is missing means "not measured", never zero.

### Reading fields

| Key       | Meaning                                                       | Accepted by the app |
|-----------|---------------------------------------------------------------|---------------------|
| `HR`      | Heart rate, beats per minute                                  | 20–300              |
| `SPO2`    | Blood oxygen saturation, %                                    | 50–100              |
| `STEMP`   | Skin temperature, °C. Not core body temperature               | 20–42               |
| `TEMP`    | Core body temperature, °C (not sent by this hardware)         | 25–45               |
| `MOT`     | Peak acceleration magnitude since the previous line, g        | 0–16                |
| `EMG`     | Mean EMG level since the previous line, ADC counts            | 0–4095              |
| `EMGPK`   | Highest EMG level since the previous line, ADC counts         | `EMG`–4095          |
| `EMGBITS` | ADC resolution of `EMG`/`EMGPK`; must be `12`                 | 12                  |
| `ST`      | Sensor status bitmask, hex (bits below)                       | 0–FFFF              |
| `TS`      | Epoch milliseconds when the reading was taken                 | see *Time*          |

Status bits (`ST`), matching `WearableStatus` in the app:

| Bit    | Meaning                         |
|--------|---------------------------------|
| `0x01` | Pulse oximeter found            |
| `0x02` | Skin contact (see below)        |
| `0x04` | Skin thermometer found          |
| `0x08` | Motion sensor found             |
| `0x10` | EMG signal looks connected      |
| `0x20` | SD card working                 |
| `0x40` | Clock set by the phone          |

Skin contact comes from the pulse sensor. A MAX30102 judges it from its infrared level. The
MAX30100 library has no such signal, so on that sensor the bit means a pulse is being found,
held for 3 seconds so it does not flicker between beats. It is therefore clear for the first
few seconds after the sensor is put on, until the library locks on to the pulse.

`HR` from a MAX30100 is sent only when the library's own beats back it up: five beats found,
the newest within 2.5 seconds, and the library's rate within 20 % of the rate those beats imply.
On the bench the library's detector stuck after the board was knocked and went on reporting
0.3 BPM with nothing on the sensor; after a real pulse it would repeat that pulse indefinitely.
`SPO2` is sent only alongside a valid `HR`, since the library computes it from the same beats.

A reading field is sent only when the sensor measured it recently: a DS18B20 or MPU6050 that
has not given a good reading for 5 seconds has its field left out rather than repeated.

### Sync fields

| Key    | On                  | Meaning |
|--------|---------------------|---------|
| `BUF`  | stored records      | `1`: this record comes from the SD card, not from this moment. |
| `SEQ`  | stored records      | The record's number. Numbers only go up, by one per record, and survive restarts. |
| `LOST` | stored records      | `LOST:<k>` with `SEQ:<n>`: records `n-k+1` … `n` exist but cannot be delivered (unreadable, or taken before a restart and never placed in time). The line carries no reading. |
| `PEND` | status lines        | Records stored on the wearable that the phone has not yet acknowledged. |
| `LAST` | status lines        | The newest record number stored on the wearable. |

## Phone → wearable: commands

| Command         | Meaning |
|-----------------|---------|
| `T:<epoch ms>`  | Sets the wearable's clock. Sent on every connection, and again while live lines report `ST` without bit `0x40`. |
| `ACK:<n>`       | The phone has **stored** every record up to and including `n`. The wearable may delete them. |
| `STOP`          | The wearer stopped monitoring in the app. Until the app connects again, the wearable records nothing. |

The wearable ignores an `ACK` lower than one it already has, and an `ACK` higher than the
newest record it has sent.

**Why `STOP` exists.** Without it the wearable cannot tell a deliberate stop from walking
out of range: both are just a dropped link. It would record the whole time monitoring was
off and replay it on the next connection. The app sends `STOP` only when monitoring is
stopped, never when the link drops, and waits 300 ms for it to leave the phone before
closing the link. Records already stored before the stop are kept and delivered as usual.
The pause ends when the app connects again, or when the wearable restarts; there is no
separate start command, because connecting is what starting monitoring does. A wearable
that misses the `STOP` just records the gap, which costs card space, never a reading.

## Time

The wearable has no battery-backed clock.

- **After the phone sets the clock:** each line carries `TS`.
- **Before that (after a restart, away from the phone):** records are still stored, with the
  uptime and a boot number instead of `TS`. These stay on the SD card and never go over
  the air. When the phone sets the clock during that same boot, the wearable converts them
  to `TS` as it replays them.
- **From an earlier boot that never got the time:** these cannot be placed in time. They are
  delivered as `LOST` records, so the phone knows they existed.

The app trusts `TS` only if it is at most 2 minutes ahead of the phone's clock and at most
30 days old. A live line with no trusted `TS` is stamped with its arrival time. A stored
record (`BUF:1`) with no trusted `TS` has no reading the app can use: it is acknowledged, so
it does not block the sync, and counted as rejected.

## The two modes

### Live

When nothing is waiting on the SD card, the wearable sends each line as it is measured,
twice a second, with no `BUF`, `SEQ` or `PEND`:

```
HR:72,SPO2:97,STEMP:33.9,MOT:1.02,EMG:1234,EMGPK:1810,EMGBITS:12,ST:7F,TS:1726470000000
```

If a notification fails, the line is stored instead, which switches to catch-up.

### Catch-up

While anything is stored and not yet acknowledged, **every new line is stored too**, so
records reach the phone strictly in order. The wearable:

1. Sends stored records from the one after the last acknowledged record, oldest first:
   ```
   HR:70,STEMP:33.8,MOT:1.00,ST:7F,TS:1726469000000,BUF:1,SEQ:4711
   SEQ:4712,LOST:1,BUF:1
   ```
2. Stops sending while 32 sent records are unacknowledged.
3. Sends a status line once a second, **after** that pass's records, so `LAST` never names a
   record that is stored but not yet sent (otherwise the phone could never tell it has
   everything, and catch-up would not end):
   ```
   ST:7F,PEND:120,LAST:4830
   ```
4. Goes back to the oldest unacknowledged record if no acknowledgement arrives for
   5 seconds while records are unacknowledged.
5. Returns to live mode once the phone has acknowledged the newest stored record. The
   stored file is then deleted.

The phone:

1. Skips any record number it has already received on this connection (a resend).
2. Stores readings. Readings from stored records are checked by the same rules but never
   raise a notification, because they describe the past.
3. Acknowledges with `ACK:<n>` where `n` is the highest number such that every record up
   to `n` has arrived and its reading is stored. A gap waits for the wearable's resend.
   Acknowledgements go out after 10 more records are covered, or 1 second after the
   last one.
4. Treats a status line whose `LAST` it has already received as the end of what the
   wearable has sent. Readings still being combined are stored and acknowledged then.

After a dropped link, both sides start again from the last acknowledged record. Nothing
unacknowledged is lost, and nothing is stored twice: the app also skips a reading whose
time is already stored.

## Compatibility

- **Older firmware** (no `SEQ`): stored lines are accepted as before and never acknowledged.
- **Older apps**: ignore `SEQ`, `PEND`, `LAST` and `LOST`, and never send `ACK`. The
  wearable then keeps resending its stored records, which the old app skips by time, and
  stays in catch-up mode. Update the app along with the firmware.
