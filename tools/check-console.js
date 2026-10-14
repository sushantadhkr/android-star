// Exercises index.html's render logic against the REAL /api/capabilities/docs payload.
// Not a browser: a minimal DOM shim, enough to prove the page builds correct markup
// from the real data, every param type maps to a usable control, and nothing the
// device returns can inject HTML.
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const html = fs.readFileSync(path.join(root, "index.html"), "utf8");
const code = html.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^\s*"use strict";/, "");

const DOCS_FILE = path.join(root, "tools/fixtures/docs-sample.json");
if (!fs.existsSync(DOCS_FILE)) {
  console.error("missing " + DOCS_FILE + " — run the DumpDocs test first");
  process.exit(1);
}
const docs = JSON.parse(fs.readFileSync(DOCS_FILE, "utf8"));

// ---- DOM shim -------------------------------------------------------------
class El {
  constructor(id = "") {
    this.id = id; this.value = ""; this.checked = false; this.type = "";
    this.dataset = {}; this.style = {}; this.className = "";
    this.listeners = {};
  }
  set innerHTML(v) { this._html = v; }
  get innerHTML() { return this._html || ""; }
  addEventListener(t, f) { (this.listeners[t] = this.listeners[t] || []).push(f); }
  closest() { return null; }
  querySelector() { return null; }
}
const els = {};
["base", "token", "status", "connect", "reveal", "list", "main", "master"]
  .forEach(id => (els[id] = new El(id)));

global.document = {
  getElementById: (id) => (els[id] = els[id] || new El(id)),
  querySelector: () => null,
  querySelectorAll: () => [],
};
global.localStorage = { getItem: () => null, setItem: () => {} };
global.fetch = async () => { throw new Error("no network in this harness"); };
global.location = { protocol: "http:" };

// Load the page with state already seeded, so the renderers can be called directly.
function boot(catalogue, selected) {
  const src = code
    .replace("let docs = null;", "let docs = __seed.docs;")
    .replace("let catalogue = null;", "let catalogue = __seed.catalogue;")
    .replace("let selected = null;", "let selected = __seed.selected;") + `
  ; return {
    renderList, renderMain, actionCard, paramHtml, statusExplain,
    select: (s) => { selected = s; },
  };`;
  return new Function("__seed", src)({ docs, catalogue, selected });
}

// A catalogue containing both documented and undocumented capabilities, with a
// realistic mix of usable / switched-off / unregistered states.
const allIds = [...new Set([
  ...docs.capabilities.map(c => c.id),
  "CALENDAR", "BLUETOOTH", "LOCATION", "HEALTH_CONNECT", "VPN", "NFC", "SENSORS",
])];
const capabilities = allIds.map((id, i) => {
  const spec = docs.capabilities.find(c => c.id === id);
  return {
    id,
    displayName: spec ? spec.displayName : id.replace(/_/g, " ").toLowerCase(),
    description: spec ? spec.description : "",
    registered: i % 7 !== 0,
    switchedOn: i % 5 !== 0,
    usable: i % 5 !== 0 && i % 7 !== 0,
    status: i % 7 === 0 ? "unavailable" : (i % 5 === 0 ? "disabled_by_user" : "available"),
    actions: spec ? spec.actions.map(a => a.name) : [],
    requiredPermissions: spec ? spec.requiredPermissions : [],
  };
});
const catalogue = { masterEnabled: true, count: capabilities.length, capabilities };

// ---------------------------------------------------------------------------
let failures = 0;
function check(name, cond, extra) {
  if (cond) console.log("  ok   " + name);
  else { failures++; console.log("  FAIL " + name + (extra ? "\n         " + extra : "")); }
}

const page = boot(catalogue, docs.capabilities[0].id);

console.log("\n1. sidebar renders every capability");
page.renderList();
let list = els.list.innerHTML;
check("every capability appears", capabilities.every(c => list.includes('data-cap="' + c.id + '"')));
check("action counts shown for documented ones",
  (list.match(/class="badge"/g) || []).length === docs.capabilities.length,
  "badges=" + (list.match(/class="badge"/g) || []).length);
check("grouped by whether it has actions",
  list.includes("With actions") && list.includes("No actions yet"));

console.log("\n2. every documented action renders its own form");
let missing = [];
for (const cap of docs.capabilities) {
  page.select(cap.id);
  page.renderMain();
  const main = els.main.innerHTML;
  for (const a of cap.actions) {
    if (!main.includes('data-run="' + a.name + '"')) missing.push(cap.id + "." + a.name + " (Run)");
    for (const p of a.params) {
      if (!main.includes('id="' + a.name + "::" + p.name + '"')) {
        missing.push(cap.id + "." + a.name + "." + p.name);
      }
    }
  }
}
check("all " + (docs.capabilities.length) + " capabilities and their forms render",
  missing.length === 0, missing.slice(0, 8).join(", "));

console.log("\n3. every param type maps to a usable control");
const types = [...new Set(docs.capabilities.flatMap(c => c.actions.flatMap(a => a.params.map(p => p.type))))];
const post = docs.capabilities.find(c => c.id === "NOTIFICATIONS").actions.find(a => a.name === "post");
const postHtml = page.actionCard(post);
for (const p of post.params) {
  const want = { string: 'type="text"', boolean: 'type="checkbox"', array: "<textarea", integer: 'type="number"' }[p.type];
  check(`${p.type} param "${p.name}" renders a control`, postHtml.includes(want), postHtml);
}
check("types actually exercised: " + types.join(", "), types.includes("array") && types.includes("boolean"));

console.log("\n4. required params are marked");
check("required params are labelled", postHtml.includes("required"));
check("only the required one is", (postHtml.match(/class="req"/g) || []).length === post.params.filter(p => p.required).length);

console.log("\n5. an unusable capability explains itself");
const off = capabilities.find(c => !c.switchedOn);
page.select(off.id);
page.renderMain();
check("a switched-off capability says so",
  els.main.innerHTML.includes("Switched off") || !docs.capabilities.find(c => c.id === off.id));

console.log("\n6. a capability with no actions does not invent any");
const bare = capabilities.find(c => !docs.capabilities.find(d => d.id === c.id));
page.select(bare.id);
page.renderMain();
check("says it exposes no actions", els.main.innerHTML.includes("no actions yet"));
check("renders no Run button", !els.main.innerHTML.includes("data-run="));

console.log("\n7. a hostile capability name cannot inject HTML");
const evilCatalogue = {
  masterEnabled: true, count: 1,
  capabilities: [{
    id: "X", displayName: '<img src=x onerror=alert(1)>', description: "", registered: true,
    switchedOn: true, usable: true, status: "available", actions: [], requiredPermissions: [],
  }],
};
boot(evilCatalogue, "X").renderList();
check("no raw <img> from a capability name", !els.list.innerHTML.includes("<img src=x"), els.list.innerHTML.slice(0, 160));
check("escaped form is present", els.list.innerHTML.includes("&lt;img"));

console.log("\n8. examples in the docs are valid JSON the page can pre-fill");
let badExamples = [];
for (const c of docs.capabilities)
  for (const a of c.actions)
    try { JSON.parse(a.exampleRequest); } catch (e) { badExamples.push(c.id + "." + a.name); }
check("every documented exampleRequest parses", badExamples.length === 0, badExamples.join(", "));

console.log("\n9. opened from disk it explains itself instead of failing silently");
{
  global.location = { protocol: "file:" };
  boot(catalogue, null);
  const main = els.main.innerHTML;
  check("says to open it from the phone", /open this from the phone/i.test(main));
  check("gives the served URL as a link", main.includes('href="http://100.101.227.77:8899/"'));
  check("mentions the localhost form for the phone's own browser", main.includes("localhost:8899"));
  check("offers no form to a disk copy", !main.includes("data-run="));
  check("the sidebar stays empty rather than showing fake capabilities", els.list.innerHTML === "");
  global.location = { protocol: "http:" };
}

console.log("\n10. the server asset and the repository copy cannot drift");
{
  const fs2 = require("fs");
  const asset = "app/src/main/assets/index.html";
  const tracked = fs2.existsSync(asset);
  check("the generated asset exists after a build", tracked, "run ./gradlew :app:assembleDebug first");
  if (tracked) {
    const same = fs2.readFileSync(asset, "utf8") === fs2.readFileSync(path.join(root, "index.html"), "utf8");
    check("the APK copy is byte-identical to index.html", same);
  }
  // spawnSync, not execSync: the latter returns a Buffer with no exit status, so `=== 0` would
  // silently compare against undefined and always report "not ignored".
  const ignored = require("child_process")
    .spawnSync("git", ["check-ignore", "-q", asset], { cwd: root }).status === 0;
  check("the generated copy is gitignored so only one is tracked", ignored);
}

console.log("\n" + (failures ? failures + " FAILURE(S)" : "all checks passed"));
process.exit(failures ? 1 : 0);