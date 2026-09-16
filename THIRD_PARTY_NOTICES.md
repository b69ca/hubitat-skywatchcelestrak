# Third-party notices

## satellite.js 5.0.0

The near-Earth SGP4 routines in `SkywatchCelesTrak.groovy` are adapted from
[satellite.js 5.0.0](https://www.npmjs.com/package/satellite.js/v/5.0.0).
The original implementation follows David Vallado and colleagues' SGP4 work.
The Groovy port retains the near-Earth equations, rejects unsupported deep-space
orbits, and replaces JavaScript objects with Groovy maps. The full upstream
license is also embedded in the driver so it accompanies standalone imports.

MIT License

Copyright (C) 2013 Shashwat Kandadai, UCSC Jack Baskin School of Engineering

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


## Reference data

The numeric SGP4 test vectors are from the Vallado verification dataset distributed with python-sgp4. See [tests/README.md](tests/README.md) for sources. Independent viewing-window fixtures were generated with Skyfield and JPL DE421 using a synthetic orbit. These development tools are not bundled with or required by the driver.
