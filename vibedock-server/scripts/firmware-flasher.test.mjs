import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('../src/main/resources/static/firmware-flasher.js',import.meta.url),'utf8');
const install=source.match(/button\('install'\)\.addEventListener\('click', \(\) => run\(async \(\) => \{([\s\S]*?)\n  \}\)\);/)[1];
async function flash(chip,confirmationFails=false){
  const calls=[],messages=[],port={};
  const loader={chip:{CHIP_NAME:chip},async writeFlash(options){assert.equal(options.eraseAll,false);calls.push('write');},async after(mode){calls.push(mode);}};
  await vm.runInNewContext('(async()=>{'+install+'})()',{
    loader,serialPort:port,flashSize:'4MB',Uint8Array,
    card:{dataset:{chip,firmware:'/synthetic.bin',version:'test'}},show(){},
    fetch:async()=>({ok:true,arrayBuffer:async()=>new ArrayBuffer(4)}),
    terminal:{writeLine:message=>messages.push(message)},
    disconnect:async()=>calls.push('disconnect'),
    confirmUsbStart:async actual=>{assert.equal(actual,port);calls.push('hello');if(confirmationFails)throw new Error('timeout');},
    askToPair:()=>calls.push('pair')
  });
  return {calls,messages};
}
test('M1 install soft-resets then confirms over USB before offering pairing',async()=>{
  assert.deepEqual((await flash('ESP8266')).calls,['write','soft_reset','disconnect','hello','pair']);
});
test('ESP32 install keeps the hardware reset path',async()=>{
  assert.deepEqual((await flash('ESP32-S3')).calls,['write','hard_reset','disconnect','pair']);
});
test('missing M1 confirmation is reported without retrying reset or offering pairing',async()=>{
  const result=await flash('ESP8266',true);
  assert.deepEqual(result.calls,['write','soft_reset','disconnect','hello']);
  assert.ok(result.messages.some(message=>message.includes('did not confirm')));
});
