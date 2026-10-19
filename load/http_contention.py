"""Bounded stdlib HTTP load generator. Use ONLY your own isolated test event.
python load/http_contention.py --base http://localhost:3000 --event EVENT_UUID --tokens-file tokens.json
Tokens file is a JSON array of bearer strings (do not commit it). Each request uses
normal public hold+checkout endpoints. Rate limits/active-hold limits still apply.
A successful hold is followed by SUCCESS simulated checkout. No real payments.
"""
import argparse,concurrent.futures,json,time,urllib.request,urllib.error,statistics,uuid

def run(base,event,tokens,attempts=100,concurrency=12):
 if not 2<=attempts<=300 or not 1<=concurrency<=24 or not tokens:raise ValueError('Bounded range: 2–300 attempts; 1–24 concurrency; nonempty tokens')
 def post(path,body,token):
  req=urllib.request.Request(base+'/api'+path,data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
  try:
   with urllib.request.urlopen(req,timeout=10) as r:return r.status,json.load(r)
  except urllib.error.HTTPError as e:
   try:body=json.load(e)
   except:body={}
   return e.code,body
 def attempt(i):
  st=time.perf_counter();token=tokens[i%len(tokens)]
  try:
   status,data=post('/holds',{'eventId':event,'seats':['A1']},token)
   if status!=200:return {'hold':False,'checkout':False,'outcome':data.get('code',str(status)),'ms':(time.perf_counter()-st)*1000}
   status,c=post('/checkout',{'holdId':data['id'],'key':str(uuid.uuid4()),'mode':'SUCCESS'},token)
   return {'hold':True,'checkout':status==200 and c.get('status')=='SUCCEEDED','outcome':c.get('status',c.get('code',str(status))),'ms':(time.perf_counter()-st)*1000}
  except Exception as e:return {'hold':False,'checkout':False,'outcome':'INFRASTRUCTURE_ERROR','ms':(time.perf_counter()-st)*1000}
 st=time.perf_counter()
 with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:rows=list(pool.map(attempt,range(attempts)))
 elapsed=time.perf_counter()-st;lat=sorted(r['ms'] for r in rows);outcomes={k:sum(r['outcome']==k for r in rows) for k in sorted({r['outcome'] for r in rows})}
 return {'attempts':attempts,'concurrency':concurrency,'identityCount':len(tokens),'successfulHolds':sum(r['hold'] for r in rows),'confirmedBookings':sum(r['checkout'] for r in rows),'outcomes':outcomes,'durationSeconds':elapsed,'throughputAttemptsPerSecond':attempts/elapsed,'p50ms':lat[(len(lat)-1)//2],'p95ms':lat[min(len(lat)-1,int(len(lat)*.95))],'databaseInvariantVerification':'NOT_RUN_BY_HTTP_GENERATOR','scope':'Public endpoints; one isolated A1 seat; user/rate limits enabled; local measurement only'}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--base',default='http://localhost:3000');p.add_argument('--event',required=True);p.add_argument('--tokens-file',required=True);p.add_argument('--attempts',type=int,default=100);p.add_argument('--concurrency',type=int,default=12);a=p.parse_args();print(json.dumps(run(a.base,a.event,json.load(open(a.tokens_file)),a.attempts,a.concurrency),indent=2))
