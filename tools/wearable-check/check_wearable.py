#!/usr/bin/env python3
"""
G-one wearable checker.

The other half of tools/wearable-emulator: there the PC plays the wearable; here it plays the
PHONE, against the real wearable, over Bluetooth. It checks what the firmware sends against the
rules the app applies (docs/WEARABLE_PROTOCOL.md and the app's AsciiKeyValuePacketParser), and
answers like the app does: it sets the clock, acknowledges stored records once it "has" them,
and sends STOP when told to stop.

With --serial it also reads the wearable's USB port, where the firmware prints `SENT: <line>`
once a second, and checks that the line that arrived over Bluetooth is, byte for byte, the line
the board says it sent.

Nothing is simulated: every value comes from the wearable's sensors.

  python check_wearable.py stream  [--seconds 60]   live lines, their fields and timing
  python check_wearable.py backlog [--away 60]      out of range, then catch-up
  python check_wearable.py stop    [--away 60]      STOP, then nothing recorded while away

  common options: --serial COM13   --name "G-one Wearable"   --address AA:BB:...

Needs `bleak`; --serial needs `pyserial`. Exit status 0 when every check passed.
"""

import argparse
import asyncio
import re
import sys
import threading
import time

SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
SERIAL_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

# The app's accepted ranges (AsciiKeyValuePacketParser). Integers must be written as integers.
FIELDS = {
    "HR": (int, 20, 300),
    "SPO2": (int, 50, 100),
    "TEMP": (float, 25, 45),
    "STEMP": (float, 20, 42),
    "MOT": (float, 0, 16),
    "EMG": (int, 0, 4095),
    "EMGPK": (int, 0, 4095),
    "ATEMP": (float, -30, 70),
    "HUM": (float, 0, 100),
    "AQI": (int, 0, 1000),
}
MAX_CLOCK_AHEAD_MS = 2 * 60 * 1000
MAX_RECORD_AGE_MS = 30 * 24 * 60 * 60 * 1000
ST_NAMES = {0x01: "pulse oximeter", 0x02: "skin contact", 0x04: "skin thermometer", 0x08: "motion sensor",
            0x10: "EMG signal", 0x20: "SD card", 0x40: "clock set"}

ACK_EVERY_RECORDS = 10
ACK_MAX_DELAY = 1.0


def now_ms():
    return int(time.time() * 1000)


class Line:
    """One line, read the way the app reads it."""

    def __init__(self, text, received_ms):
        self.text = text
        self.received_ms = received_ms
        self.values = {}
        self.rejected = []          # (key, raw, why)
        self.status = None
        self.backlog = False
        self.ts = None
        self.trusted_ts = None
        self.seq = None
        self.lost = 0
        self.pending = None
        self.newest = None
        emg_bits = None
        for part in text.split(","):
            if ":" not in part:
                continue
            key, raw = part.split(":", 1)
            key, raw = key.strip().upper(), raw.strip()
            if not key or not raw:
                continue
            if key in FIELDS:
                kind, low, high = FIELDS[key]
                try:
                    value = kind(raw)
                except ValueError:
                    self.rejected.append((key, raw, "not " + ("a whole number" if kind is int else "a number")))
                    continue
                if not (low <= value <= high):
                    self.rejected.append((key, raw, f"outside {low}..{high}"))
                    continue
                self.values[key] = value
            elif key == "EMGBITS":
                emg_bits = raw
            elif key == "ST":
                if re.fullmatch(r"[0-9A-Fa-f]{1,4}", raw):
                    self.status = int(raw, 16)
                else:
                    self.rejected.append((key, raw, "not 1-4 hex digits"))
            elif key == "BUF":
                self.backlog = raw == "1"
            elif key in ("TS", "SEQ", "LOST", "PEND", "LAST"):
                try:
                    number = int(raw)
                except ValueError:
                    self.rejected.append((key, raw, "not a whole number"))
                    continue
                setattr(self, {"TS": "ts", "SEQ": "seq", "LOST": "lost", "PEND": "pending", "LAST": "newest"}[key], number)
        if emg_bits is not None and emg_bits != "12":
            for key in ("EMG", "EMGPK"):
                if key in self.values:
                    self.rejected.append((key, str(self.values.pop(key)), f"EMGBITS:{emg_bits}, the app needs 12"))
        if "EMGPK" in self.values and self.values["EMGPK"] < self.values.get("EMG", 1 << 30):
            self.rejected.append(("EMGPK", str(self.values.pop("EMGPK")), "below EMG"))
        if self.ts is not None:
            if received_ms - MAX_RECORD_AGE_MS <= self.ts <= received_ms + MAX_CLOCK_AHEAD_MS:
                self.trusted_ts = self.ts
            else:
                self.rejected.append(("TS", str(self.ts), "not a time the app trusts"))

    @property
    def is_status(self):
        return self.pending is not None or self.newest is not None

    @property
    def has_reading(self):
        return bool(self.values)


class SerialTap:
    """The wearable's USB port, read in the background: its `SENT:` lines and anything else."""

    def __init__(self, port):
        import serial  # pyserial
        self.sent = {}               # TS -> exact line the firmware says it sent
        self.log = []
        self.paused_seen = False
        self._port = serial.Serial()
        self._port.port = port
        self._port.baudrate = 115200
        self._port.timeout = 0.2
        self._port.dtr = False       # opening with DTR/RTS raised can reset an ESP32-S3
        self._port.rts = False
        self._port.open()
        self._stop = False
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        buffer = b""
        while not self._stop:
            try:
                chunk = self._port.read(512)
            except Exception as e:
                self.log.append(f"(serial error: {e})")
                return
            buffer += chunk
            while b"\n" in buffer:
                raw, buffer = buffer.split(b"\n", 1)
                text = raw.decode("ascii", "replace").strip()
                self.log.append(text)
                if text.startswith("SENT: ") and "(stored" not in text:
                    line = text[len("SENT: "):]
                    match = re.search(r"(?:^|,)TS:(\d+)", line)
                    if match:
                        self.sent[int(match.group(1))] = line
                if "recording PAUSED" in text:
                    self.paused_seen = True

    def close(self):
        self._stop = True
        time.sleep(0.3)
        self._port.close()


class Phone:
    """What the app does with a link: collects lines, acknowledges stored records, sets the clock."""

    def __init__(self, client, tap):
        self.client = client
        self.tap = tap
        self.buffer = ""
        self.lines = []
        self.records = {}            # seq -> Line, stored records received
        self.lost = 0
        self.acked = 0               # highest ACK sent
        self.first_seq = None
        self.due_since = None
        self.caught_up_at = None
        self.duplicates = 0

    async def write(self, text):
        await self.client.write_gatt_char(SERIAL_UUID, text.encode("ascii"), response=False)

    async def sync_clock(self):
        await self.write(f"T:{now_ms()}\n")

    def on_bytes(self, _handle, data):
        received = now_ms()
        self.buffer += data.decode("ascii", "replace")
        while "\n" in self.buffer:
            raw, self.buffer = self.buffer.split("\n", 1)
            raw = raw.strip()
            if raw:
                self.on_line(Line(raw, received))

    def on_line(self, line):
        self.lines.append(line)
        if line.backlog and line.seq is not None:
            if line.seq in self.records or line.seq <= self.acked:
                self.duplicates += 1
                return
            self.records[line.seq] = line
            self.lost += line.lost
            if self.first_seq is None:
                self.first_seq = line.seq - max(line.lost, 1) + 1
            if self.due_since is None:
                self.due_since = time.monotonic()
        if line.is_status and line.newest is not None and self.contiguous() >= line.newest:
            self.caught_up_at = self.caught_up_at or time.monotonic()

    def contiguous(self):
        """Highest record number such that every one before it has arrived."""
        if self.first_seq is None:
            return self.acked
        n = max(self.acked, self.first_seq - 1)
        while n + 1 in self.records:
            n += 1
        return n

    async def acknowledge(self, force=False):
        target = self.contiguous()
        if target <= self.acked:
            return
        waited = self.due_since is not None and time.monotonic() - self.due_since >= ACK_MAX_DELAY
        if force or waited or target - self.acked >= ACK_EVERY_RECORDS:
            await self.write(f"ACK:{target}\n")
            self.acked = target
            self.due_since = time.monotonic() if self.contiguous() > self.acked else None


async def find_wearable(args):
    from bleak import BleakScanner
    print("Looking for the wearable...")
    if args.address:
        device = await BleakScanner.find_device_by_address(args.address, timeout=20)
    else:
        device = await BleakScanner.find_device_by_filter(
            lambda d, adv: SERVICE_UUID in [u.lower() for u in adv.service_uuids] or (d.name or adv.local_name) == args.name,
            timeout=20)
    if device is None:
        sys.exit("No G-one wearable found. Is it powered, advertising, and not connected to the phone?")
    print(f"Found {device.name or args.name} at {device.address}")
    return device


async def session(device, tap, seconds, until=None):
    """One connection, run like the app for `seconds` (or until `until(phone)` is true)."""
    from bleak import BleakClient
    async with BleakClient(device, timeout=20) as client:
        phone = Phone(client, tap)
        await client.start_notify(SERIAL_UUID, phone.on_bytes)
        await phone.sync_clock()
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            await asyncio.sleep(0.1)
            await phone.acknowledge(force=phone.caught_up_at is not None)
            if until and until(phone):
                break
        await phone.acknowledge(force=True)
        await asyncio.sleep(0.5)
        return phone


# ─── Reporting ───────────────────────────────────────────────────────────────

class Report:
    def __init__(self):
        self.results = []

    def check(self, name, ok, detail=""):
        self.results.append(ok)
        print(("  PASS  " if ok else "  FAIL  ") + name + (f" — {detail}" if detail else ""))

    @property
    def passed(self):
        return all(self.results)


def describe_fields(lines):
    readings = [l for l in lines if l.has_reading and not l.backlog]
    print(f"\n  {len(readings)} live readings. Fields, as the app will store them:")
    for key in ("HR", "SPO2", "STEMP", "MOT", "EMG", "EMGPK"):
        values = [l.values[key] for l in readings if key in l.values]
        if values:
            print(f"    {key:6} in {len(values):4} lines   min {min(values):>8}   max {max(values):>8}   last {values[-1]}")
        else:
            print(f"    {key:6} never sent")
    status = next((l.status for l in reversed(readings) if l.status is not None), None)
    if status is not None:
        on = [name for bit, name in ST_NAMES.items() if status & bit]
        off = [name for bit, name in ST_NAMES.items() if not status & bit]
        print(f"    ST:{status:02X}  on: {', '.join(on) or 'none'}  |  off: {', '.join(off) or 'none'}")


def check_live(report, phone, tap, seconds):
    live = [l for l in phone.lines if l.has_reading and not l.backlog]
    rate = len(live) / seconds if seconds else 0
    report.check("readings arrive about twice a second", 1.6 <= rate <= 2.4, f"{rate:.2f} a second")
    rejected = [(l.text, r) for l in phone.lines for r in l.rejected]
    report.check("the app would reject no field", not rejected,
                 "; ".join(f"{r[0]}:{r[1]} {r[2]}" for _, r in rejected[:3]) if rejected else f"{len(phone.lines)} lines")
    stamped = [l for l in live if l.trusted_ts is not None]
    report.check("every live reading carries a time the app trusts once the clock is set",
                 len(stamped) >= len(live) - 2, f"{len(stamped)} of {len(live)}")
    if stamped:
        offsets = [l.received_ms - l.trusted_ts for l in stamped]
        report.check("the wearable's clock agrees with this PC's", max(abs(o) for o in offsets) < 2000,
                     f"arrival minus TS {min(offsets)}..{max(offsets)} ms")
        gaps = [b.trusted_ts - a.trusted_ts for a, b in zip(stamped, stamped[1:])]
        report.check("times only go forward, about 500 ms apart", gaps and min(gaps) > 0 and max(gaps) < 1500,
                     f"{min(gaps)}..{max(gaps)} ms" if gaps else "")
    synced = [l for l in live[4:] if l.status is not None]
    report.check("the clock-set bit is on after the phone set it", synced and all(l.status & 0x40 for l in synced))
    if tap is not None:
        compared = [(ts, line) for ts, line in tap.sent.items() if any(l.trusted_ts == ts for l in live)]
        same = [ts for ts, line in compared if any(l.trusted_ts == ts and l.text == line for l in live)]
        report.check("each line received is exactly the line the board printed as SENT",
                     compared and len(same) == len(compared), f"{len(same)} of {len(compared)} compared")


async def run(args):
    report = Report()
    tap = SerialTap(args.serial) if args.serial else None
    try:
        device = await find_wearable(args)

        if args.mode == "stream":
            print(f"\nStreaming for {args.seconds} s, as the app would...")
            phone = await session(device, tap, args.seconds)
            describe_fields(phone.lines)
            print()
            check_live(report, phone, tap, args.seconds)

        elif args.mode in ("backlog", "stop"):
            print("\nConnecting first: sets the clock and clears anything already waiting...")
            await session(device, tap, 60, until=lambda p: p.caught_up_at is not None or
                          (len([l for l in p.lines if l.has_reading and not l.backlog]) >= 10))

            if args.mode == "stop":
                print("Sending STOP, as the app does when monitoring is stopped...")
                from bleak import BleakClient
                async with BleakClient(device, timeout=20) as client:
                    phone = Phone(client, tap)
                    await client.start_notify(SERIAL_UUID, phone.on_bytes)
                    await phone.sync_clock()
                    await asyncio.sleep(2)
                    await phone.write("STOP\n")
                    await asyncio.sleep(0.3)
            print(f"Disconnected. Staying away for {args.away} s...")
            away_from = now_ms()
            await asyncio.sleep(args.away)
            away_to = now_ms()

            print("Reconnecting, and catching up as the app would...")
            phone = await session(device, tap, max(90, args.away * 2),
                                  until=lambda p: p.caught_up_at is not None and
                                  time.monotonic() - p.caught_up_at > 3 or
                                  (args.mode == "stop" and len([l for l in p.lines if l.has_reading]) >= 20))
            records = sorted(phone.records.values(), key=lambda l: l.seq)
            in_gap = [r for r in records if r.trusted_ts is not None and away_from - 2000 <= r.trusted_ts <= away_to + 2000]
            print(f"\n  {len(records)} stored records received, {len(in_gap)} from the time away, "
                  f"{phone.lost} reported lost, {phone.duplicates} resends skipped")

            if args.mode == "backlog":
                expected = args.away * 2
                report.check("every half second away arrives as a stored record", len(in_gap) >= expected - 4,
                             f"{len(in_gap)} of about {expected}")
                seqs = [r.seq for r in records]
                report.check("record numbers have no gaps", seqs == list(range(seqs[0], seqs[0] + len(seqs))) if seqs else False)
                report.check("stored records carry real times", all(r.trusted_ts is not None for r in records if r.lost == 0))
                report.check("nothing lost", phone.lost == 0)
                report.check("catch-up finished and live readings resumed",
                             phone.caught_up_at is not None and any(l.has_reading and not l.backlog for l in phone.lines[-10:]))
                rejected = [r for l in phone.lines for r in l.rejected]
                report.check("the app would reject no field", not rejected, str(rejected[:3]) if rejected else "")
            else:
                report.check("nothing was recorded while monitoring was stopped", not in_gap,
                             f"{len(in_gap)} records from the stop")
                report.check("live readings flow straight away on reconnecting",
                             any(l.has_reading and not l.backlog for l in phone.lines))
                if tap is not None:
                    report.check("the board said it had paused", tap.paused_seen)
    finally:
        if tap is not None:
            tap.close()

    print("\nAll checks passed." if report.passed else "\nSome checks FAILED.")
    return 0 if report.passed else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=["stream", "backlog", "stop"])
    parser.add_argument("--seconds", type=int, default=60)
    parser.add_argument("--away", type=int, default=60)
    parser.add_argument("--serial", help="the wearable's USB serial port, e.g. COM13")
    parser.add_argument("--name", default="G-one Wearable")
    parser.add_argument("--address")
    args = parser.parse_args()
    sys.exit(asyncio.run(run(args)))


if __name__ == "__main__":
    main()
