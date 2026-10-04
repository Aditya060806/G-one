# Your area: official alerts, city weather and India AQI

Optional, off by default, and separate from health monitoring: monitoring, detection and SOS
work the same with it off. It uses the city chosen in the **Air quality** card.

| Part | Source | What the app does with it |
|---|---|---|
| Official disaster alerts | NDMA **SACHET** public CAP feeds (`sachet.ndma.gov.in`) | Lists active alerts for the wearer's state, marks those that name their city or district, notifies new ones for the district at Moderate or above, shows what to do |
| City weather | Open-Meteo forecast API | Shows temperature, feels-like, humidity, UV; gives the heat rules an environment when the strap has no temperature sensor |
| Forecast hints | Open-Meteo daily maximum and rain | Flags heat-wave level heat and heavy / very heavy / extremely heavy rain in IMD's terms, labelled "from the forecast, not an official warning" |
| India AQI | Open-Meteo pollutant concentrations | Computes CPCB's National AQI next to the US AQI the Air quality card already shows |

## Official alerts

SACHET is the National Disaster Management Authority's Common Alerting Protocol system. Each
state has a public RSS feed (`rss_<state>.xml`, named after the state's first word: `rss_punjab`,
`rss_tamil`, `rss_uttar`, …) listing recent alerts; each item links to a CAP 1.2 message.

1. The app maps the city's state (from the geocoder) to its feed: [`SachetFeeds`](../app/src/main/java/com/gone/ai/health/disaster/DisasterRules.kt).
2. It downloads the **whole state's feed** and, for items from the last 48 hours it has not seen,
   up to 15 CAP messages per check: [`AreaRepository`](../app/src/main/java/com/gone/ai/health/environment/AreaRepository.kt).
3. It keeps alerts that are real (`status` Actual, not a cancellation) and not expired, in English
   with the local-language headline beside it: [`CapParser`](../app/src/main/java/com/gone/ai/health/disaster/CapAlert.kt).
4. An alert is **for your district** when its area text names the city or its district as a whole
   word: [`AreaMatch`](../app/src/main/java/com/gone/ai/health/disaster/DisasterRules.kt). SACHET's
   polygon files refuse scripted access (HTTP 403), so matching is by name.
5. New alerts for the district at Moderate, Severe or Extreme are notified once each, on their own
   "Area alerts" channel. The check runs about every 30 minutes in the background (JobScheduler,
   any network) and every minute while the Health tab is open, throttled to 15 minutes.

**Privacy.** No location is sent to SACHET: the request names only the state feed, and the
matching happens on the phone. City coordinates go to Open-Meteo, as for air quality.

**Safety of the parser.** Feeds come from the network, so the XML parser refuses DTDs and
external entities, only SACHET's own addresses are fetched, and responses are size-capped.

**Offline.** Alerts are saved on the phone and keep showing, with their expiry time, when the
network is gone. Each comes with short what-to-do guidance per hazard (heat, heavy rain or flood,
thunderstorm and lightning, cyclone, earthquake, cold, landslide, tsunami, air pollution),
summarised from NDMA's public do's and don'ts and always shown after the alert's own instruction:
[`DisasterGuidance`](../app/src/main/java/com/gone/ai/health/disaster/DisasterRules.kt).

## City weather in the heat rules

The strap has no ambient temperature sensor, so the heat rules (`env.heatStress`,
`env.dehydrationRisk`) had no environment to work with. Now, **only when the wearable sends no
ambient reading of its own**, they may use the city's weather if it is under 90 minutes old. Such
a finding:

- still needs the body's own response (resting heart rate of 100 or more, or a rising or raised
  core temperature) exactly as before;
- is capped at **MODERATE**;
- says in its text that the temperature is "the city's weather, not measured at the wrist", and
  records `"environment_source":"city"` in its evidence;
- **never sends an SOS**;
- is never written into a stored reading.

## Forecast hints (IMD terms)

| Hint | Criterion |
|---|---|
| Heavy rain | 64.5 mm or more in a day |
| Very heavy rain | 115.6 mm or more |
| Extremely heavy rain | 204.5 mm or more |
| Heat-wave level heat | Daily maximum 45 °C or more |
| Severe heat-wave level heat | 47 °C or more |

Only IMD's absolute heat-wave criterion is used; the departure-from-normal criterion needs a
climate normal for the place, which the app does not have. These are hints, not warnings.

## India AQI

[`IndiaAqi`](../app/src/main/java/com/gone/ai/health/environment/IndiaAqi.kt) applies CPCB's
breakpoints to 24-hour means of PM2.5, PM10, NO₂ and SO₂ and 8-hour means of O₃ and CO, needs at
least three pollutants including PM2.5 or PM10, and reports the largest sub-index and which
pollutant it came from. The concentrations are modelled for the city, not measured by a station.
Above the "Very Poor" band CPCB gives no upper concentration; the app extends the line to twice
the band edge for 401–500 and stops at 500.

## Tests

`DisasterTest` parses real SACHET documents saved on 2 October 2026 (a Punjab feed, a Tamil Nadu
rain alert, a bilingual Punjab thunderstorm alert), and checks feed names, district matching,
hazard kinds and the IMD terms. `AreaRepositoryTest` checks the refresh logic against a fake
network: only the district's new alerts are announced, once; expired alerts drop out; one failing
part keeps the others; switching off forgets everything. `IndiaAqiTest` checks the CPCB
arithmetic. `AnomalyRulesTest` and `MonitoringPipelineTest` check the heat rules with city weather.

## Limits

- Matching by district name can miss an alert whose area text spells the district differently.
  "Other alerts in your state" are always listed beneath.
- Background checks depend on Android's scheduling; a notification can arrive minutes after an
  alert is published.
- A notification depends on the notification permission and the "Area alerts" channel being on.
- Checked on the phone (4 October 2026): weather, India AQI, the state feed and a CAP alert all
  download and parse, and a full refresh fills all three (`AreaLiveNetworkTest`, fixed public city).
  Not yet observed: a live notification from a real alert for the wearer's own district.
