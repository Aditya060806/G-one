# Five-minute demo

Built only from what works today. Everything below runs on the phone, offline, except step 4
(official alerts need a connection the first time, then stay cached).

**Before you start:** install the debug APK; profile saved with a test emergency contact you own;
Automatic SOS on (Settings) with the fall window at 20 s; city chosen in the Air quality card and
"Your area" switched on once while online; the strap charged and paired, or the simulator ready.

| # | Show | Where | Say |
|---|---|---|---|
| 1 | Live strap readings: movement, skin temperature, EMG | Monitor | "The wearable streams over Bluetooth; the phone stores a reading every 5 seconds. Heart rate needs a resistor fix on the strap, so heart-rate demos use the simulator and recorded data." |
| 2 | A hard jolt (≥ 5 g): alarm and **"I'm OK, don't send"** countdown | Any screen | "Falls get a 20-second window because a knock looks like a fall. Tap I'm OK and nothing is sent. Low oxygen or a dangerous heart rate never wait." |
| 3 | The alert's wording appears at once, then may be reworded | Trails | "A fixed rule raised it with a written explanation. The on-phone language model may reword it later; it can't change it." |
| 4 | Official NDMA alerts for the district, with what to do | Health → Your area | "Straight from SACHET, the government's alert system. The phone downloads the state feed and picks out the district itself, so no location goes to the government server." |
| 5 | City weather and India AQI | Health → Your area | "When the strap has no thermometer, the heat rules can use the city's weather, capped at a warning and never an SOS." |
| 6 | Model Lab → Fall detector → replay, then live | Tools → Model Lab | "Trained on two public fall datasets, tested on people it never saw: 96 % of falls caught against 51 % for the 5 g rule. Drop the phone on a cushion: scored 3 s later, on the phone." |
| 7 | Model Lab → Heart rate in context → add the made-up rise | Tools → Model Lab | "The model expects a heart rate for the movement. Add a 30-beat rise while sitting and the rule flags it at minute 11; on the real recording it stays quiet. 2 false alarms a day against 52 for a fixed threshold." |
| 8 | Time it on this phone | Model Lab | "Each model is a few kilobytes and answers in well under a millisecond. On Qualcomm's AI Hub the same files run entirely on the Snapdragon NPU; those timings are listed here too." |
| 9 | A session report as PDF | Monitor → Reports | "Every number in it comes from stored readings." |
| 10 | Emergency ID on NFC/QR | Settings → Emergency ID | "A stable link on a tag; the record behind it updates." |

**If something fails:** the simulator (Device screen) produces every alert type without the
strap; the Model Lab replays need no network and no strap.

**Do not claim:** clinical accuracy, that the fall model runs on the strap (it runs on the phone;
the strap still uses the 5 g rule), that the app itself uses a Snapdragon NPU (the Snapdragon
figures are from Qualcomm AI Hub's hosted phones; the app runs the models on the CPU), or that a
real SMS has been tested unless it has.
