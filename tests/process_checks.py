"""Real JVM restart + cross-instance smoke tests against a disposable database.
Prerequisites: primary backend on localhost:8080, PostgreSQL + Redis configured
through DB_URL/DB_USER/DB_PASSWORD/REDIS_HOST. Build backend first.
Run: python tests/process_checks.py --java java --jar backend/target/seatlock-1.0.0.jar
Do not point this at a production/demo database you need to keep tidy: it creates
an isolated ordinary test event and temporary reservations using demo identities.
The second instance listens on 8081; override --primary/--port if needed.
"""
import argparse,subprocess,urllib.request,urllib.error,json,time,concurrent.futures,pathlib,os
p=argparse.ArgumentParser();p.add_argument('--java',default='java');p.add_argument('--jar',required=True);p.add_argument('--primary',default='http://127.0.0.1:8080');p.add_argument('--port',default='8081');a=p.parse_args();second='http://127.0.0.1:'+a.port

def call(base,path,body=None,token=None,method=None):
 h={'Content-Type':'application/json'}
 if token:h['Authorization']='Bearer '+token
 q=urllib.request.Request(base+'/api'+path,headers=h,data=json.dumps(body).encode() if body is not None else None,method=method)
 try:
  with urllib.request.urlopen(q,timeout=20) as res:
   text=res.read();return res.status,json.loads(text) if text else None
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
def start():
 env=os.environ.copy();env['PORT']=a.port;env['LAB_ENABLED']='false';env['HOLD_SECONDS']='300'
 f=open('/tmp/seatlock-second-instance.log','a');proc=subprocess.Popen([a.java,'-jar',a.jar],env=env,stdout=f,stderr=subprocess.STDOUT)
 for _ in range(120):
  try:
   if urllib.request.urlopen(second+'/actuator/health/readiness',timeout=1).status==200:return proc
  except:pass
  if proc.poll() is not None:raise RuntimeError('Second backend exited; inspect /tmp/seatlock-second-instance.log')
  time.sleep(.25)
 proc.terminate();raise RuntimeError('Second instance not ready')
def stop(proc):
 proc.terminate()
 try:proc.wait(timeout=20)
 except subprocess.TimeoutExpired:proc.kill();proc.wait()

def login(email):
 code,x=call(a.primary,'/auth/login',{'email':email,'password':os.environ.get('DEMO_PASSWORD','SeatLock-demo-2026!')});assert code==200,(code,x);return x['token']
proc=None
try:
 op=login('operator@seatlock.local');u=login('user@seatlock.local');v=login('admin@seatlock.local')
 # Explicitly release these demo users' existing holds before the isolated fixture.
 for token in [u,v]:
  _,hs=call(a.primary,'/holds',token=token)
  for h in hs:call(a.primary,'/holds/'+h['id'],token=token,method='DELETE')
 code,event=call(a.primary,'/ops/events',{'title':'Process verification '+str(time.time_ns()),'description':'Two real JVMs / restart fixture','rows':1,'cols':2,'price':100},op);assert code==200
 eid=event['id'];proc=start()
 with concurrent.futures.ThreadPoolExecutor(2) as pool:
  fs=[pool.submit(call,base,'/holds',{'eventId':eid,'seats':['A1']},token) for base,token in [(a.primary,u),(second,v)]];results=[f.result() for f in fs]
 assert sorted(c for c,_ in results)==[200,409],results
 idx=next(i for i,(c,_) in enumerate(results) if c==200);owner=[u,v][idx];hid=results[idx][1]['id'];print('PASS: two real JVMs, competing holds, exactly one winner',flush=True)
 stop(proc);proc=start();code,holds=call(second,'/holds',token=owner);assert code==200 and any(h['id']==hid for h in holds);print('PASS: active hold + bearer token recover after JVM restart',flush=True)
 code,c=call(second,'/checkout',{'holdId':hid,'key':'process-checkout-'+hid,'mode':'SUCCESS'},owner);assert code==200 and c['status']=='SUCCEEDED',(code,c);bid=c['booking_id']
 stop(proc);proc=start();code,bookings=call(second,'/bookings',token=owner);assert code==200 and any(b['id']==bid for b in bookings);print('PASS: persisted booking recovers after second JVM restart',flush=True)

 # An external HTTP generator invokes public APIs separately from the service-level lab.
 import importlib.util
 spec=importlib.util.spec_from_file_location('http_load',str(pathlib.Path(__file__).resolve().parents[1]/'load/http_contention.py'));mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
 code,load_event=call(a.primary,'/ops/events',{'title':'External HTTP contention','description':'Isolated external load fixture','rows':1,'cols':1,'price':100},op);assert code==200
 report=mod.run(a.primary,load_event['id'],[op],attempts=12,concurrency=6)
 (pathlib.Path(__file__).resolve().parents[1]/'docs/http-load-results.json').write_text(json.dumps(report,indent=2))
 assert report['confirmedBookings']==1,report
 print('PASS: external public HTTP load: '+json.dumps(report),flush=True)
finally:
 if proc:stop(proc)
