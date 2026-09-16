/*
 * Skywatch (CelesTrak) — local visible satellite pass predictions for Hubitat.
 * Copyright 2026 Jon Wallace. MIT License; SGP4 attribution is embedded below.
 * No API key. Observer coordinates never leave the hub.
 * Predictions describe viewing geometry, not guaranteed naked-eye visibility.
 * Version 1.0.0
 */

metadata {
  definition(name: "Skywatch (CelesTrak)", namespace: "jonw", author: "Jon Wallace",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/b69ca/hubitat-skywatchcelestrak/main/SkywatchCelesTrak.groovy") {
    capability "Sensor"
    capability "Refresh"
    capability "Initialize"
    capability "PushableButton"
    command "recalculate"
    command "resumeDownloads"
    command "testReminder"
    attribute "satelliteName", "string"
    attribute "catalogNumber", "number"
    attribute "passState", "enum", ["unknown", "none", "upcoming", "approaching", "visible"]
    attribute "dataStatus", "enum", ["unavailable", "fresh", "cached", "stale"]
    attribute "downloadStatus", "string"
    attribute "predictionStatus", "string"
    attribute "orbitalEpoch", "string"
    attribute "orbitalAgeHours", "number"
    attribute "lastUpdated", "string"
    attribute "lastError", "string"
    attribute "predictionsThrough", "string"
    attribute "passCount", "number"
    attribute "nextPassStart", "string"
    attribute "nextPassEnd", "string"
    attribute "nextPassStartEpoch", "number"
    attribute "nextPassEndEpoch", "number"
    attribute "minutesUntilPass", "number"
    attribute "maximumElevation", "number"
    attribute "visibleDuration", "number"
    attribute "startDirection", "string"
    attribute "endDirection", "string"
    attribute "passSummary", "string"
    attribute "upcomingPasses", "string"
    attribute "notificationText", "string"
    attribute "reminderType", "string"
    attribute "azimuth", "number"
    attribute "elevation", "number"
    attribute "rangeKm", "number"
    attribute "positionStatus", "enum", ["disabled", "current", "unavailable"]
    attribute "positionTime", "string"
  }
  preferences {
    input name: "setupNotes", type: "paragraph", title: "Skywatch 1.0.0", description: "Uses the hub location unless overridden. Viewing windows require a dark sky, a sunlit satellite, and the elevation limits below. No account or API key is required."
    input name: "satellite", type: "enum", title: "Satellite", options: ["25544":"ISS", "48274":"Tiangong", "20580":"Hubble", "custom":"Custom low-Earth-orbit satellite"], defaultValue: "25544"
    input name: "customCatalogNumber", type: "number", title: "Custom NORAD catalog number (only with Custom selected)", range: "1..999999999"
    input name: "useCustomLocation", type: "bool", title: "Use custom observer coordinates", defaultValue: false
    input name: "observerLatitude", type: "decimal", title: "Custom latitude (north positive)", range: "-90..90"
    input name: "observerLongitude", type: "decimal", title: "Custom longitude (east positive)", range: "-180..180"
    input name: "observerAltitude", type: "number", title: "Observer altitude above sea level (meters)", range: "-500..9000", defaultValue: 0
    input name: "horizonHours", type: "enum", title: "Look ahead", options: ["12":"12 hours", "24":"24 hours", "48":"48 hours", "72":"72 hours"], defaultValue: "48"
    input name: "minimumElevation", type: "number", title: "Minimum viewing elevation (degrees above horizon)", range: "5..60", defaultValue: 10
    input name: "minimumPeak", type: "number", title: "Minimum peak elevation during the visible portion (degrees)", range: "5..90", defaultValue: 20
    input name: "minimumDuration", type: "number", title: "Minimum continuous visible duration (seconds)", range: "30..600", defaultValue: 60
    input name: "sunLimit", type: "enum", title: "Sky darkness: Sun must be below", options: ["-0.833":"Horizon (bright twilight)", "-3":"3° (bright satellites)", "-6":"6° (civil twilight)", "-12":"12° (nautical twilight)", "-18":"18° (astronomical night)"], defaultValue: "-6"
    input name: "leadMinutes", type: "number", title: "Advance reminder (minutes)", range: "1..60", defaultValue: 5
    input name: "remindersEnabled", type: "bool", title: "Emit advance reminder events (button 1)", defaultValue: true
    input name: "reminderStartHour", type: "number", title: "Reminders allowed from hour (0–23, hub local time)", range: "0..23", defaultValue: 0
    input name: "reminderEndHour", type: "number", title: "Reminders allowed until hour (1–24; same as start means all day)", range: "1..24", defaultValue: 24
    input name: "downloadNotes", type: "paragraph", title: "Orbital data", description: "Keep one device per satellite. Refresh and Resume Downloads respect a two-hour minimum between requests. Provider errors pause downloads; cached predictions continue while the elements remain within the age limit."
    input name: "downloadHours", type: "enum", title: "Orbital data refresh interval", options: ["2":"2 hours", "4":"4 hours", "6":"6 hours", "12":"12 hours"], defaultValue: "6"
    input name: "maxAgeHours", type: "enum", title: "Maximum orbital age, including predicted times", options: ["24":"24 hours (strict)", "48":"48 hours", "72":"72 hours", "120":"5 days (less accurate)", "168":"7 days (less accurate)"], defaultValue: "72"
    input name: "sampleSeconds", type: "enum", title: "Prediction sampling", options: ["15":"15 seconds (more hub work)", "30":"30 seconds"], defaultValue: "30"
    input name: "maximumPasses", type: "number", title: "Maximum retained viewing windows", range: "1..30", defaultValue: 10
    input name: "currentPosition", type: "bool", title: "Update current direction, elevation and range every minute", defaultValue: false
    input name: "logEnable", type: "bool", title: "Debug logging (automatically disables after 30 minutes)", defaultValue: false
  }
}

def installed() {
  initialize()
}

def updated() {
  initialize()
}

def uninstalled() {
  unschedule()
}

def initialize() {
  unschedule()
  state.generation = (state.generation ?: 0L) + 1L
  state.job = null
  state.inFlight = null
  state.passes = []
  state.predictionEnd = 0L
  sendEvent(name: "numberOfButtons", value: 3)
  sendEvent(name: "predictionStatus", value: "waiting")
  if (settings?.logEnable) runIn(1800, "logsOff")
  runEvery1Minute("tick")
  runEvery1Hour("maintenance")
  refresh()
}

def logsOff() {
  device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def maintenance() {
  refresh()
}

def refresh() {
  try {
    int id = selectedCatalog()
    observerConfig() // Fail before a download when configuration is incomplete.
    long current = now()
    boolean same = state.orbit?.catalog == id
    long attempt = (state.lastAttempt ?: 0L) as Long
    long success = (state.fetchedAt ?: 0L) as Long
    long interval = settingNumber("downloadHours", 6, 2, 12).longValue() * 3600000L
    // All commands and configuration changes share the persisted two-hour gate.
    boolean due = !same || current - success >= interval
    if (due && !state.fetchPaused && !state.inFlight && current - attempt >= 7200000L) {
      if (settings?.logEnable) log.debug "Requesting orbital elements for catalog ${id}"
      String token = "${current}-${state.generation}"
      state.lastAttempt = current
      state.inFlight = token
      sendEvent(name:"downloadStatus", value:"fetching")
      asynchttpGet("orbitalResponse", [uri:"https://celestrak.org/NORAD/elements/gp.php",
        query:[CATNR:id.toString(), FORMAT:"json"], contentType:"application/json", timeout:20,
        headers:["User-Agent":"Hubitat-Skywatch/1.0 (github.com/b69ca/hubitat-skywatchcelestrak)"]],
        [token:token, catalog:id])
    } else if (state.fetchPaused) {
      sendEvent(name:"downloadStatus", value:"paused — see lastError; use Resume Downloads after resolving")
    } else if (!state.inFlight) {
      sendEvent(name:"downloadStatus", value:due ? "waiting for two-hour request limit" : "using cached elements")
    }
    if (same && !state.job) beginPrediction()
    tick()
  } catch (Exception e) {
    recordError("Configuration/request: ${e.message}")
    if (state.inFlight) pauseDownloads("Request could not be started: ${e.message}")
    invalidatePredictions("configuration error")
  }
}

def orbitalResponse(resp, data) {
  if (!data || data.token != state.inFlight) return
  state.inFlight = null
  try {
    if (resp.hasError() || resp.status != 200) {
      pauseDownloads("CelesTrak request failed (HTTP ${resp.status}). Automatic downloads paused to respect provider policy.")
      tick()
      return
    }
    def rows = resp.getJson()
    if (!(rows instanceof List)) throw new IllegalArgumentException("Expected a JSON array of orbital elements")
    def row = rows.find { it instanceof Map && it.NORAD_CAT_ID?.toString() == data.catalog.toString() }
    if (!row) throw new IllegalArgumentException("No elements for catalog ${data.catalog}")
    Map orbit = parseElements(row, now())
    if (data.catalog as Integer != selectedCatalog()) return
    // Reject unsupported models/invalid orbital parameters before replacing cached data.
    sgp4Initialize(orbit)
    state.orbit = orbit
    state.fetchedAt = now()
    state.fetchPaused = false
    sendEvent(name:"catalogNumber", value:orbit.catalog)
    sendEvent(name:"satelliteName", value:orbit.name)
    sendEvent(name:"orbitalEpoch", value:utcText(orbit.epoch as Long))
    sendEvent(name:"lastUpdated", value:localText(now()))
    sendEvent(name:"lastError", value:"")
    sendEvent(name:"downloadStatus", value:"success")
    beginPrediction()
  } catch (Exception e) {
    pauseDownloads("Orbital response rejected: ${e.message}")
  }
  tick()
}

def pauseDownloads(String message) {
  state.inFlight = null
  state.fetchPaused = true
  sendEvent(name:"downloadStatus", value:"paused")
  recordError(message)
}

def resumeDownloads() {
  state.fetchPaused = false
  // This deliberately does not reset lastAttempt or bypass provider limits.
  refresh()
}
def recalculate() {
  try { beginPrediction(); tick() }
  catch (Exception e) { recordError(e.message); invalidatePredictions("configuration error") }
}

Map parseElements(Map row, long current) {
  if (row.EPHEMERIS_TYPE != null && row.EPHEMERIS_TYPE.toString() != "0") throw new IllegalArgumentException("Only standard SGP4 GP elements are supported")
  if (row.CENTER_NAME && row.CENTER_NAME != "EARTH") throw new IllegalArgumentException("Earth-centered elements required")
  if (row.REF_FRAME && row.REF_FRAME != "TEME") throw new IllegalArgumentException("TEME elements required")
  if (row.TIME_SYSTEM && row.TIME_SYSTEM != "UTC") throw new IllegalArgumentException("UTC elements required")
  if (row.MEAN_ELEMENT_THEORY && row.MEAN_ELEMENT_THEORY != "SGP4") throw new IllegalArgumentException("SGP4 elements required")
  String epochText = row.EPOCH?.toString()
  if (!(epochText ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z?/)) throw new IllegalArgumentException("Invalid UTC epoch")
  // CelesTrak's OMM timestamps have no zone suffix; their time system is UTC.
  String base = epochText.substring(0, 19)
  def parser = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
  parser.setLenient(false)
  parser.setTimeZone(TimeZone.getTimeZone("UTC"))
  long epoch = parser.parse(base).time
  String fraction = epochText.length() > 19 && epochText.charAt(19) == '.' ? epochText.substring(20).replace("Z", "") : ""
  if (fraction) epoch += (fraction + "000").substring(0, 3).toLong()
  if (epoch > current + 3600000L) throw new IllegalArgumentException("Orbital epoch is unexpectedly in the future")
  if (current - epoch > settingNumber("maxAgeHours",72,24,168).longValue()*3600000L) throw new IllegalArgumentException("Orbital elements are older than the configured age limit")
  double motion = requiredNumber(row, "MEAN_MOTION", 0.01d, 18d)
  double radians = Math.PI / 180d
  return [catalog:requiredNumber(row,"NORAD_CAT_ID",1d,999999999d).intValue(), name:(row.OBJECT_NAME ?: "Satellite").toString().take(80), epoch:epoch,
    no:motion*2d*Math.PI/1440d, ecco:requiredNumber(row,"ECCENTRICITY",0d,0.999d),
    inclo:requiredNumber(row,"INCLINATION",0d,180d)*radians,
    nodeo:requiredNumber(row,"RA_OF_ASC_NODE",0d,360d)*radians,
    argpo:requiredNumber(row,"ARG_OF_PERICENTER",0d,360d)*radians,
    mo:requiredNumber(row,"MEAN_ANOMALY",0d,360d)*radians,
    bstar:requiredNumber(row,"BSTAR",-1d,1d)]
}

double requiredNumber(Map row, String key, double low, double high) {
  if (row[key] == null) throw new IllegalArgumentException("Missing ${key}")
  double value = Double.parseDouble(row[key].toString())
  if (Double.isNaN(value) || Double.isInfinite(value) || value < low || value > high) throw new IllegalArgumentException("Invalid ${key}")
  return value
}

def beginPrediction() {
  long current = now()
  Map observer = observerConfig()
  if (!usableOrbit(current)) { invalidatePredictions("waiting for fresh orbital data"); return }
  state.generation = (state.generation ?: 0L) + 1L
  long end = Math.min(current + settingNumber("horizonHours",48,12,72).longValue()*3600000L,
    (state.orbit.epoch as Long) + settingNumber("maxAgeHours",72,24,168).longValue()*3600000L)
  // Start 20 minutes back so a pass already underway retains its original start.
  state.job = [generation:state.generation, cursor:current-1200000L, end:end, requestedAt:current,
    observer:observer, passes:[], open:null, previous:null,
    minElevation:settingNumber("minimumElevation",10,5,60), minPeak:settingNumber("minimumPeak",20,5,90),
    minDuration:settingNumber("minimumDuration",60,30,600), sunLimit:settingNumber("sunLimit",-6,-18,0),
    step:settingNumber("sampleSeconds",30,15,30).longValue()*1000L, limit:settingNumber("maximumPasses",10,1,30).intValue()]
  sendEvent(name:"predictionStatus", value:"calculating")
  runIn(1,"predictionBatch",[data:[generation:state.generation]])
}

def predictionBatch(data) {
  Map job = state.job
  if (!job || data?.generation?.toString() != job.generation.toString()) return
  try {
    Map model = sgp4Initialize(state.orbit)
    int count = 0
    while ((job.cursor as Long) <= (job.end as Long) && count++ < 120 && job.passes.size() < job.limit) {
      Map sample = viewingSample(model, job.observer, job.cursor as Long, job)
      accumulateSample(job, model, sample)
      job.cursor = (job.cursor as Long) + (job.step as Long)
    }
    if ((job.cursor as Long) > (job.end as Long) || job.passes.size() >= job.limit) {
      // An unfinished window at the horizon is omitted rather than given a false end.
      state.passes = job.passes
      state.predictionEnd = job.passes.size() >= job.limit ? (job.cursor as Long) - (job.step as Long) : job.end
      state.job = null
      sendEvent(name:"predictionStatus", value:"ready")
      sendEvent(name:"predictionsThrough", value:localText(state.predictionEnd as Long))
      if (settings?.logEnable) log.debug "Calculated ${job.passes.size()} viewing windows"
      tick()
    } else {
      state.job = job
      runIn(1,"predictionBatch",[data:[generation:job.generation]])
    }
  } catch (Exception e) {
    state.job = null
    recordError("Prediction failed: ${e.message}")
    invalidatePredictions("error")
  }
}

def accumulateSample(Map job, Map model, Map sample) {
  Map previous = job.previous
  if (sample.visible && !job.open) {
    Map first = previous ? refineBoundary(model,job.observer,previous.time as Long,sample.time as Long,true,job) : sample
    job.open = [start:first.time, startAz:first.az, endAz:sample.az, peak:sample.el, peakAt:sample.time,
      clipped:previous == null]
  }
  if (sample.visible && job.open && sample.el > job.open.peak) {
    job.open.peak = sample.el
    job.open.peakAt = sample.time
  }
  if (!sample.visible && job.open) {
    Map last = refineBoundary(model,job.observer,previous.time as Long,sample.time as Long,false,job)
    Map pass = job.open
    pass.end = last.time
    pass.endAz = last.az
    // Refine the peak only within the visible segment, not the entire orbit pass.
    long lo = Math.max(pass.start as Long,(pass.peakAt as Long)-(job.step as Long))
    long hi = Math.min(pass.end as Long,(pass.peakAt as Long)+(job.step as Long))
    while (hi-lo > 1000L) {
      long a = lo + (long)((hi-lo)/3d), b = hi - (long)((hi-lo)/3d)
      if (viewingSample(model,job.observer,a,job).el < viewingSample(model,job.observer,b,job).el) lo=a
      else hi=b
    }
    Map peak = viewingSample(model,job.observer,lo+(long)((hi-lo)/2d),job)
    if (peak.el > pass.peak) { pass.peak=peak.el; pass.peakAt=peak.time }
    pass.duration = (long)(((pass.end as Long)-(pass.start as Long))/1000d)
    if (!pass.clipped && pass.end > job.requestedAt && pass.duration >= job.minDuration && pass.peak >= job.minPeak) job.passes << pass
    job.open = null
  }
  job.previous = sample
}

Map refineBoundary(Map model, Map observer, long lo, long hi, boolean entering, Map config) {
  while (hi-lo > 1000L) {
    long mid = lo+(long)((hi-lo)/2d)
    boolean visible = viewingSample(model,observer,mid,config).visible
    if (visible == entering) hi=mid else lo=mid
  }
  return viewingSample(model,observer,entering ? hi : lo,config)
}

def tick() {
  try {
    updatePassState()
  } catch (Exception e) {
    recordError("State update: ${e.message}")
    invalidatePredictions("configuration error")
    sendChanged("positionStatus", "unavailable")
  }
}

def updatePassState() {
  long current = now()
  // A lost callback must not silently prevent all subsequent downloads.
  if (state.inFlight && current-(state.lastAttempt as Long) > 120000L) pauseDownloads("No HTTP callback received; automatic downloads paused")
  boolean usable = usableOrbit(current)
  if (state.orbit) sendChanged("orbitalAgeHours",rounded((current-(state.orbit.epoch as Long))/3600000d,2))
  sendChanged("dataStatus", !state.orbit ? "unavailable" : !usable ? "stale" : state.fetchPaused || current-((state.fetchedAt ?: 0L) as Long)>settingNumber("downloadHours",6,2,12)*3600000d ? "cached" : "fresh")
  updatePosition(current, usable)
  // A boundary can coincide with the end of the retained forecast.
  Map started = state.emissions?.start
  if (usable && started && started.catalog == selectedCatalog() && current >= (started.end as Long) &&
      current-(started.end as Long) < 120000L && state.endedStart != started.start) {
    state.endedStart = started.start
    push(3)
  }
  if (!usable || current >= ((state.predictionEnd ?: 0L) as Long)) {
    clearPassAttributes("unknown")
    if (!state.job && usable && device.currentValue("predictionStatus") != "error") beginPrediction()
    return
  }
  List passes = (state.passes ?: []).findAll { (it.end as Long) > current }
  Map pass = passes ? passes[0] : null
  sendChanged("passCount",passes.size())
  sendChanged("upcomingPasses",passes.collect { passDescription(it) }.join("\n").take(6000))
  if (!pass) {
    clearPassAttributes("none")
  } else {
    String status = current >= pass.start ? "visible" : current >= (pass.start as Long)-settingNumber("leadMinutes",5,1,60)*60000d ? "approaching" : "upcoming"
    sendChanged("passState",status)
    sendChanged("nextPassStart",localText(pass.start as Long))
    sendChanged("nextPassEnd",localText(pass.end as Long))
    sendChanged("nextPassStartEpoch",(long)((pass.start as Long)/1000d))
    sendChanged("nextPassEndEpoch",(long)((pass.end as Long)/1000d))
    sendChanged("minutesUntilPass",Math.max(0,(int)Math.ceil(((pass.start as Long)-current)/60000d)))
    sendChanged("maximumElevation",rounded(pass.peak as Double,1))
    sendChanged("visibleDuration",pass.duration)
    sendChanged("startDirection",compass(pass.startAz as Double))
    sendChanged("endDirection",compass(pass.endAz as Double))
    sendChanged("passSummary",passDescription(pass))
    if (status == "approaching" && settings?.remindersEnabled != false && reminderAllowed(current) && !alreadyEmitted("reminder",pass)) {
      rememberEmission("reminder",pass)
      publishReminder(passDescription(pass),"advance")
    }
    if (status == "visible" && !alreadyEmitted("start",pass)) {
      rememberEmission("start",pass)
      push(2)
    }
  }
  unschedule("passBoundary")
  if (pass) {
    long lead = (pass.start as Long)-settingNumber("leadMinutes",5,1,60).longValue()*60000L
    List times = [lead,pass.start as Long,pass.end as Long].findAll { it > current }
    if (times) runIn(Math.max(1,(int)Math.ceil(((times.min() as Long)-current)/1000d)),"passBoundary")
  }
}

def updatePosition(long current, boolean usable) {
  if (!settings?.currentPosition || !usable) {
    sendChanged("positionStatus", settings?.currentPosition ? "unavailable" : "disabled")
    sendChanged("positionTime", "")
    return
  }
    try {
      Map sample=viewingSample(sgp4Initialize(state.orbit),observerConfig(),current,[minElevation:0d,sunLimit:0d])
      sendChanged("azimuth",rounded(sample.az as Double,1))
      sendChanged("elevation",rounded(sample.el as Double,1))
      sendChanged("rangeKm",rounded(sample.range as Double,1))
      sendChanged("positionTime", localText(current))
      sendChanged("positionStatus", "current")
    } catch (Exception e) {
      sendChanged("positionStatus", "unavailable")
      sendChanged("positionTime", "")
      recordError("Current position: ${e.message}")
    }
}

def passBoundary() { tick() }
boolean alreadyEmitted(String kind, Map pass) {
  Map prior=state.emissions?.get(kind)
  return prior && prior.catalog == state.orbit.catalog && (prior.start as Long) < (pass.end as Long) && (pass.start as Long) < (prior.end as Long)
}
def rememberEmission(String kind, Map pass) {
  Map emissions=state.emissions ?: [:]
  emissions[kind]=[catalog:state.orbit.catalog,start:pass.start,end:pass.end]
  state.emissions=emissions
}
def publishReminder(String text, String kind) {
  sendEvent(name:"reminderType",value:kind)
  sendEvent(name:"notificationText",value:text,isStateChange:true)
  push(1)
}
def testReminder() {
  publishReminder("TEST: Skywatch reminder. No real satellite pass is implied.", "test")
}
def push(button) {
  int number=button as Integer
  if (number >= 1 && number <= 3) sendEvent(name:"pushed",value:number,isStateChange:true,type:"digital")
}
boolean reminderAllowed(long time) {
  int hour=new Date(time).format("H",hubTimezone()).toInteger()
  int start=settingNumber("reminderStartHour",0,0,23).intValue(), end=settingNumber("reminderEndHour",24,1,24).intValue()
  return start == end || (start < end ? hour >= start && hour < end : hour >= start || hour < end)
}
String passDescription(Map pass) {
  return "${state.orbit?.name ?: 'Satellite'}: ${localText(pass.start as Long)} to ${new Date(pass.end as Long).format('HH:mm:ss z',hubTimezone())}; ${compass(pass.startAz as Double)} to ${compass(pass.endAz as Double)}, peak ${rounded(pass.peak as Double,1)}°, visible window ${pass.duration}s."
}
def clearPassAttributes(String status) {
  sendChanged("passState",status)
  ["nextPassStart","nextPassEnd","startDirection","endDirection","upcomingPasses"].each { sendChanged(it,"") }
  ["nextPassStartEpoch","nextPassEndEpoch","maximumElevation","visibleDuration","passCount"].each { sendChanged(it,0) }
  sendChanged("minutesUntilPass",-1)
  sendChanged("passSummary",status == "none" ? "No qualifying viewing windows in the calculated interval." : "Waiting for usable predictions; check dataStatus and predictionStatus.")
  unschedule("passBoundary")
}
def invalidatePredictions(String status) {
  state.job=null
  state.passes=[]
  state.predictionEnd=0L
  unschedule("predictionBatch")
  sendChanged("predictionStatus",status)
  sendChanged("predictionsThrough", "")
  clearPassAttributes("unknown")
}
boolean usableOrbit(long current) {
  try {
    return state.orbit && state.orbit.catalog == selectedCatalog() && current >= (state.orbit.epoch as Long)-3600000L && current < (state.orbit.epoch as Long)+settingNumber("maxAgeHours",72,24,168).longValue()*3600000L
  } catch (Exception ignored) { return false }
}
int selectedCatalog() {
  String selected=(settings?.satellite ?: "25544").toString()
  def value=selected == "custom" ? settings?.customCatalogNumber : selected
  double id=requiredNumber([catalog:value],"catalog",1d,999999999d)
  if (id != Math.floor(id)) throw new IllegalArgumentException("Catalog number must be an integer")
  return (int)id
}
Map observerConfig() {
  def latitude=settings?.useCustomLocation ? settings?.observerLatitude : location?.latitude
  def longitude=settings?.useCustomLocation ? settings?.observerLongitude : location?.longitude
  double lat=requiredNumber([latitude:latitude],"latitude",-90d,90d)*Math.PI/180d
  double lon=requiredNumber([longitude:longitude],"longitude",-180d,180d)*Math.PI/180d
  double altitude=settingNumber("observerAltitude",0,-500,9000)/1000d
  double sinLat=Math.sin(lat), cosLat=Math.cos(lat), sinLon=Math.sin(lon), cosLon=Math.cos(lon)
  double n=6378.137d/Math.sqrt(1d-0.00669437999014d*sinLat*sinLat)
  return [lat:lat,lon:lon,sinLat:sinLat,cosLat:cosLat,sinLon:sinLon,cosLon:cosLon,
    x:(n+altitude)*cosLat*cosLon,y:(n+altitude)*cosLat*sinLon,z:(n*(1d-0.00669437999014d)+altitude)*sinLat]
}
double settingNumber(String key,double fallback,double low,double high) {
  def raw=settings?.get(key)
  double value=raw == null ? fallback : Double.parseDouble(raw.toString())
  if (Double.isNaN(value) || Double.isInfinite(value) || value < low || value > high) throw new IllegalArgumentException("${key} must be ${low} to ${high}")
  return value
}
def recordError(String text) {
  sendEvent(name: "lastError", value: text.take(350))
  log.warn "Skywatch: ${text}"
}
def sendChanged(String name, value) {
  if (device.currentValue(name)?.toString() != value?.toString()) {
    sendEvent(name: name, value: value)
  }
}
def rounded(double value,int places) { return new BigDecimal(Double.toString(value)).setScale(places,BigDecimal.ROUND_HALF_UP) }
TimeZone hubTimezone() { return location?.timeZone ?: TimeZone.getTimeZone("UTC") }
String localText(long time) { return new Date(time).format("yyyy-MM-dd HH:mm:ss z",hubTimezone()) }
String utcText(long time) { return new Date(time).format("yyyy-MM-dd'T'HH:mm:ss'Z'",TimeZone.getTimeZone("UTC")) }
String compass(double az) { return ["N","NNE","NE","ENE","E","ESE","SE","SSE","S","SSW","SW","WSW","W","WNW","NW","NNW"][((int)Math.floor((az+11.25d)/22.5d)) % 16] }

// Viewing geometry: TEME satellite position, WGS-84 observer, approximate Sun of date.
Map viewingSample(Map model,Map observer,long time,Map config) {
  Map pv=sgp4Position(model,(time-(model.epoch as Long))/60000d)
  if (!pv) throw new IllegalArgumentException("SGP4 propagation failed (code ${model.error})")
  double jd=time/86400000d+2440587.5d
  double gst=siderealTime(jd)
  Map look=lookAngles(pv.position,observer,gst)
  Map sun=sunVector(jd)
  Map sunLook=lookAngles(sun,observer,gst)
  boolean lit=fullySunlit(pv.position,sun)
  return [time:time,az:look.az,el:look.el,range:look.range,
    visible:look.el >= config.minElevation && sunLook.el <= config.sunLimit && lit,
    sunElevation:sunLook.el,sunlit:lit]
}
Map lookAngles(Map position,Map observer,double gst) {
  double x=position.x*Math.cos(gst)+position.y*Math.sin(gst)-observer.x
  double y=-position.x*Math.sin(gst)+position.y*Math.cos(gst)-observer.y
  double z=position.z-observer.z
  double east=-observer.sinLon*x+observer.cosLon*y
  double north=-observer.sinLat*observer.cosLon*x-observer.sinLat*observer.sinLon*y+observer.cosLat*z
  double up=observer.cosLat*observer.cosLon*x+observer.cosLat*observer.sinLon*y+observer.sinLat*z
  double range=Math.sqrt(x*x+y*y+z*z)
  return [az:positiveAngle(Math.atan2(east,north))*180d/Math.PI,el:Math.asin(clamp(up/range))*180d/Math.PI,range:range]
}
double siderealTime(double jd) {
  double t=(jd-2451545d)/36525d
  return positiveAngle((-6.2e-6d*t*t*t+0.093104d*t*t+(876600d*3600d+8640184.812866d)*t+67310.54841d)*Math.PI/180d/240d)
}
Map sunVector(double jd) {
  // Low-precision solar ephemeris (Astronomical Almanac / CelesTrak observing guidance).
  // Mean equinox of date approximation; this is not a transit/contact-time calculator.
  double n=jd-2451545d, rad=Math.PI/180d
  double g=positiveAngle((357.528d+0.9856003d*n)*rad)
  double lambda=(280.460d+0.9856474d*n+1.915d*Math.sin(g)+0.020d*Math.sin(2d*g))*rad
  double epsilon=(23.439d-0.0000004d*n)*rad
  double r=(1.00014d-0.01671d*Math.cos(g)-0.00014d*Math.cos(2d*g))*149597870.7d
  return [x:r*Math.cos(lambda),y:r*Math.cos(epsilon)*Math.sin(lambda),z:r*Math.sin(epsilon)*Math.sin(lambda)]
}
boolean fullySunlit(Map satellite,Map sun) {
  double r=Math.sqrt(satellite.x*satellite.x+satellite.y*satellite.y+satellite.z*satellite.z)
  double sx=sun.x-satellite.x,sy=sun.y-satellite.y,sz=sun.z-satellite.z
  double distance=Math.sqrt(sx*sx+sy*sy+sz*sz)
  double separation=Math.acos(clamp(-(satellite.x*sx+satellite.y*sy+satellite.z*sz)/(r*distance)))
  // Conservative: omit partial eclipse as well as full shadow. No atmospheric model.
  return separation > Math.asin(clamp(6378.137d/r))+Math.asin(clamp(695700d/distance))
}
double clamp(double value) { return Math.max(-1d,Math.min(1d,value)) }
double positiveAngle(double angle) { double result=angle%(2d*Math.PI); return result < 0d ? result+2d*Math.PI : result }

/* SGP4 near-Earth routines below are adapted from satellite.js 5.0.0.
 * Original algorithm: David Vallado et al., AIAA 2006-6753.
 * Adaptation: Jon Wallace, 2026. Deep-space branches removed; unsupported
 * periods are rejected. Numeric locals use double; outputs are Groovy maps.
 * Source: https://www.npmjs.com/package/satellite.js/v/5.0.0
 *
 * MIT License
 *
 * Copyright (C) 2013 Shashwat Kandadai, UCSC Jack Baskin School of Engineering
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
Map sgp4Constants() {
  // WGS-72, matching the model used to fit CelesTrak GP elements.
  double earthRadius = 6378.135d
  double xke = 60d / Math.sqrt(earthRadius * earthRadius * earthRadius / 398600.8d)
  return [earthRadius:earthRadius, xke:xke, tumin:1d/xke, x2o3:2d/3d,
    j2:0.001082616d, j4:-0.00000165597d, j3oj2:-0.00000253881d/0.001082616d,
    twoPi:2d*Math.PI, pi:Math.PI, vkmpersec:earthRadius*xke/60d]
}
Map sgp4Initial(Map satrec) {
  Map c = sgp4Constants()
  double ecco = satrec.ecco, inclo = satrec.inclo, no = satrec.no
  double eccsq = ecco * ecco
  double omeosq = 1.0d - eccsq
  double rteosq = Math.sqrt(omeosq)
  double cosio = Math.cos(inclo)
  double cosio2 = cosio * cosio
  double ak = Math.pow(c.xke / no, c.x2o3)
  double d1 = 0.75d * c.j2 * (3.0d * cosio2 - 1.0d) / (rteosq * omeosq)
  double delPrime = d1 / (ak * ak)
  double adel = ak * (1.0d - delPrime * delPrime - delPrime * (1.0d / 3.0d + 134.0d * delPrime * delPrime / 81.0d))
  delPrime = d1 / (adel * adel)
  no /= 1.0d + delPrime
  double ao = Math.pow(c.xke / no, c.x2o3)
  double sinio = Math.sin(inclo)
  double po = ao * omeosq
  double con42 = 1.0d - 5.0d * cosio2
  double con41 = -con42 - cosio2 - cosio2
  double ainv = 1.0d / ao
  double posq = po * po
  double rp = ao * (1.0d - ecco)
  return [no:no, ao:ao, con41:con41, con42:con42, cosio:cosio,
    cosio2:cosio2, eccsq:eccsq, omeosq:omeosq, posq:posq, rp:rp, rteosq:rteosq, sinio:sinio]
}
Map sgp4Initialize(Map elements) {
  Map c = sgp4Constants()
  Map satrec = new LinkedHashMap(elements)
  double cc1sq, cc2, cc3, coef, coef1, cosio4, eeta, etasq, perige, pinvsq,
    psisq, qzms24, sfour, temp, temp1, temp2, temp3, tsi, xpidot, xhdot1
  double temp4 = 1.5e-12d
  satrec.isimp = 0
  satrec.method = 'n'
  satrec.aycof = 0.0d
  satrec.con41 = 0.0d
  satrec.cc1 = 0.0d
  satrec.cc4 = 0.0d
  satrec.cc5 = 0.0d
  satrec.d2 = 0.0d
  satrec.d3 = 0.0d
  satrec.d4 = 0.0d
  satrec.delmo = 0.0d
  satrec.eta = 0.0d
  satrec.argpdot = 0.0d
  satrec.omgcof = 0.0d
  satrec.sinmao = 0.0d
  satrec.t = 0.0d
  satrec.t2cof = 0.0d
  satrec.t3cof = 0.0d
  satrec.t4cof = 0.0d
  satrec.t5cof = 0.0d
  satrec.x1mth2 = 0.0d
  satrec.x7thm1 = 0.0d
  satrec.mdot = 0.0d
  satrec.nodedot = 0.0d
  satrec.xlcof = 0.0d
  satrec.xmcof = 0.0d
  satrec.nodecf = 0.0d
  double ss = 78.0d / c.earthRadius + 1.0d
  double qzms2ttemp = (120.0d - 78.0d) / c.earthRadius
  double qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp
  satrec.init = 'y'
  satrec.t = 0.0d
  def initlResult = sgp4Initial(satrec)
  double ao = initlResult.ao, con42 = initlResult.con42, cosio = initlResult.cosio,
    cosio2 = initlResult.cosio2, eccsq = initlResult.eccsq, omeosq = initlResult.omeosq,
    posq = initlResult.posq, rp = initlResult.rp, rteosq = initlResult.rteosq, sinio = initlResult.sinio
  satrec.no = initlResult.no
  if (2d * Math.PI / satrec.no >= 225d) throw new IllegalArgumentException("Deep-space orbits (period >= 225 minutes) are not supported.")
  satrec.con41 = initlResult.con41
  satrec.a = Math.pow(satrec.no * c.tumin, -2.0d / 3.0d)
  satrec.alta = satrec.a * (1.0d + satrec.ecco) - 1.0d
  satrec.altp = satrec.a * (1.0d - satrec.ecco) - 1.0d
  satrec.error = 0
  if (omeosq >= 0.0d || satrec.no >= 0.0d) {
    satrec.isimp = 0
    if (rp < 220.0d / c.earthRadius + 1.0d) {
      satrec.isimp = 1
    }
    sfour = ss
    qzms24 = qzms2t
    perige = (rp - 1.0d) * c.earthRadius
    if (perige < 156.0d) {
      sfour = perige - 78.0d
      if (perige < 98.0d) {
        sfour = 20.0d
      }
      double qzms24temp = (120.0d - sfour) / c.earthRadius
      qzms24 = qzms24temp * qzms24temp * qzms24temp * qzms24temp
      sfour = sfour / c.earthRadius + 1.0d
    }
    pinvsq = 1.0d / posq
    tsi = 1.0d / (ao - sfour)
    satrec.eta = ao * satrec.ecco * tsi
    etasq = satrec.eta * satrec.eta
    eeta = satrec.ecco * satrec.eta
    psisq = Math.abs(1.0d - etasq)
    coef = qzms24 * Math.pow(tsi, 4.0d)
    coef1 = coef / Math.pow(psisq, 3.5d)
    cc2 = coef1 * satrec.no * (ao * (1.0d + 1.5d * etasq + eeta * (4.0d + etasq)) + 0.375d * c.j2 * tsi / psisq * satrec.con41 * (8.0d + 3.0d * etasq * (8.0d + etasq)))
    satrec.cc1 = satrec.bstar * cc2
    cc3 = 0.0d
    if (satrec.ecco > 1.0e-4d) {
      cc3 = -2.0d * coef * tsi * c.j3oj2 * satrec.no * sinio / satrec.ecco
    }
    satrec.x1mth2 = 1.0d - cosio2
    satrec.cc4 = 2.0d * satrec.no * coef1 * ao * omeosq * (satrec.eta * (2.0d + 0.5d * etasq) + satrec.ecco * (0.5d + 2.0d * etasq) - c.j2 * tsi / (ao * psisq) * (-3.0d * satrec.con41 * (1.0d - 2.0d * eeta + etasq * (1.5d - 0.5d * eeta)) + 0.75d * satrec.x1mth2 * (2.0d * etasq - eeta * (1.0d + etasq)) * Math.cos(2.0d * satrec.argpo)))
    satrec.cc5 = 2.0d * coef1 * ao * omeosq * (1.0d + 2.75d * (etasq + eeta) + eeta * etasq)
    cosio4 = cosio2 * cosio2
    temp1 = 1.5d * c.j2 * pinvsq * satrec.no
    temp2 = 0.5d * temp1 * c.j2 * pinvsq
    temp3 = -0.46875d * c.j4 * pinvsq * pinvsq * satrec.no
    satrec.mdot = satrec.no + 0.5d * temp1 * rteosq * satrec.con41 + 0.0625d * temp2 * rteosq * (13.0d - 78.0d * cosio2 + 137.0d * cosio4)
    satrec.argpdot = -0.5d * temp1 * con42 + 0.0625d * temp2 * (7.0d - 114.0d * cosio2 + 395.0d * cosio4) + temp3 * (3.0d - 36.0d * cosio2 + 49.0d * cosio4)
    xhdot1 = -temp1 * cosio
    satrec.nodedot = xhdot1 + (0.5d * temp2 * (4.0d - 19.0d * cosio2) + 2.0d * temp3 * (3.0d - 7.0d * cosio2)) * cosio
    xpidot = satrec.argpdot + satrec.nodedot
    satrec.omgcof = satrec.bstar * cc3 * Math.cos(satrec.argpo)
    satrec.xmcof = 0.0d
    if (satrec.ecco > 1.0e-4d) {
      satrec.xmcof = -c.x2o3 * coef * satrec.bstar / eeta
    }
    satrec.nodecf = 3.5d * omeosq * xhdot1 * satrec.cc1
    satrec.t2cof = 1.5d * satrec.cc1
    if (Math.abs(cosio + 1.0d) > 1.5e-12d) {
      satrec.xlcof = -0.25d * c.j3oj2 * sinio * (3.0d + 5.0d * cosio) / (1.0d + cosio)
    } else {
      satrec.xlcof = -0.25d * c.j3oj2 * sinio * (3.0d + 5.0d * cosio) / temp4
    }
    satrec.aycof = -0.5d * c.j3oj2 * sinio
    double delmotemp = 1.0d + satrec.eta * Math.cos(satrec.mo)
    satrec.delmo = delmotemp * delmotemp * delmotemp
    satrec.sinmao = Math.sin(satrec.mo)
    satrec.x7thm1 = 7.0d * cosio2 - 1.0d
    if (satrec.isimp != 1) {
      cc1sq = satrec.cc1 * satrec.cc1
      satrec.d2 = 4.0d * ao * tsi * cc1sq
      temp = satrec.d2 * tsi * satrec.cc1 / 3.0d
      satrec.d3 = (17.0d * ao + sfour) * temp
      satrec.d4 = 0.5d * temp * ao * tsi * (221.0d * ao + 31.0d * sfour) * satrec.cc1
      satrec.t3cof = satrec.d2 + 2.0d * cc1sq
      satrec.t4cof = 0.25d * (3.0d * satrec.d3 + satrec.cc1 * (12.0d * satrec.d2 + 10.0d * cc1sq))
      satrec.t5cof = 0.2d * (3.0d * satrec.d4 + 12.0d * satrec.cc1 * satrec.d3 + 6.0d * satrec.d2 * satrec.d2 + 15.0d * cc1sq * (2.0d * satrec.d2 + cc1sq))
    }
  }
  return satrec
}
Map sgp4Position(Map satrec, double tsince) {
  Map c = sgp4Constants()
  double coseo1
  double sineo1
  double cosip
  double sinip
  double cosisq
  double delm
  double delomg
  double eo1
  double argpm
  double argpp
  double su
  double t3
  double t4
  double tc
  double tem5
  double temp
  double tempa
  double tempe
  double templ
  double inclm
  double mm
  double nm
  double nodem
  double xincp
  double xlm
  double mp
  double nodep
  double temp4 = 1.5e-12d
  satrec.t = tsince
  satrec.error = 0
  double xmdf = satrec.mo + satrec.mdot * satrec.t
  double argpdf = satrec.argpo + satrec.argpdot * satrec.t
  double nodedf = satrec.nodeo + satrec.nodedot * satrec.t
  argpm = argpdf
  mm = xmdf
  double t2 = satrec.t * satrec.t
  nodem = nodedf + satrec.nodecf * t2
  tempa = 1.0d - satrec.cc1 * satrec.t
  tempe = satrec.bstar * satrec.cc4 * satrec.t
  templ = satrec.t2cof * t2
  if (satrec.isimp != 1) {
    delomg = satrec.omgcof * satrec.t
    double delmtemp = 1.0d + satrec.eta * Math.cos(xmdf)
    delm = satrec.xmcof * (delmtemp * delmtemp * delmtemp - satrec.delmo)
    temp = delomg + delm
    mm = xmdf + temp
    argpm = argpdf - temp
    t3 = t2 * satrec.t
    t4 = t3 * satrec.t
    tempa = tempa - satrec.d2 * t2 - satrec.d3 * t3 - satrec.d4 * t4
    tempe += satrec.bstar * satrec.cc5 * (Math.sin(mm) - satrec.sinmao)
    templ = templ + satrec.t3cof * t3 + t4 * (satrec.t4cof + satrec.t * satrec.t5cof)
  }
  nm = satrec.no
  double em = satrec.ecco
  inclm = satrec.inclo
  if (nm <= 0.0d) {
    satrec.error = 2
    return null
  }
  double am = Math.pow(c.xke / nm, c.x2o3) * tempa * tempa
  nm = c.xke / Math.pow(am, 1.5d)
  em -= tempe
  if (em >= 1.0d || em < -0.001d) {
    satrec.error = 1
    return null
  }
  if (em < 1.0e-6d) {
    em = 1.0e-6d
  }
  mm += satrec.no * templ
  xlm = mm + argpm + nodem
  nodem %= c.twoPi
  argpm %= c.twoPi
  xlm %= c.twoPi
  mm = (xlm - argpm - nodem) % c.twoPi
  double sinim = Math.sin(inclm)
  double cosim = Math.cos(inclm)
  double ep = em
  xincp = inclm
  argpp = argpm
  nodep = nodem
  mp = mm
  sinip = sinim
  cosip = cosim
  double axnl = ep * Math.cos(argpp)
  temp = 1.0d / (am * (1.0d - ep * ep))
  double aynl = ep * Math.sin(argpp) + temp * satrec.aycof
  double xl = mp + argpp + nodep + temp * satrec.xlcof * axnl
  double u = (xl - nodep) % c.twoPi
  eo1 = u
  tem5 = 9999.9d
  double ktr = 1
  while (Math.abs(tem5) >= 1.0e-12d && ktr <= 10) {
    sineo1 = Math.sin(eo1)
    coseo1 = Math.cos(eo1)
    tem5 = 1.0d - coseo1 * axnl - sineo1 * aynl
    tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
    if (Math.abs(tem5) >= 0.95d) {
      if (tem5 > 0.0d) {
        tem5 = 0.95d
      } else {
        tem5 = -0.95d
      }
    }
    eo1 += tem5
    ktr += 1
  }
  double ecose = axnl * coseo1 + aynl * sineo1
  double esine = axnl * sineo1 - aynl * coseo1
  double el2 = axnl * axnl + aynl * aynl
  double pl = am * (1.0d - el2)
  if (pl < 0.0d) {
    satrec.error = 4
    return null
  }
  double rl = am * (1.0d - ecose)
  double rdotl = Math.sqrt(am) * esine / rl
  double rvdotl = Math.sqrt(pl) / rl
  double betal = Math.sqrt(1.0d - el2)
  temp = esine / (1.0d + betal)
  double sinu = am / rl * (sineo1 - aynl - axnl * temp)
  double cosu = am / rl * (coseo1 - axnl + aynl * temp)
  su = Math.atan2(sinu, cosu)
  double sin2u = (cosu + cosu) * sinu
  double cos2u = 1.0d - 2.0d * sinu * sinu
  temp = 1.0d / pl
  double temp1 = 0.5d * c.j2 * temp
  double temp2 = temp1 * temp
  double mrt = rl * (1.0d - 1.5d * temp2 * betal * satrec.con41) + 0.5d * temp1 * satrec.x1mth2 * cos2u
  if (mrt < 1.0d) {
    satrec.error = 6
    return null
  }
  su -= 0.25d * temp2 * satrec.x7thm1 * sin2u
  double xnode = nodep + 1.5d * temp2 * cosip * sin2u
  double xinc = xincp + 1.5d * temp2 * cosip * sinip * cos2u
  double mvt = rdotl - nm * temp1 * satrec.x1mth2 * sin2u / c.xke
  double rvdot = rvdotl + nm * temp1 * (satrec.x1mth2 * cos2u + 1.5d * satrec.con41) / c.xke
  double sinsu = Math.sin(su)
  double cossu = Math.cos(su)
  double snod = Math.sin(xnode)
  double cnod = Math.cos(xnode)
  double sini = Math.sin(xinc)
  double cosi = Math.cos(xinc)
  double xmx = -snod * cosi
  double xmy = cnod * cosi
  double ux = xmx * sinsu + cnod * cossu
  double uy = xmy * sinsu + snod * cossu
  double uz = sini * sinsu
  double vx = xmx * cossu - cnod * sinsu
  double vy = xmy * cossu - snod * sinsu
  double vz = sini * cossu
  Map r = [
    x: mrt * ux * c.earthRadius,
    y: mrt * uy * c.earthRadius,
    z: mrt * uz * c.earthRadius
  ]
  Map v = [
    x: (mvt * ux + rvdot * vx) * c.vkmpersec,
    y: (mvt * uy + rvdot * vy) * c.vkmpersec,
    z: (mvt * uz + rvdot * vz) * c.vkmpersec
  ]
  return [
    position: r,
    velocity: v
  ]
}
