const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const runner=require('../../../scripts/acceptance/run.cjs');

test('acceptance options fail closed and deduplicate features',()=>{
  assert.throws(()=>runner.options(['--live']),/requires/);
  assert.throws(()=>runner.options(['--feature']),/Missing/);
  assert.throws(()=>runner.options(['--wat']),/Unknown/);
  assert.throws(()=>runner.options(['--timeout','0']),/Timeout/);
  assert.throws(()=>runner.options(['--live','--offline']),/Choose/);
  const catalog=runner.catalog(path.resolve('scripts/acceptance/features.json'));
  assert.throws(()=>runner.select(catalog,{features:[]}),/Select/);
  assert.throws(()=>runner.select(catalog,{features:['missing']}),/Unknown/);
  assert.equal(runner.select(catalog,{features:['coding','coding']}).length,1);
});
test('acceptance subprocess environment excludes inherited secrets',()=>{
  assert.deepEqual(runner.environment({PATH:'/bin',CODEX_HOME:'/fixture',GH_TOKEN:'secret',ANTHROPIC_API_KEY:'secret',SSH_AUTH_SOCK:'/socket'}),{PATH:'/bin',CODEX_HOME:'/fixture'});
  assert.equal(runner.redact('Bearer abc ghp_123 sk-secret'),'Bearer [REDACTED] [REDACTED] [REDACTED]');
});
test('acceptance process failures, logs, timeouts and interruption',async()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'acceptance-runner-test-'));
  try {
    const log=path.join(dir,'failure.log');
    assert.equal((await runner.execute(process.execPath,['-e','console.error("ghp_secret");process.exit(3)'],{log})).status,'FAIL');
    assert.equal(fs.readFileSync(log,'utf8').trim(),'[REDACTED]');
    assert.equal((await runner.execute(process.execPath,['-e','setInterval(()=>{},1000)'],{timeout:0.05})).reason,'timeout');
    const controller=new AbortController();controller.abort();
    assert.equal((await runner.execute(process.execPath,['-e','setInterval(()=>{},1000)'],{signal:controller.signal})).reason,'interrupted');
    assert.equal((await runner.execute(path.join(dir,'missing'),[])).status,'FAIL');
  } finally {fs.rmSync(dir,{recursive:true,force:true});}
});
test('acceptance report does not treat skipped live evidence as success',async()=>{
  const code=await runner.main(['--feature','coding','--live','--harness','codex','--model','fixture'],{execute:async()=>({status:'PASS',code:0})});
  assert.equal(code,1);
  const parent=path.resolve('target/acceptance');
  const reports=fs.readdirSync(parent).map(name=>path.join(parent,name,'report.json')).filter(f=>fs.existsSync(f));
  const report=reports.map(f=>JSON.parse(fs.readFileSync(f,'utf8'))).find(r=>r.options?.model==='fixture');
  assert.equal(report.status,'FAIL');
  assert.equal(report.checks.find(c=>c.id==='live-coding').status,'BLOCKED');
});
test('trusted fixture assertions reject the seeded bug and pass the correct implementation',async()=>{
  const dir=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'acceptance-verifier-test-')));
  const fixture=path.join(dir,'sum.cjs');
  const verifier=path.resolve('scripts/acceptance/verify-sum.cjs');
  const args=['--permission','--allow-fs-read='+verifier,'--allow-fs-read='+fixture,verifier,fixture];
  try {
    fs.writeFileSync(fixture,'module.exports=values=>0;');
    assert.equal((await runner.execute(process.execPath,args)).status,'FAIL');
    fs.writeFileSync(fixture,'module.exports=values=>values.reduce((a,b)=>a+b,0);');
    const log=path.join(dir,'verifier.log');
    assert.equal((await runner.execute(process.execPath,args,{log})).status,'PASS',fs.readFileSync(log,'utf8'));
    fs.writeFileSync(fixture,'require("node:fs").writeFileSync('+JSON.stringify(path.join(dir,'unexpected'))+',"unsafe");module.exports=()=>0;');
    assert.equal((await runner.execute(process.execPath,args)).status,'FAIL');
    assert.equal(fs.existsSync(path.join(dir,'unexpected')),false);
  } finally {fs.rmSync(dir,{recursive:true,force:true});}
});
