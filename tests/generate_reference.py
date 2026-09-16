"""Regenerate independent pass fixtures: pip install skyfield==1.55 skyfield-data==7.0.0.

Run from the project folder. Uses the packaged JPL DE421 ephemeris; no live API calls.
The orbit is synthetic (ISS-like), so fixtures never claim historical ISS sightings.
"""
import json
from pathlib import Path

import numpy as np
from sgp4.api import Satrec, WGS72
from skyfield.api import EarthSatellite, load, load_file, wgs84
from skyfield_data import get_skyfield_data_path

ts = load.timescale(builtin=True)
epoch = ts.utc(2024, 1, 1, 12)
omm = dict(NORAD_CAT_ID=25544, OBJECT_NAME="ISS (synthetic test orbit)",
           EPOCH="2024-01-01T12:00:00.000000", MEAN_MOTION=15.5,
           ECCENTRICITY=0.0005, INCLINATION=51.64, RA_OF_ASC_NODE=120,
           ARG_OF_PERICENTER=90, MEAN_ANOMALY=0, BSTAR=0.0003)
model = Satrec()
# SGP4's epoch is UTC; avoid the UT1-UTC correction applied by the timescale.
model.sgp4init(WGS72, 'i', 25544, 2460311.0 - 2433281.5, omm['BSTAR'], 0, 0,
              omm['ECCENTRICITY'], np.radians(omm['ARG_OF_PERICENTER']),
              np.radians(omm['INCLINATION']), 0,
              omm['MEAN_MOTION'] * 2 * np.pi / 1440, np.radians(omm['RA_OF_ASC_NODE']))
satellite = EarthSatellite.from_satrec(model, ts)
ephemeris = load_file(str(Path(get_skyfield_data_path()) / 'de421.bsp'))
earth, sun = ephemeris['earth'], ephemeris['sun']
seconds = np.arange(-1200, 172801, 2)
times = ts.utc(2024, 1, 1, 12, 0, seconds)
position = satellite.at(times).position.km
sun_position = (sun - earth).at(times).position.km - position
radius = np.linalg.norm(position, axis=0)
distance = np.linalg.norm(sun_position, axis=0)
separation = np.arccos(np.clip(np.sum(-position * sun_position, axis=0) / (radius * distance), -1, 1))
sunlit = separation > np.arcsin(6378.137 / radius) + np.arcsin(695700 / distance)
result = []
for latitude, longitude, altitude in [(40, -105, 1609), (-33.86, 151.2, 50), (51.5, 0, 0), (0, -78, 100), (69, 18, 10)]:
    observer = wgs84.latlon(latitude, longitude, elevation_m=altitude)
    elevation, azimuth, _ = (satellite - observer).at(times).altaz()
    solar_alt, _, _ = (earth + observer).at(times).observe(sun).apparent().altaz()
    visible = (elevation.degrees >= 10) & (solar_alt.degrees <= -6) & sunlit
    passes, start = [], None
    for i, active in enumerate(visible):
        if active and start is None:
            start = i
        elif not active and start is not None:
            if start > 0 and seconds[i - 1] > 0 and seconds[i - 1] - seconds[start] >= 30:
                passes.append(dict(start=int(1704110400000 + seconds[start] * 1000),
                                   end=int(1704110400000 + seconds[i - 1] * 1000),
                                   peak=float(np.max(elevation.degrees[start:i]))))
            start = None
    result.append(dict(latitude=latitude, longitude=longitude, altitude=altitude,
                       start=1704110400000, omm=omm, passes=passes[:10]))
Path('tests/skyfield-passes.json').write_text(json.dumps(result, indent=2) + '\n')
print([(r['latitude'], len(r['passes'])) for r in result])
