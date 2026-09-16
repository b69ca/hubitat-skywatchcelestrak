// Run from SkywatchCelesTrak: groovy tests/SkywatchCelesTrakTest.groovy
// Hubitat services are simulated; orbital results use external reference fixtures.
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def state=[:], settings=[:], readings=[:], events=[], requests=[], tasks=[:], warnings=[]
long clock=1704110400000L // 2024-01-01 12:00 UTC
def location=[latitude:40d,longitude:-105d,timeZone:TimeZone.getTimeZone('America/Denver')]
def binding=new Binding([
  state:state,settings:settings,location:location,
  metadata:{Closure ignored ->},now:{->clock},
  device:[currentValue:{String name->readings[name]},updateSetting:{String name,Map v->settings[name]=v.value}],
  log:[warn:{message->warnings<<message.toString()},debug:{ignored->},info:{ignored->}],
  sendEvent:{Map event->events<<event;readings[event.name]=event.value},
  runIn:{seconds,handler,Map options=[:]->tasks[handler]=[seconds:seconds,options:options]},
  unschedule:{String handler=null->if(handler)tasks.remove(handler) else tasks.clear()},
  runEvery1Minute:{handler->tasks[handler]=[every:60]},runEvery1Hour:{handler->tasks[handler]=[every:3600]},
  asynchttpGet:{callback,params,data->requests<<[callback:callback,params:params,data:data]}
])
def driver=new GroovyShell(binding).parse(new File('SkywatchCelesTrak.groovy'))
driver.run()
def fails={Closure action->boolean failed=false;try{action()}catch(Exception expected){failed=true};assert failed}

def vectors=new JsonSlurper().parse(new File('tests/vallado-vectors.json'))
double maxError=0d
vectors.each { v ->
  def model=driver.sgp4Initialize(v.elements)
  def result=driver.sgp4Position(model,v.minutes as Double)
  assert result : "Unexpected SGP4 error for ${v.catalog} at ${v.minutes}"
  ['x','y','z'].eachWithIndex { axis,i ->
    double error=Math.abs((result.position[axis] as Double)-(v.position[i] as Double))
    maxError=Math.max(maxError,error)
    assert error<0.00002d : "Position error ${error} km: ${v.catalog}, ${v.minutes}, ${axis}"
    assert Math.abs((result.velocity[axis] as Double)-(v.velocity[i] as Double))<0.00000002d
  }
}
println "PASS: ${vectors.size()} Vallado position/velocity vectors; worst coordinate error ${maxError} km"
fails { driver.sgp4Initialize(vectors[0].elements+[no:2d*Math.PI/1440d]) }

def omm=[NORAD_CAT_ID:25544,OBJECT_NAME:'ISS (synthetic test orbit)',EPOCH:'2024-01-01T12:00:00.000000',
  MEAN_MOTION:15.5d,ECCENTRICITY:0.0005d,INCLINATION:51.64d,RA_OF_ASC_NODE:120d,
  ARG_OF_PERICENTER:90d,MEAN_ANOMALY:0d,BSTAR:0.0003d]
def orbit=driver.parseElements(omm,clock)
assert orbit.epoch==clock && orbit.catalog==25544
assert driver.parseElements(omm+[EPOCH:'2024-01-01T12:00:00.123456Z'],clock).epoch==clock+123L
['NaN','Infinity',null,-1d].each { bad->fails { driver.parseElements(omm+[MEAN_MOTION:bad],clock) } }
fails { driver.parseElements(omm+[EPOCH:'2024-02-30T12:00:00'],clock) }
fails { driver.parseElements(omm+[EPOCH:'2024-01-03T12:00:00'],clock) }
fails { driver.parseElements(omm+[EPOCH:'2023-12-01T12:00:00'],clock) }
fails { driver.parseElements(omm+[REF_FRAME:'GCRF'],clock) }
fails { driver.parseElements(omm+[BSTAR:null],clock) }
settings.useCustomLocation=true; settings.observerLatitude=0;settings.observerLongitude=0
assert driver.observerConfig().lat==0d
settings.observerLatitude=91;fails {driver.observerConfig()};settings.clear()
assert driver.fullySunlit([x:6800d,y:0d,z:0d],[x:149597870d,y:0d,z:0d])
assert !driver.fullySunlit([x:-6800d,y:0d,z:0d],[x:149597870d,y:0d,z:0d])
assert driver.fullySunlit([x:0d,y:6800d,z:0d],[x:149597870d,y:0d,z:0d])
assert driver.compass(0d)=='N' && driver.compass(359.9d)=='N' && driver.compass(90d)=='E'
println 'PASS: epoch parsing, invalid/missing values, unsupported orbits, equator coordinates and eclipse geometry'

driver.initialize()
assert requests.size()==1
assert !requests[0].params.toString().contains('latitude') && !requests[0].params.toString().contains('longitude')
def request=requests[0]
driver.refresh();driver.resumeDownloads();driver.initialize()
assert requests.size()==1 // update/reboot/manual commands cannot bypass throttle
driver.orbitalResponse([hasError:{->false},status:200,getJson:{-> [omm]}],request.data)
assert !state.orbit // old callback invalidated by initialize
clock+=7200001L;driver.refresh();request=requests[-1]
assert requests.size()==2
driver.orbitalResponse([hasError:{->false},status:200,getJson:{-> [omm]}],request.data)
assert state.orbit && state.job
def generation=state.job.generation
driver.predictionBatch([generation:generation-1])
assert state.job.generation==generation
long started=System.nanoTime()
int batches=0
while(state.job && batches++<300) {
  driver.predictionBatch([generation:state.job.generation])
  // Match Hubitat persistence: only JSON-safe data may survive between executions.
  def restored=new JsonSlurper().parseText(JsonOutput.toJson(state))
  state.clear();state.putAll(restored)
}
assert !state.job && readings.predictionStatus=='ready' : warnings
assert batches<300 && state.predictionEnd>clock
assert JsonOutput.toJson(state).length()<50000
assert state.passes.every {it.end>it.start && it.duration>=60 && it.peak>=20}
println "PASS: async response validation, two-hour request guard, stale callback rejection, batched prediction (${batches} batches in ${(System.nanoTime()-started)/1e9}s)"

// Reminder/start/end transitions with one-second scheduling and duplicate suppression.
state.passes=[[start:clock+120000L,end:clock+300000L,peak:45d,startAz:270d,endAz:90d,duration:180]]
state.predictionEnd=clock+3600000L
driver.tick()
assert readings.passState=='approaching'
assert events.count{it.name=='pushed' && it.value==1}==1
driver.tick();assert events.count{it.name=='pushed' && it.value==1}==1
state.passes[0].start+=1500L // refreshed predictions must not repeat the same reminder
driver.tick();assert events.count{it.name=='pushed' && it.value==1}==1
clock+=123000L;driver.tick()
assert readings.passState=='visible' && events.count{it.name=='pushed' && it.value==2}==1
clock+=180000L;driver.tick()
assert readings.passState=='none' && events.count{it.name=='pushed' && it.value==3}==1
driver.tick();assert events.count{it.name=='pushed' && it.value==3}==1
assert readings.minutesUntilPass==-1 && readings.nextPassStart==''
def originalState=readings.passState
driver.testReminder()
assert readings.notificationText.startsWith('TEST:') && readings.passState==originalState
settings.reminderStartHour=22;settings.reminderEndHour=6
assert driver.reminderAllowed(1704175200000L) // Jan 1, 23:00 Denver
assert !driver.reminderAllowed(1704218400000L) // Jan 2, 11:00 Denver
settings.clear()
println 'PASS: reminder windows, transition buttons, refreshed-pass deduplication and isolated test reminders'

clock+=21600000L;driver.refresh();request=requests[-1]
driver.orbitalResponse([hasError:{->true},status:503],request.data)
assert state.fetchPaused && readings.dataStatus=='cached'
int count=requests.size();clock+=7200001L;driver.refresh();assert requests.size()==count
driver.resumeDownloads();assert requests.size()==count+1
request=requests[-1]
driver.orbitalResponse([hasError:{->false},status:200,getJson:{-> [[NORAD_CAT_ID:25544]]}],request.data)
assert state.fetchPaused && state.orbit // malformed responses preserve the good cache
clock=(state.orbit.epoch as Long)+72L*3600000L
driver.tick();assert readings.dataStatus=='stale' && readings.passState=='unknown'
assert readings.nextPassStartEpoch==0 && readings.minutesUntilPass==-1
println 'PASS: provider-error circuit breaker, manual resume, cache preservation and stale-data suppression'

// Current-position readings are explicitly marked when disabled or stale.
clock=(state.orbit.epoch as Long)+3600000L
settings.currentPosition=true
driver.tick()
assert readings.positionStatus=='current' && readings.positionTime
settings.currentPosition=false
driver.tick()
assert readings.positionStatus=='disabled' && readings.positionTime==''
settings.currentPosition=true
clock=(state.orbit.epoch as Long)+72L*3600000L
driver.tick()
assert readings.positionStatus=='unavailable' && readings.positionTime==''

// The last retained pass must emit its end event even at the forecast boundary.
clock=(state.orbit.epoch as Long)+3600000L
state.job=null
state.emissions=[start:[catalog:25544,start:clock-60000L,end:clock]]
state.predictionEnd=clock
state.endedStart=null
int ends=events.count{it.name=='pushed' && it.value==3}
driver.tick()
assert events.count{it.name=='pushed' && it.value==3}==ends+1
state.endedStart=null
state.emissions.start.catalog=48274
driver.tick()
assert events.count{it.name=='pushed' && it.value==3}==ends+1
settings.minimumElevation=100
driver.recalculate()
assert readings.predictionStatus=='configuration error'
settings.clear()
println 'PASS: position freshness, forecast-boundary end events, satellite changes and invalid preferences'

if (new File('tests/skyfield-passes.json').exists()) {
  def reference=new JsonSlurper().parse(new File('tests/skyfield-passes.json'))
  reference.each { fixture ->
    settings.clear();state.clear();events.clear()
    clock=fixture.start as Long
    settings.useCustomLocation=true;settings.observerLatitude=fixture.latitude;settings.observerLongitude=fixture.longitude
    settings.observerAltitude=fixture.altitude;settings.minimumPeak=10;settings.minimumDuration=30
    state.orbit=driver.parseElements(fixture.omm,clock);state.fetchedAt=clock
    driver.beginPrediction()
    int n=0
    while(state.job && n++<300)driver.predictionBatch([generation:state.job.generation])
    assert readings.predictionStatus=='ready' : warnings
    assert state.passes.size()==fixture.passes.size() : "Pass count at ${fixture.latitude},${fixture.longitude}: ${state.passes} versus ${fixture.passes}"
    state.passes.eachWithIndex { p,i ->
      def ref=fixture.passes[i]
      assert Math.abs((p.start as Long)-(ref.start as Long))<15000L : "Start discrepancy ${p} vs ${ref}"
      assert Math.abs((p.end as Long)-(ref.end as Long))<15000L : "End discrepancy ${p} vs ${ref}"
      assert Math.abs((p.peak as Double)-(ref.peak as Double))<0.25d
    }
  }
  println "PASS: complete viewing windows compared with Skyfield/JPL DE421 at ${reference.size()} locations"
}
println 'ALL CHECKS PASSED'
