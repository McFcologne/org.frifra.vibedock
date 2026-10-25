import test from 'node:test';
import assert from 'node:assert/strict';
globalThis.document={querySelector:()=>({content:'synthetic-csrf'})};
const {openConsole}=await import('../src/main/resources/static/serial-console.js');
function silentDevice(malformedFirst=false){
  let output,commands=[],attempts=0;
  const info={identifier:'vibedock-silent-test',board:'waveshare-s3-touch-lcd-147',version:'test'};
  return {commands,info,open:async()=>{},setSignals:async()=>{},close:async()=>{},
    readable:new ReadableStream({start(controller){output=controller;}}),
    writable:new WritableStream({write(bytes){
      const line=new TextDecoder().decode(bytes).trim();commands.push(line);
      if(line==='VIBEDOCK-HELLO')output.enqueue(new TextEncoder().encode(++attempts===1&&malformedFirst?'VIBEDOCK-READY {broken\n':'VIBEDOCK-READY '+JSON.stringify(info)+'\n'));
      if(line==='VIBEDOCK-LOG')output.enqueue(new TextEncoder().encode('VIBEDOCK-LOG ["synthetic log"]\n'));
    }})};
}
test('a silent device is actively identified and its log is requested',async()=>{
  const port=silentDevice(),console_=await openConsole(port);
  try{assert.deepEqual(await console_.hello(2),port.info);assert.deepEqual(await console_.readLog(port.info),['synthetic log']);
      assert.deepEqual(port.commands,['VIBEDOCK-HELLO','VIBEDOCK-LOG']);}
  finally{await console_.close();}
});
test('HELLO retries an incomplete reply instead of relying on a startup announcement',async()=>{
  const port=silentDevice(true),console_=await openConsole(port);
  try{assert.deepEqual(await console_.hello(2),port.info);assert.deepEqual(port.commands,['VIBEDOCK-HELLO','VIBEDOCK-HELLO']);}
  finally{await console_.close();}
});
