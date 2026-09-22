# Automatic SOS

The current app sends an SMS for every confirmed live anomaly from the wearable,
including LOW and MODERATE findings. Motion is the exception: a finite impact of
at least **5.0 g** is required. A smaller knock followed by stillness cannot bypass
this limit. Missing motion readings are not evidence of immobility.

The fall detector also uses 5.0 g (previously 2.5 g), so ordinary knocks do not
raise fall events or occupy the fall cooldown before a harder impact. This is an
engineering setting, not a clinically validated fall classifier. Increasing it
reduces sensitivity and may miss lower-impact falls; validate it on the mounted
wearable. The firmware already reports acceleration using its ±8 g range.

## Sending

- Enable Automatic SOS in Settings after saving the emergency contact. Grant
  Android's SMS permission once. No per-event approval is required.
- There is no user countdown. Older saved countdown values are ignored. The app
  combines alerts for 1.5 seconds to avoid separate texts for the same reading.
- Detection still uses its existing signal checks, duration requirements and
  per-type cooldowns. The live aggregation bucket is five seconds; SMS starts
  after an anomaly is detected, not on every raw sensor sample.
- Simulated samples and historical replay never send automatic SOS messages.
- Calls are optional, separately permissioned, and disabled by default for new
  settings. Existing calling preferences remain in effect. Denied call permission
  does not prevent sending SMS.
- The phone needs a working SIM, SMS service and mobile signal. No cloud service
  is used. A successful send callback is not proof the contact read the message.
- Notification failures do not block SMS. Sender exceptions are recorded as
  failures instead of escaping the SOS worker.

The test suite uses fake senders. Actual SMS receipt, locked-screen behavior and
motion sensitivity must be checked on the phone and wearable; building an APK
does not verify those device-dependent behaviors.
