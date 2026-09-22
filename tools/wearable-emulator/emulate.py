#!/usr/bin/env python3
"""
G-one wearable emulator.

Plays the wearable from docs/WEARABLE_PROTOCOL.md so the phone side of syncing can be checked
without the hardware. The wearable logic here mirrors firmware/g_one_wearable/g_one_wearable.ino:
numbered stored records, ACK, catch-up pacing, resend after 5 s without an acknowledgement,
records kept before the clock is set, and LOST for records that cannot be placed.

Two ways to run it:

  python emulate.py --selftest
      No Bluetooth, no extra packages. Runs the wearable against a simulated phone through
      scripted scenarios (out of range, app killed mid-replay, restart before the clock is set,
      a flaky link) on a fast virtual clock, and checks that nothing is lost or stored twice.

  python emulate.py --scenario normal|out-of-range|reboot|emg|flaky [options]
      Advertises the G-one serial service (FFE0, characteristic FFE1) from this PC's Bluetooth
      adapter, for the checks in VERIFICATION.md. It shows up under the PC's Bluetooth name;
      the app lists it first because of the service. Needs the `bless` package
      (pip install bless) and an adapter that can act as a BLE peripheral.
"""

import argparse
import asyncio
import math
import random
import sys
import time

SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
SERIAL_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
DEVICE_NAME = "G-one Wearable"

LINE_INTERVAL = 0.5
SEND_WINDOW = 32
ACK_TIMEOUT = 5.0
STATUS_INTERVAL = 1.0
REPLAY_LINES_PER_TICK = 10

ST_ALL_SENSORS = 0x01 | 0x02 | 0x04 | 0x08 | 0x10 | 0x20
ST_CLOCK_SYNCED = 0x40


def field(line, key):
    """The integer value of `key` ("SEQ") in a line, or None."""
    for part in line.split(","):
        if part.startswith(key + ":"):
            try:
                return int(part[len(key) + 1:])
            except ValueError:
                return None
    return None


def without(line, *keys):
    return ",".join(p for p in line.split(",") if p and not any(p.startswith(k + ":") for k in keys))


# ─── The wearable ────────────────────────────────────────────────────────────

class Wearable:
    """The firmware's sync logic. `send(text) -> bool` is the notification; `now()` is seconds."""

    def __init__(self, now, send, log=print):
        self.now = now
        self.send = send
        self.log = log
        self.stored = []            # the SD card file: stored record lines, oldest first
        self.boot_id = 1
        self.boot_started = now()
        self.next_seq = 1
        self.acked_seq = 0
        self.acked_index = 0        # position in `stored` just after the acknowledged record
        self.backlog_mode = False
        self.connected = False
        self.recording_paused = False   # STOP from the app; cleared when it connects again
        self.clock_synced = False
        self.epoch_offset_ms = 0
        self.ack_requested = 0
        self.read_index = 0
        self.sent_seq = 0
        self.sent_ring = []         # (seq, index after it)
        self.last_ack_progress = now()
        self.last_status = 0.0
        self.partial_line = False
        self.emg_level = 300
        self.counters = {"generated": 0, "live_sent": 0, "stored": 0, "not_recorded": 0, "replayed": 0,
                         "lost_sent": 0, "rewinds": 0}

    # time
    def uptime_ms(self):
        return int((self.now() - self.boot_started) * 1000)

    def epoch_ms(self):
        return self.uptime_ms() + self.epoch_offset_ms

    # commands from the phone
    def on_write(self, text):
        for command in text.strip().splitlines():
            if command.startswith("T:"):
                try:
                    epoch = int(command[2:])
                except ValueError:
                    continue
                if epoch > 1_600_000_000_000:
                    self.epoch_offset_ms = epoch - self.uptime_ms()
                    self.clock_synced = True
            elif command.startswith("ACK:"):
                try:
                    self.ack_requested = max(self.ack_requested, int(command[4:]))
                except ValueError:
                    pass
            elif command.startswith("STOP"):
                self.recording_paused = True

    def connect(self):
        self.connected = True
        self.recording_paused = False

    def disconnect(self):
        self.connected = False

    def reboot(self):
        """Power cycle: RAM is lost, the SD card is kept."""
        self.boot_id += 1
        self.boot_started = self.now()
        self.clock_synced = False
        self.epoch_offset_ms = 0
        self.connected = False
        self.recording_paused = False
        self.ack_requested = 0
        newest = max((field(r, "SEQ") or 0) for r in self.stored) if self.stored else 0
        self.next_seq = max(self.next_seq, newest + 1)
        self.backlog_mode = self.next_seq - 1 > self.acked_seq
        self.rewind()

    # lines
    def reading(self):
        t = self.now()
        hr = 72 + round(6 * math.sin(t / 40))
        spo2 = 97 if int(t) % 60 else 96
        emg = max(0, int(random.gauss(self.emg_level, self.emg_level * 0.05 + 5)))
        status = ST_ALL_SENSORS | (ST_CLOCK_SYNCED if self.clock_synced else 0)
        return f"HR:{hr},SPO2:{spo2},STEMP:33.8,MOT:1.0{random.randint(0, 9)},EMG:{emg},EMGPK:{emg + 40},EMGBITS:12,ST:{status:02X}"

    def tick_line(self):
        line = self.reading()
        self.counters["generated"] += 1
        if self.connected and not self.backlog_mode:
            live = line + (f",TS:{self.epoch_ms()}" if self.clock_synced else "")
            if self.send_text(live + "\n"):
                self.counters["live_sent"] += 1
                return
        # Out of range the app still wants what happens; after a STOP from the app it does not.
        if not self.connected and self.recording_paused:
            self.counters["not_recorded"] += 1
            return
        self.store(line)

    def store(self, line):
        if self.clock_synced:
            record = f"{line},TS:{self.epoch_ms()}"
        else:
            record = f"{line},UP:{self.uptime_ms()},BOOT:{self.boot_id}"
        record += f",SEQ:{self.next_seq}"
        if not self.backlog_mode:
            self.stored = []
            self.acked_seq = self.next_seq - 1
            self.acked_index = 0
            self.rewind()
        self.stored.append(record)
        self.next_seq += 1
        self.backlog_mode = True
        self.counters["stored"] += 1

    def send_text(self, text):
        if not self.connected:
            return False
        out = ("\n" + text) if self.partial_line else text
        ok = self.send(out)
        self.partial_line = not ok and self.partial_line
        return ok

    # sync
    def rewind(self):
        self.read_index = self.acked_index
        self.sent_seq = self.acked_seq
        self.sent_ring = []
        self.last_ack_progress = self.now()

    def handle_ack(self, seq):
        if seq <= self.acked_seq or seq > self.sent_seq:
            return
        moved = False
        while self.sent_ring and self.sent_ring[0][0] <= seq:
            self.acked_seq, self.acked_index = self.sent_ring.pop(0)
            moved = True
        if not moved:
            return
        self.last_ack_progress = self.now()
        if self.acked_seq >= self.next_seq - 1:
            self.stored = []
            self.backlog_mode = False
            self.acked_index = self.read_index = 0
            self.sent_seq = self.acked_seq
            self.sent_ring = []

    def replay_form(self, record, seq):
        up = field(record, "UP")
        if up is None:
            return record + ",BUF:1"
        if field(record, "BOOT") == self.boot_id and self.clock_synced:
            return without(record, "UP", "BOOT", "SEQ") + f",TS:{up + self.epoch_offset_ms},BUF:1,SEQ:{seq}"
        return f"SEQ:{seq},LOST:1,BUF:1"

    def service(self):
        now = self.now()
        if self.ack_requested > self.acked_seq:
            self.handle_ack(self.ack_requested)
        if not self.connected:
            if self.sent_seq != self.acked_seq:
                self.rewind()
            return
        if not self.backlog_mode or not self.clock_synced:
            return
        if self.sent_seq > self.acked_seq and now - self.last_ack_progress >= ACK_TIMEOUT:
            self.counters["rewinds"] += 1
            self.rewind()
        if self.sent_seq == self.acked_seq:
            self.last_ack_progress = now
        self.replay()
        # After the replay, as in the firmware: LAST must not name a record not yet sent.
        if now - self.last_status >= STATUS_INTERVAL:
            self.last_status = now
            status = ST_ALL_SENSORS | ST_CLOCK_SYNCED
            self.send_text(f"ST:{status:02X},PEND:{self.next_seq - 1 - self.acked_seq},LAST:{self.next_seq - 1}\n")

    def replay(self):
        sent = 0
        while (sent < REPLAY_LINES_PER_TICK and self.sent_seq - self.acked_seq < SEND_WINDOW
               and self.sent_seq < self.next_seq - 1 and self.read_index < len(self.stored)):
            record = self.stored[self.read_index]
            seq = field(record, "SEQ")
            if seq is None or seq <= self.sent_seq:
                self.read_index += 1
                continue
            if seq > self.sent_seq + 1:
                out_seq = seq - 1
                out = f"SEQ:{out_seq},LOST:{out_seq - self.sent_seq},BUF:1"
                after = self.read_index
            else:
                out_seq, out, after = seq, self.replay_form(record, seq), self.read_index + 1
            if not self.send_text(out + "\n"):
                return
            if ",LOST:" in out or out.startswith("SEQ:"):
                self.counters["lost_sent"] += 1
            else:
                self.counters["replayed"] += 1
            self.read_index = after
            self.sent_ring.append((out_seq, after))
            self.sent_seq = out_seq
            sent += 1


# ─── A simulated phone for --selftest (mirrors BacklogAckTracker and the service) ───

class Phone:
    BATCH = 10
    MAX_DELAY = 1.0
    BUCKET_LINES = 10   # readings wait in 5-second buckets before they are stored

    def __init__(self, now, write, epoch_now):
        self.now = now
        self.write = write
        self.epoch_now = epoch_now
        self.readings = {}          # TS -> times stored
        self.duplicates_skipped = 0
        self.lost = 0
        self.live_readings = 0
        self.buffer = ""
        self.reset_link()

    def reset_link(self):
        self.next = None
        self.base = None
        self.ahead = {}
        self.held_from = None
        self.last_sent = None
        self.covered_since = None
        self.bucket = []
        self.buffer = ""

    def on_connect(self):
        # The source resets its parser and tracker per link; the service keeps its open bucket.
        self.reset_tracker()
        self.buffer = ""
        self.write(f"T:{self.epoch_now()}\n")

    # tracker
    def receive(self, seq, lost):
        first = seq - max(lost, 1) + 1
        if self.next is None:
            self.base, self.next = first - 1, seq + 1
            return True
        if seq < self.next:
            resume = (self.last_sent if self.last_sent is not None else self.base) + 1
            if first != resume:
                return False
            self.reset_tracker()
            return self.receive(seq, lost)
        if first <= self.next:
            self.next = seq + 1
            while True:
                starts = sorted(k for k in self.ahead if k <= self.next)
                if not starts:
                    break
                k = starts[0]
                end = self.ahead.pop(k)
                if end >= self.next:
                    self.next = end + 1
            return True
        for k, end in self.ahead.items():
            if k <= seq <= end:
                return False
        self.ahead[first] = seq
        return True

    def reset_tracker(self):
        self.next = self.base = self.held_from = self.last_sent = self.covered_since = None
        self.ahead = {}

    def acknowledgeable(self):
        if self.next is None:
            return None
        through = self.next - 1
        limit = min(through, self.held_from - 1) if self.held_from is not None else through
        return limit if limit >= 1 and limit > (self.base or 0) else None

    def due(self, force=False):
        covered = self.acknowledgeable()
        if covered is None:
            return None
        previous = self.last_sent if self.last_sent is not None else (self.base or 0)
        if covered <= previous:
            self.covered_since = None
            return None
        if self.covered_since is None:
            self.covered_since = self.now()
        if force or covered - previous >= self.BATCH or self.now() - self.covered_since >= self.MAX_DELAY:
            return covered
        return None

    # storage
    def store_bucket(self):
        for ts in self.bucket:
            if ts in self.readings:
                self.duplicates_skipped += 1
            else:
                self.readings[ts] = 1
        self.bucket = []
        self.held_from = None

    def on_bytes(self, data):
        self.buffer += data
        while "\n" in self.buffer:
            line, self.buffer = self.buffer.split("\n", 1)
            line = line.strip()
            if line:
                self.on_line(line)

    def on_line(self, line):
        seq, lost, ts = field(line, "SEQ"), field(line, "LOST") or 0, field(line, "TS")
        backlog = field(line, "BUF") == 1
        caught_up = False
        if backlog:
            if seq is not None and not self.receive(seq, lost):
                return
            self.lost += lost
            if ts is not None and lost == 0:
                if len(self.bucket) >= self.BUCKET_LINES:
                    self.store_bucket()
                self.bucket.append(ts)
                if seq is not None and self.held_from is None:
                    self.held_from = seq
        else:
            newest = field(line, "LAST")
            if newest is not None:
                caught_up = (self.next is not None and self.next > newest) or (self.last_sent is not None and self.last_sent >= newest)
            if caught_up or field(line, "HR") is not None:
                self.store_bucket()
            if field(line, "HR") is not None and ts is not None:
                self.live_readings += 1
                self.readings[ts] = self.readings.get(ts, 0) + 1
        ack = self.due(force=caught_up)
        if ack is not None and self.write(f"ACK:{ack}\n"):
            self.last_sent = max(self.last_sent or 0, ack)
            self.covered_since = None


def selftest():
    clock = [0.0]
    now = lambda: clock[0]
    epoch_base = 1_726_470_000_000
    epoch_now = lambda: epoch_base + int(clock[0] * 1000)
    rng = random.Random(7)
    link = {"up": False, "notify_fail": 0.0, "write_fail": 0.0}
    wearable = None
    phone = None

    def notify(text):
        if not link["up"] or rng.random() < link["notify_fail"]:
            return False
        phone.on_bytes(text)
        return True

    def write(text):
        if not link["up"] or rng.random() < link["write_fail"]:
            return False
        wearable.on_write(text)
        return True

    wearable = Wearable(now, notify, log=lambda *_: None)
    phone = Phone(now, write, epoch_now)

    def connect():
        link["up"] = True
        wearable.connect()
        phone.on_connect()

    def disconnect(app_killed=False):
        link["up"] = False
        wearable.disconnect()
        if app_killed:
            phone.reset_link()   # a new process: nothing in memory survives
        else:
            phone.reset_tracker()   # a new link: the tracker starts over; readings in the open bucket stay

    def run(seconds):
        end = clock[0] + seconds
        next_line = clock[0]
        while clock[0] < end:
            if clock[0] >= next_line:
                wearable.tick_line()
                next_line += LINE_INTERVAL
            wearable.service()
            clock[0] = round(clock[0] + 0.05, 3)

    results = []

    def check(name, condition, detail=""):
        results.append((name, condition, detail))
        print(("PASS " if condition else "FAIL ") + name + (f"  ({detail})" if detail else ""))

    def placeable_generated():
        return wearable.counters["generated"]

    # S1: normal streaming
    connect()
    run(60)
    check("S1 live lines flow with the clock set", phone.live_readings >= 110 and not wearable.backlog_mode,
          f"{phone.live_readings} live readings")

    # S2: out of range for 5 minutes
    before = len(phone.readings)
    disconnect()
    run(300)
    stored_while_away = wearable.counters["stored"]
    connect()
    run(120)
    gained = len(phone.readings) - before
    check("S2 every reading from 5 minutes out of range arrives", not wearable.backlog_mode and gained >= stored_while_away,
          f"{stored_while_away} stored on the wearable, {gained} new readings on the phone")

    # S3: the app is killed mid-replay, then reopened
    disconnect()
    run(600)
    connect()
    run(3)                      # part of the replay
    acked_before_kill = wearable.acked_seq
    disconnect(app_killed=True)
    run(10)
    connect()
    run(180)
    check("S3 replay resumes after the app is killed", not wearable.backlog_mode, f"acked {acked_before_kill} before the kill, now {wearable.acked_seq}")
    counts = list(phone.readings.values())
    check("S3 nothing stored twice", max(counts) == 1, f"{phone.duplicates_skipped} resends skipped by time")

    # S4: restart away from the phone before the clock is set
    disconnect()
    run(5)
    wearable.reboot()
    run(60)                     # records with uptime only
    connect()
    run(90)
    check("S4 readings from the same boot are placed in time", not wearable.backlog_mode and phone.lost == 0,
          f"lost {phone.lost}")

    # S5: a boot that never got the time, then another restart
    disconnect()
    wearable.reboot()
    run(30)                     # 60 records that can never be placed
    wearable.reboot()
    run(10)                     # 20 records from this boot
    connect()
    run(90)
    check("S5 records from an earlier boot are reported lost, not dropped silently", phone.lost == 60 and not wearable.backlog_mode,
          f"lost {phone.lost}")

    # S6: a flaky link
    disconnect()
    run(120)
    link["notify_fail"] = 0.1
    link["write_fail"] = 0.2
    connect()
    run(240)
    link["notify_fail"] = link["write_fail"] = 0.0
    run(30)
    check("S6 a link that drops notifications and acknowledgements still catches up", not wearable.backlog_mode,
          f"{wearable.counters['rewinds']} resends after silence")
    counts = list(phone.readings.values())
    check("S6 still nothing stored twice", max(counts) == 1)

    # S7: the wearer stops monitoring in the app, and starts it again later
    stored_before = wearable.counters["stored"]
    write("STOP\n")            # what the app sends when monitoring is stopped
    disconnect()
    run(120)
    recorded_while_stopped = wearable.counters["stored"] - stored_before
    connect()
    run(30)
    check("S7 time after a stop in the app is not recorded", recorded_while_stopped == 0 and not wearable.backlog_mode,
          f"{recorded_while_stopped} stored, {wearable.counters['not_recorded']} not recorded")
    disconnect()                 # out of range this time, with no stop
    run(60)
    recorded_away = wearable.counters["stored"] - stored_before
    connect()
    run(60)
    check("S7 once the app has connected again, time out of range is recorded and delivered",
          recorded_away >= 110 and not wearable.backlog_mode, f"{recorded_away} stored while away")

    total = placeable_generated()
    print(f"\n{total} lines generated, {len(phone.readings)} readings stored, {phone.lost} lost records reported, "
          f"{wearable.counters['rewinds']} rewinds")
    return all(ok for _, ok, _ in results)


# ─── Over Bluetooth, with bless ──────────────────────────────────────────────

def _prepare_bless():
    """
    bless 0.3.0 on Windows imports `pysetupdi`, which is not on PyPI, only to rename the PC's
    Bluetooth adapter: a registry change plus an adapter restart. The emulator never renames the
    adapter, so a stand-in module satisfies the import and the renaming class is disabled. The PC
    therefore advertises under its own name; the app still lists it first by its FFE0 service.
    """
    import types
    if sys.platform == "win32":
        try:
            import pysetupdi  # noqa: F401
        except ImportError:
            stub = types.ModuleType("pysetupdi")
            stub.devices = lambda guid: []
            sys.modules["pysetupdi"] = stub
    from bless import BlessServer, GATTAttributePermissions, GATTCharacteristicProperties
    if sys.platform == "win32":
        import bless.backends.winrt.server as winrt_server

        class NoAdapterRename:
            def set_local_name(self, local_name):
                raise RuntimeError("Renaming the Bluetooth adapter is disabled in this emulator")

        winrt_server.BLEAdapter = NoAdapterRename
    return BlessServer, GATTAttributePermissions, GATTCharacteristicProperties


async def run_ble(args):
    try:
        BlessServer, GATTAttributePermissions, GATTCharacteristicProperties = _prepare_bless()
    except ImportError:
        print("This mode needs the `bless` package: pip install bless", file=sys.stderr)
        return 2

    loop = asyncio.get_running_loop()
    server = BlessServer(name=DEVICE_NAME, loop=loop)
    wearable = None

    def notify(text):
        data = text.encode("ascii")
        characteristic = server.get_characteristic(SERIAL_UUID)
        ok = True
        for start in range(0, len(data), 180):
            if args.scenario == "flaky" and random.random() < 0.1:
                return False
            characteristic.value = bytearray(data[start:start + 180])
            ok = server.update_value(SERVICE_UUID, SERIAL_UUID) is not False and ok
        return ok

    def on_read(characteristic, **kwargs):
        return characteristic.value

    def on_write(characteristic, value, **kwargs):
        text = bytes(value).decode("ascii", errors="replace")
        print(f"  phone → {text.strip()}")
        wearable.on_write(text)

    server.read_request_func = on_read
    server.write_request_func = on_write
    await server.add_new_service(SERVICE_UUID)
    props = (GATTCharacteristicProperties.read | GATTCharacteristicProperties.write
             | GATTCharacteristicProperties.write_without_response | GATTCharacteristicProperties.notify)
    perms = GATTAttributePermissions.readable | GATTAttributePermissions.writeable
    await server.add_new_characteristic(SERVICE_UUID, SERIAL_UUID, props, bytearray(b""), perms)

    wearable = Wearable(time.monotonic, notify)
    advertising = False

    async def advertise(on, why="Out of range: advertising stopped, readings are being stored"):
        nonlocal advertising
        if on and not advertising:
            await server.start()
            advertising = True
            print("Advertising the G-one serial service (FFE0) under this PC's Bluetooth name")
        elif not on and advertising:
            await server.stop()
            advertising = False
            wearable.disconnect()
            print(why)

    await advertise(True)
    if args.scenario == "reboot":
        print("Simulating a restart before the phone has set the clock")
        wearable.reboot()

    started = time.monotonic()
    next_line = started
    last_report = started
    away_from = started + args.away_after
    away_until = away_from + args.away_minutes * 60
    manual_away = False
    last_control = 0.0
    try:
        while args.seconds <= 0 or time.monotonic() - started < args.seconds:
            t = time.monotonic()
            if args.control and t - last_control >= 0.5:
                last_control = t
                for command in read_commands(args.control):
                    print("control:", command)
                    if command == "away":
                        manual_away = True
                    elif command == "back":
                        manual_away = False
                    elif command == "reboot":
                        # A restart drops the link and loses the clock; stay away until "back".
                        manual_away = True
                        await advertise(False, why="Restarting: link dropped, clock lost")
                        wearable.reboot()
                    elif command in ("clench", "relax"):
                        wearable.emg_level = 2300 if command == "clench" else 300
            if args.control:
                await advertise(not manual_away)
            if args.scenario == "out-of-range":
                await advertise(not (away_from <= t < away_until))
            if args.scenario == "emg":
                cycle = (t - started) % 30
                wearable.emg_level = 2300 if cycle >= 25 else 300
            if advertising:
                connected = await server.is_connected()
                if connected and not wearable.connected:
                    print("Phone connected")
                    wearable.connect()
                elif not connected and wearable.connected:
                    print("Phone disconnected")
                    wearable.disconnect()
            if t >= next_line:
                wearable.tick_line()
                next_line += LINE_INTERVAL
            wearable.service()
            if t - last_report >= 10:
                last_report = t
                c = wearable.counters
                print(f"[{int(t - started)} s] generated {c['generated']}, live {c['live_sent']}, stored {c['stored']}, "
                      f"replayed {c['replayed']}, lost {c['lost_sent']}, waiting {wearable.next_seq - 1 - wearable.acked_seq}, "
                      f"acked up to {wearable.acked_seq}, rewinds {c['rewinds']}")
            await asyncio.sleep(0.02)
    finally:
        await advertise(False, why="Stopped")
    return 0


def read_commands(path):
    """Commands written to the control file (one per line), which is then emptied."""
    try:
        with open(path, "r+", encoding="utf-8") as f:
            commands = [line.strip() for line in f.read().splitlines() if line.strip()]
            f.seek(0)
            f.truncate()
        return commands
    except FileNotFoundError:
        return []


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--selftest", action="store_true", help="check the protocol against a simulated phone, no Bluetooth")
    parser.add_argument("--scenario", default="normal", choices=["normal", "out-of-range", "reboot", "emg", "flaky"])
    parser.add_argument("--seconds", type=float, default=0, help="stop after this long (0 = until Ctrl+C)")
    parser.add_argument("--away-after", type=float, default=60, help="out-of-range: seconds before going away")
    parser.add_argument("--away-minutes", type=float, default=5, help="out-of-range: minutes away")
    parser.add_argument("--control", help="file to write commands to while running: away, back, reboot, clench, relax")
    args = parser.parse_args()
    if args.selftest:
        sys.exit(0 if selftest() else 1)
    try:
        sys.exit(asyncio.run(run_ble(args)))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
