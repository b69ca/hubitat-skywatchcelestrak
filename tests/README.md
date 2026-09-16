# Tests

Run `groovy tests/SkywatchCelesTrakTest.groovy` from the repository root with Groovy 2.4.21. Hubitat services are simulated; state is round-tripped through JSON during batched calculations.

## SGP4 vectors

`vallado-vectors.json` contains 158 near-Earth reference records from the Vallado SGP4 verification dataset, distributed by [python-sgp4](https://github.com/brandon-rhodes/python-sgp4/tree/master/sgp4): `SGP4-VER.TLE` and `tcppver.out`.

The records cover nine satellite element sets. Cases whose orbital period is at least 225 minutes are excluded because the driver deliberately does not implement deep-space propagation. Tests compare all three position components with a 0.00002 km tolerance and velocity components with a 0.00000002 km/s tolerance. The port agrees much more closely than these thresholds; this validates the implementation against the reference algorithm, not the accuracy of real satellite predictions.

The underlying verification material comes from David Vallado and colleagues' [Revisiting Spacetrack Report #3](https://celestrak.org/publications/AIAA/2006-6753/). Numeric reference records are not live orbital data.

## Independent viewing windows

`skyfield-passes.json` uses a synthetic ISS-like orbit at a fixed 2024 epoch and five generic observer sites spanning both hemispheres, the equator, and high latitudes. It contains eight qualifying windows and two no-window cases. No household coordinates are included.

`generate_reference.py` regenerates the fixtures with Python, Skyfield 1.55, skyfield-data 7.0.0, and its packaged JPL DE421 ephemeris. Install those packages in a local virtual environment, then run the script from the repository root. It requires no live CelesTrak request.

Skyfield independently handles reference frames, observer geometry, and the solar ephemeris. Its two-second sampling and the driver's approximate solar model warrant a 15-second boundary tolerance and 0.25-degree peak tolerance. The test also checks the complete window count at each location. These tolerances describe this fixture comparison, not a guarantee for every orbit or date.

## Driver behavior

Tests cover epoch parsing, malformed/stale orbital responses, deep-space rejection, custom zero coordinates, eclipse geometry, asynchronous callback identity, download limits across initialization, JSON persistence, forecast batching, reminder quiet hours, duplicate events after recalculation, manual test reminders, cache preservation, position freshness, and end events at the forecast boundary.

Real Hubitat compilation and operation must still be checked after platform-specific changes. A simulated scheduler cannot establish loaded-hub timing or future outdoor observing accuracy.
