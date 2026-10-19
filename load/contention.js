import http from 'k6/http';import {check,sleep}from'k6';
// Use an isolated event created for this test. One login per VU, never arbitrary targets from the UI.
export const options={vus:12,iterations:120,maxDuration:'60s',thresholds:{http_req_failed:['rate<0.05']}};
const base=__ENV.BASE_URL||'http://localhost:3000';
export default function(){if(!__ENV.EVENT_ID||!__ENV.TOKEN)throw Error('Supply EVENT_ID and an operator/user TOKEN. Use a fresh isolated event.');const opts={headers:{Authorization:'Bearer '+__ENV.TOKEN,'Content-Type':'application/json'},responseCallback:http.expectedStatuses(200,409,429)};const r=http.post(base+'/api/holds',JSON.stringify({eventId:__ENV.EVENT_ID,seats:['A1']}),opts);check(r,{'expected reservation response':r=>[200,409,429].includes(r.status)});sleep(.05);}
// This measures public hold endpoint contention with one identity and includes its active-hold/rate limits.
// The in-app lab supplies distinct synthetic identities and persisted invariant verification.
