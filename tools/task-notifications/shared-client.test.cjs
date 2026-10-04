'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {SharedCodexClient,localSharedEndpoint}=require('../../app/src/main/assets/task-notifications/codex-client.cjs');
class Socket extends EventTarget{
  static sockets=[];
  constructor(url){super();this.url=url;this.sent=[];this.readyState=0;Socket.sockets.push(this);
    queueMicrotask(()=>{this.readyState=1;this.dispatchEvent(new Event('open'));});}
  send(text){const row=JSON.parse(text);this.sent.push(row);if(row.method==='initialize')queueMicrotask(()=>this.receive({id:row.id,result:{}}));}
  receive(row){this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify(row)}));}
  close(){this.readyState=3;this.dispatchEvent(new Event('close'));}
}
test('shared transport accepts only an explicit loopback listener',()=>{
  assert.equal(localSharedEndpoint('ws://127.0.0.1:18475'),'ws://127.0.0.1:18475/');
  for(const value of ['ws://0.0.0.0:18475','wss://example.com','ws://127.0.0.1','ws://user@127.0.0.1:18475',
    'ws://127.0.0.1:18475/task','ws://127.0.0.1:18475/?token=secret',null])assert.throws(()=>localSharedEndpoint(value),/INVALID_SHARED_SERVER/);
});
test('two independent clients share the server without closing or rejecting each other',async()=>{
  const a=new SharedCodexClient('ws://127.0.0.1:18475',Socket),b=new SharedCodexClient('ws://127.0.0.1:18475',Socket);
  const events=[];a.setHandlers(m=>events.push(m),id=>a.reject(id));b.setHandlers(()=>{},()=>{});
  try{
    await Promise.all([a.connect(),b.connect()]);
    a.socket.receive({id:50,method:'item/commandExecution/requestApproval',params:{threadId:'desktop-owned'}});
    assert.equal(a.socket.sent.some(r=>r.id===50),false);
    a.close();assert.equal(b.socket.readyState,1);
    const response=b.read('same-thread'),row=b.socket.sent.at(-1);
    b.socket.receive({id:row.id,result:{thread:{id:'same-thread'}}});
    assert.equal((await response).thread.id,'same-thread');assert.deepEqual(events,[]);
  }finally{a.close();b.close();}
});
test('unexpected shared socket loss rejects pending calls and turn waiters immediately',async()=>{
  const a=new SharedCodexClient('ws://127.0.0.1:18475',Socket),events=[];a.setHandlers(m=>events.push(m),()=>{});
  await a.connect();const read=assert.rejects(a.read('thread'),/CODEX_DISCONNECTED/);
  const stop=assert.rejects(a.interrupt('thread','turn'),/CODEX_DISCONNECTED/);
  a.socket.close();await Promise.all([read,stop]);assert.deepEqual(events,['bridge/disconnected']);
  assert.equal(a.pending.size,0);assert.equal(a.startWaiters.size,0);
});
