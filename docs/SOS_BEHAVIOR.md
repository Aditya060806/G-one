# Automatic SOS

When it is switched on, the app texts the emergency contact for every confirmed live anomaly
from the wearable, including LOW and MODERATE findings. Two kinds of event never text anyone:
the model-based early warning (`ml.hrContext`) and heat events that rest on city weather rather
than a sensor on the wrist. Simulated, replayed and historical readings never send either.

## A fall can be stopped; nothing else waits

| Alert | What happens |
|---|---|
| Possible fall (impact of at least **5.0 g**) | **Sent at once, by default.** The wearer asked for a hard impact to reach their contact immediately, so there is no window to stop it; the cost is that a hard knock against a table texts them too. Settings offers a 10, 20 or 30 second **"I'm OK, don't send"** countdown, with an alarm and vibration, for anyone who wants the chance to cancel. |
| Everything else (low oxygen, heart rate, temperature, …) | Sent after 1.5 seconds, with no countdown. The 1.5 seconds only gathers alerts raised on the same reading into one message. |
| A fall, then another alert within 1.5 seconds | One message carries both reasons. With a countdown chosen, a second alert during it ends the countdown and sends at once. |
| Another alert, then a fall within 1.5 seconds | Sent at once with both reasons; the fall does not delay it. |

Why only falls: a fall is one hard impact, and a wrist knocked against a table makes the same
impact, so the wearer is given the chance to say so. The other alerts are measured over time
and a person who is unconscious cannot cancel anything.

Stopping monitoring in the app during a countdown counts as "I'm OK". If Android ends
monitoring during a countdown, the SOS is sent at once rather than lost.

## The fall rule itself

The fall rule fires on the reading that carries the impact; it does not wait to see whether the
wearer then lies still. The threshold is 5.0 g (previously 2.5 g), so ordinary knocks do not
raise fall events or occupy the fall cooldown before a harder impact. This is an engineering
setting, not a clinically validated fall classifier. On public wrist recordings a 5 g threshold
caught about three falls in four and also fired on jogging, clapping and hitting a table; see
[`MODELS.md`](MODELS.md) for the measured figures and for the trained fall model that is being
shown beside it. Missing motion readings are never treated as evidence of stillness.

## Sending

- Enable Automatic SOS in Settings after saving the emergency contact. Grant Android's SMS
  permission once. No per-event approval is required.
- Countdown lengths saved by earlier versions, which applied to every alert, are ignored.
- Detection still uses its signal checks, duration requirements and per-type cooldowns. The
  live aggregation bucket is five seconds; an SMS follows a detected anomaly, not every raw
  sensor sample.
- Calls are optional, separately permissioned, and off by default. A denied call permission
  does not prevent the text. The countdown says "text, then call" only when calling is on.
- The phone needs a working SIM, SMS service and mobile signal. No cloud service is used. A
  successful send callback is not proof the contact read the message.
- Notification failures do not block the SMS. Sender exceptions are recorded as failures
  instead of escaping the SOS worker.

The test suite uses fake senders. Actual SMS receipt, the alarm on a locked screen and motion
sensitivity must be checked on the phone and wearable; building an APK does not verify those.
