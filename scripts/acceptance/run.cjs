#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawn, spawnSync} = require('node:child_process');
const ROOT = path.resolve(__dirname, '../..');

function options(args) {
  const out = {features: [], live: false, timeout: 600};
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (['--list','--all','--live','--offline'].includes(arg)) out[arg.slice(2)] = true;
    else if (['--feature','--harness','--model','--review-model','--reasoning','--timeout'].includes(arg)) {
      const value = args[++i];
      if (!value || value.startsWith('--')) throw Error(`Missing value for ${arg}`);
      if (arg === '--feature') out.features.push(value); else out[arg.slice(2)] = value;
    } else throw Error(`Unknown option: ${arg}`);
  }
  if (out.live && out.offline) throw Error('Choose --live or --offline, not both');
  if (out.all && out.features.length) throw Error('Choose --all or --feature');
  out.timeout = Number(out.timeout);
  if (!Number.isInteger(out.timeout) || out.timeout < 10 || out.timeout > 1800) throw Error('Timeout must be 10–1800 seconds per process');
  if (out.live && (!['codex','claude'].includes(out.harness) || !out.model)) throw Error('Live mode requires --harness codex|claude and --model');
  return out;
}

function catalog(file) {
  const data = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (data.version !== 1 || !Array.isArray(data.features) || !data.features.length) throw Error('Invalid feature catalog');
  const ids = new Set();
  for (const f of data.features) {
    if (!/^[a-z][a-z0-9-]*$/.test(f.id) || ids.has(f.id)) throw Error('Invalid/duplicate feature id');
    ids.add(f.id);
    if (!Array.isArray(f.javaTests) || !f.javaTests.length || f.javaTests.some(t => !/^[A-Za-z0-9_]+Test$/.test(t))) throw Error(`Invalid Java selectors: ${f.id}`);
    if (!Array.isArray(f.jsTests) || f.jsTests.some(t => !/^src\/test\/js\/[\w.-]+\.(cjs|js)$/.test(t) || !fs.existsSync(path.join(ROOT,t)))) throw Error(`Invalid JS selectors: ${f.id}`);
    if (f.liveTest && !/^[A-Za-z0-9_]+Test#[A-Za-z0-9_]+$/.test(f.liveTest)) throw Error('Invalid live selector');
    if (!Array.isArray(f.assertions) || !f.assertions.length) throw Error(`Missing assertions: ${f.id}`);
  }
  return data.features;
}

function select(features, opts) {
  const ids = opts.all ? features.map(f => f.id) : [...new Set(opts.features)];
  if (!ids.length) throw Error('Select --feature NAME or --all (use --list to see features)');
  return ids.map(id => {const f = features.find(f => f.id === id); if (!f) throw Error(`Unknown feature: ${id}`); return f;});
}

function environment(source = process.env) {
  return Object.fromEntries(Object.entries(source).filter(([key]) => !/TOKEN|PASSWORD|SECRET|CREDENTIAL|PRIVATE_KEY|API_KEY|AUTH_TOKEN|ASKPASS|SSH_AUTH_SOCK/i.test(key)));
}
function redact(value) {
  return String(value).replace(/(?:gh[pousr]_[\w]+|github_pat_[\w]+|sk-[\w-]+)/g, '[REDACTED]')
    .replace(/(authorization\s*[:=]\s*|bearer\s+)[^\s,]+/gi, '$1[REDACTED]');
}
function fingerprint(root) {
  const git = args => spawnSync('git',args,{cwd:root,encoding:'utf8',maxBuffer:32*1024*1024});
  const revision = git(['rev-parse','HEAD']);
  const listing = git(['ls-files','-co','--exclude-standard','-z']);
  if (revision.status !== 0 || listing.status !== 0) throw Error('Cannot fingerprint checkout');
  const hash = crypto.createHash('sha256');
  for (const file of [...new Set(listing.stdout.split('\0').filter(Boolean))].sort()) {
    hash.update(file+'\0');
    const full = path.join(root,file);
    let stat;
    try {stat=fs.lstatSync(full);} catch(error) {if(error.code!=='ENOENT') throw error;}
    if (!stat) hash.update('deleted');
    else {
      hash.update(String(stat.mode & 0o777)+'\0');
      if (stat.isSymbolicLink()) hash.update(fs.readlinkSync(full));
      else if (stat.isFile()) hash.update(fs.readFileSync(full));
    }
  }
  return {revision:revision.stdout.trim(), tree:hash.digest('hex'), dirty:git(['status','--porcelain']).stdout.trim().length > 0};
}

function execute(command, args, {cwd=ROOT, timeout=600, log, signal}={}) {
  return new Promise(resolve => {
    const started=Date.now();
    const child=spawn(command,args,{cwd,env:environment(),stdio:['ignore','pipe','pipe'],detached:process.platform!=='win32'});
    let output='', reason;
    const collect = data => {output=(output+data.toString()).slice(-2_000_000);};
    child.stdout.on('data',collect);child.stderr.on('data',collect);
    function stop(why) {
      reason=why;
      try {if(process.platform==='win32') child.kill('SIGKILL');else process.kill(-child.pid,'SIGKILL');} catch {}
    }
    const timer=setTimeout(()=>stop('timeout'),timeout*1000);
    const interrupt=()=>stop('interrupted');
    signal?.addEventListener('abort',interrupt,{once:true});
    if(signal?.aborted) interrupt();
    child.on('error',error=>{reason=error.message;});
    child.on('close',(code)=>{
      clearTimeout(timer);signal?.removeEventListener('abort',interrupt);
      if(log) fs.writeFileSync(log,redact(output));
      resolve({status:code===0&&!reason?'PASS':'FAIL',code,reason,durationMs:Date.now()-started,log});
    });
  });
}

function writeReport(dir, report) {
  fs.writeFileSync(path.join(dir,'report.json'),JSON.stringify(report,null,2)+'\n');
  const rows=report.checks.map(c=>`| ${c.id} | ${c.status} | ${String(c.reason||'').replace(/[|\r\n]/g,' ')} |`).join('\n');
  fs.writeFileSync(path.join(dir,'report.md'),`# Feature acceptance: ${report.status}\n\nMode: ${report.mode}. Required live coverage: ${report.mode==='live'?'included':'NOT_RUN (offline evidence only)'}.\n\nRevision: ${report.source?.revision||'unavailable'}\n\n| Check | Result | Detail |\n| --- | --- | --- |\n${rows}\n\n${report.error||''}\n`);
}

async function main(args, dependencies={}) {
  const parent=path.join(ROOT,'target/acceptance');
  fs.mkdirSync(parent,{recursive:true});
  const dir=fs.mkdtempSync(path.join(parent,'run-'));
  const report={schemaVersion:1,startedAt:new Date().toISOString(),mode:'offline',status:'BLOCKED',checks:[]};
  const controller=new AbortController();
  const interrupted=()=>controller.abort();
  process.once('SIGINT',interrupted);process.once('SIGTERM',interrupted);
  try {
    const opts=options(args), features=catalog(path.join(__dirname,'features.json'));
    report.mode=opts.live?'live':'offline';report.options=opts;report.source=fingerprint(ROOT);
    if(opts.list) {console.log(features.map(f=>`${f.id}: ${f.description}${f.liveTest?' [live scenario]':''}`).join('\n'));report.status='PASS';return 0;}
    const selected=select(features,opts);report.features=selected.map(f=>f.id);
    const run=dependencies.execute||execute;
    async function check(id, command, commandArgs) {
      if(controller.signal.aborted) {report.checks.push({id,status:'NOT_RUN',reason:'interrupted'});return;}
      console.log(`Running ${id}…`);
      const result=await run(command,commandArgs,{timeout:opts.timeout,log:path.join(dir,id+'.log'),signal:controller.signal});
      report.checks.push({id,...result,command:[command,...commandArgs]});writeReport(dir,report);
    }
    const tests=[...new Set(['FixtureApprovalTest',...selected.flatMap(f=>f.javaTests)])];
    await check('java',path.join(ROOT,'mvnw'),['-q',`-Dtest=${tests.join(',')}`,'test']);
    const js=[...new Set(['src/test/js/acceptance-runner.test.cjs',...selected.flatMap(f=>f.jsTests)])];
    if(js.length) await check('javascript',process.execPath,['--test',...js]);
    if(opts.live) for(const feature of selected.filter(f=>f.liveTest)) {
      const id='live-'+feature.id;
      if(report.checks.some(c=>c.status!=='PASS')) {report.checks.push({id,status:'NOT_RUN',reason:'Prerequisite checks did not pass'});continue;}
      const evidence=path.join(dir,id+'.json');
      await check(id,path.join(ROOT,'mvnw'),['-q',`-Dtest=${feature.liveTest}`,'-Dissuebot.acceptance.live=true',
        `-Dissuebot.acceptance.harness=${opts.harness}`,`-Dissuebot.acceptance.model=${opts.model}`,
        `-Dissuebot.acceptance.reasoning=${opts.reasoning||''}`,`-Dissuebot.acceptance.reviewModel=${opts['review-model']||''}`,
        `-Dissuebot.acceptance.evidence=${evidence}`,'test']);
      const result=report.checks.at(-1);
      if(!fs.existsSync(evidence)) {result.status='BLOCKED';result.reason='Required live evidence missing (a skipped test cannot pass)';}
      else {
        const observed=JSON.parse(fs.readFileSync(evidence,'utf8'));result.evidence=observed;
        if(observed.status!=='PASS' || feature.assertions.some(a=>!observed.assertions?.includes(a))) {
          result.status=observed.status==='BLOCKED'?'BLOCKED':'FAIL';result.reason=observed.reason||'Required assertions missing';
        }
      }
    }
    if(opts.live && !selected.some(f=>f.liveTest)) report.checks.push({id:'live',status:'BLOCKED',reason:'Selection has no live scenarios'});
    const after=fingerprint(ROOT);
    if(after.tree!==report.source.tree) report.checks.push({id:'source-integrity',status:'FAIL',reason:'Source changed during acceptance; rerun against the intended tree'});
    report.status=report.checks.length&&report.checks.every(c=>c.status==='PASS')?'PASS':'FAIL';
    return report.status==='PASS'?0:1;
  } catch(error) {report.error=redact(error.message);report.status='BLOCKED';return 2;}
  finally {
    process.removeListener('SIGINT',interrupted);process.removeListener('SIGTERM',interrupted);
    report.finishedAt=new Date().toISOString();writeReport(dir,report);
    console.log(`${report.status}: ${path.relative(ROOT,path.join(dir,'report.md'))}`);
  }
}
module.exports={options,catalog,select,environment,redact,fingerprint,execute,main};
if(require.main===module) main(process.argv.slice(2)).then(code=>{process.exitCode=code;});
