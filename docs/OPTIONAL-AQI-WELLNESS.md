# Optional AQI and local wellness journal

## Product behavior

Home now contains Air quality and Sleep & stress cards. AQI is off by default. Enable it,
search a city, and select a result. No GPS permission is requested. City searches and selected
city-centre coordinates reach Open-Meteo; requests inherently disclose an IP address. No
health, journal, phone-number, or account data is part of either API request.

Current AQI uses Open-Meteo/CAMS modeled US AQI, explicitly labeled as a regional estimate
and not India’s National AQI or an on-body measurement. Automatic refresh runs while Home
is visible, no more than once per 15 minutes after success. Failures are throttled to a
minute. Requests time out after 10 seconds and disconnect on cancellation. The last good
value and timestamps survive failures, process death and restart. A model timestamp older
than 3 hours is marked stale. Location changes invalidate the previous city's cache. Disable
stops updates; Remove city clears the location and cached value. This data never triggers SOS.

Sleep entries are user-reported bedtime, wake time, awake minutes and quality (1–5). Date
means wake-up date. Nighttime and daytime sleep are supported; equal times are rejected.
Stress is a daily user-reported 1–5 scale. These are not inferred sleep stages, clinically
validated stress levels, or an AI diagnosis. The dashboard shows seven-day trends with gaps
excluded from averages. One entry per type/date, up to 365 of each type; saving a date updates
it. Individual entries and the entire journal can be deleted. Journal data remains in app
private preferences, excluded from cloud backup under the existing app rules.

## Problem-statement coverage

- Adds optional environmental context with an honest offline cache.
- Adds local sleep-pattern logging and stress check-ins; automatic sleep/stress inference
  remains unimplemented and must not be presented as validated sensor analysis.
- Keeps physiological monitoring, local AI, anomaly detection and existing SOS independent
  of network availability. No AQI-driven emergency messaging is added.
- Does not claim dehydration diagnosis, medical validation or disaster prediction from AQI.

## Sources and deployment

- API: https://open-meteo.com/en/docs/air-quality-api
- City search / GeoNames: https://open-meteo.com/en/docs/geocoding-api
- Categories: https://www.airnow.gov/aqi/aqi-basics/
- Hosting terms: https://open-meteo.com/en/pricing

Open-Meteo is open source; its hosted free endpoint is for non-commercial evaluation and
prototyping. Commercial deployment requires a suitable commercial service licence or
self-hosting. Attribution to Open-Meteo/CAMS is visible in-app. No API key is bundled.

## Verification

Pure JVM tests cover disabled requests, cache persistence and reuse, failed/slow requests,
stale readings, location changes, disabling during a request, retry throttling, invalid values,
US AQI category boundaries, overnight/daytime sleep, input validation and gap-aware averages.
A public Berlin sample request was used to verify the provider response shape without sending
user location or health data. UI and physical-device checks are separate from those unit tests.

Final build verification: 578 unit tests passed; lint reported no errors; instrumented test sources compiled; signed release APK built successfully and its v2/v3 signatures verified. Instrumented tests were not executed on a device in this update.

## Visual refresh

AQI now has a 0–500 animated dial; sleep uses seven dated bar slots; stress uses an animated five-level indicator plus weekly bars. Cream/charcoal/amber styling follows the app theme. Graphics animate on becoming visible and on value changes, using Compose motion scaling. Displayed numbers remain the actual values. Missing logs are dashes, not zero measurements. AQI timestamps/cache status and self-report labels remain visible; explanations expand on demand.

Release build and signature verified. Installed as an update on the connected phone, preserving data. Inspected the existing AQI value and empty sleep/stress states in light mode; did not create or change journal entries. Screenshots are in artifacts/companion-visuals/. Populated sleep/stress animations and dark mode were not visually exercised on the phone.
