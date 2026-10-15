#!/usr/bin/env node
/*
 * Invokes every advertised capability action against a running phone and judges the answer.
 *
 * The point is not "did it return 200". Any handler can do that. The question is whether the
 * response matches what the action promised — the documented example is the contract, and a body
 * that is missing a field the example shows, or that reports a value it could not have measured,
 * is the failure this looks for.
 */
const BASE = process.argv[2] || 'http://100.101.227.77:8899';
const TOKEN = process.argv[3];

const H = { 'Authorization': `Bearer ${TOKEN}`, 'Content-Type': 'application/json' };

async function invoke(capability, action, params = {}) {
  const res = await fetch(`${BASE}/api/capabilities/invoke`, {
    method: 'POST',
    headers: H,
    body: JSON.stringify({ capability, action, ...params }),
  });
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = { _unparseable: text.slice(0, 200) }; }
  return { status: res.status, body };
}

/** Pulls the keys out of a documented example so we can hold the response to it. */
function exampleKeys(exampleResponse) {
  try {
    const parsed = JSON.parse(exampleResponse);
    return Object.keys(parsed);
  } catch {
    return [];
  }
}

async function main() {
  const docs = await (await fetch(`${BASE}/api/capabilities/docs`, { headers: H })).json();
  const capabilities = docs.capabilities || [];

  const results = [];
  for (const cap of capabilities) {
    for (const action of cap.actions || []) {
      // Fill required params from the documented example, so the call is a realistic one rather
      // than a request designed to fail on a missing argument.
      let example = {};
      try { example = JSON.parse(action.exampleRequest || '{}'); } catch { }
      const params = { ...example };
      delete params.capability;
      delete params.action;

      let outcome;
      try {
        outcome = await invoke(cap.id, action.name, params);
      } catch (e) {
        outcome = { status: 0, body: { _transport: String(e) } };
      }
      results.push({
        capability: cap.id,
        action: action.name,
        documented: action.exampleResponse,
        expectedKeys: exampleKeys(action.exampleResponse),
        status: outcome.status,
        body: outcome.body,
      });
    }
  }

  const verdict = (r) => {
    if (r.status === 0) return 'TRANSPORT_ERROR';
    if (r.status === 501) return 'NO_IMPLEMENTATION';
    if (r.status === 409) return 'NOT_REGISTERED';
    if (r.status === 403) return 'REFUSED';           // honest refusal: capability disabled
    if (r.status >= 400) return 'ERROR';
    if (r.status >= 200 && r.status < 300) {
      const missing = r.expectedKeys.filter((k) => !(k in r.body));
      if (missing.length) return `SHAPE_MISMATCH(missing ${missing.join(',')})`;
      return 'OK';
    }
    return 'UNKNOWN';
  };

  const counts = {};
  for (const r of results) {
    const v = verdict(r);
    r.verdict = v.split('(')[0];
    r.detail = v.includes('(') ? v.slice(v.indexOf('(') + 1, -1) : '';
    counts[r.verdict] = (counts[r.verdict] || 0) + 1;
  }

  console.log(`actions invoked: ${results.length}`);
  console.log('verdicts:', JSON.stringify(counts));
  console.log('');

  const problems = results.filter((r) => r.verdict !== 'OK');
  if (!problems.length) {
    console.log('every action answered 2xx with the documented shape');
  } else {
    for (const p of problems) {
      console.log(`${p.verdict.padEnd(16)} ${p.capability}.${p.action} -> ${p.status} ${p.detail || JSON.stringify(p.body).slice(0, 160)}`);
    }
  }

  console.log('');
  console.log('--- full record ---');
  console.log(JSON.stringify(results, null, 1));
}

main().catch((e) => { console.error(e); process.exit(1); });