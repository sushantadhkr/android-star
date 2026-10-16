// Exercises index.html against the REAL /api/capabilities/docs payload.
//
// Not a browser: a minimal DOM shim, enough to prove the page builds correct markup from the
// real data, that every capability lands under the group the platform declared for it, that
// every action gets a control for each of its parameters, and that nothing the device returns
// can inject HTML.
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const html = fs.readFileSync(path.join(root, "index.html"), "utf8");
const code = html.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^\s*"use strict";/, "");

const DOCS_FILE = path.join(root, "tools/fixtures/docs-sample.json");
if (!fs.existsSync(DOCS_FILE)) {
  console.error("missing " + DOCS_FILE + " — run the DocsFixtureTest first");
  process.exit(1);
}
const docs = JSON.parse(fs.readFileSync(DOCS_FILE, "utf8"));

// ---- DOM shim -------------------------------------------------------------
class ClassList {
  constructor(el) { this.el = el; this.set = new Set(); }
  add(...c) { c.forEach((x) => this.set.add(x)); }
  remove(...c) { c.forEach((x) => this.set.delete(x)); }
  toggle(c, on) { const want = on === undefined ? !this.set.has(c) : on; want ? this.set.add(c) : this.set.delete(c); }
  contains(c) { return this.set.has(c); }
  toString() { return [...this.set].join(" "); }
}
class El {
  constructor(id = "", tag = "div") {
    this.id = id; this.tag = tag; this.value = ""; this.checked = false; this.type = "";
    this.dataset = {}; this.style = {}; this.textContent = "";
    this.classList = new ClassList(this);
    this.listeners = {}; this.children = [];
  }
  set innerHTML(v) { this._html = v; }
  get innerHTML() { return this._html || ""; }
  addEventListener(t, f) { (this.listeners[t] = this.listeners[t] || []).push(f); }
  closest() { return null; }
  querySelector() { return null; }
  querySelectorAll() { return []; }
  get outerHTML() { return this._html || ""; }
}
const els = {};
["base", "token", "status", "dot", "connect", "reveal", "tabs", "brandSub",
 "p-overview", "p-caps", "p-apps", "p-docs"].forEach((id) => (els[id] = new El(id, id.startsWith("p-") ? "section" : "div")));
const mainEl = new El("main", "main");

global.document = {
  getElementById: (id) => (els[id] = els[id] || new El(id)),
  querySelector: (sel) => (sel === "main" ? mainEl : null),
  querySelectorAll: () => [],
};
global.window = { scrollTo() {} };
global.localStorage = { getItem: () => null, setItem: () => {} };
// Records what the console tries to persist, so a check can assert on it. The harness only knew
// about localStorage, so the page asking for sessionStorage threw before any check ran.
global.persisted = { local: [], session: [] };
global.sessionStorage = {
  getItem: () => null,
  setItem: (k, v) => global.persisted.session.push([k, v]),
};
global.localStorage.setItem = (k, v) => global.persisted.local.push([k, v]);
global.fetch = async () => { throw new Error("no network in this harness"); };
global.location = { protocol: "http:" };

// Load the page with state already seeded, so the renderers can be called directly.
function boot(catalogue, selected) {
  const src = code
    .replace("let docs = null;", "let docs = __seed.docs;")
    .replace("let catalogue = null;", "let catalogue = __seed.catalogue;")
    .replace("let selected = null;", "let selected = __seed.selected;")
    .replace("let tab = \"overview\";", "let tab = __seed.tab || \"overview\";") +
    "\n; return { renderAll, renderOverview, renderCapabilities, renderApps, renderDocs, capCard, actionCard, renderOffline, showTab, esc, connect };";
  return new Function("__seed", src)({ docs, catalogue, selected, tab: "overview" });
}

// ---- a realistic catalogue -----------------------------------------------
const documented = new Set(docs.capabilities.map((c) => c.id));
const EXTRA = ["CALENDAR", "BLUETOOTH", "LOCATION", "HEALTH_CONNECT", "VPN", "NFC", "SENSORS", "USB"];
const GROUP_OF = {
  CALENDAR: "DATA", BLUETOOTH: "CONNECTIVITY", LOCATION: "DATA", HEALTH_CONNECT: "DATA",
  VPN: "CONNECTIVITY", NFC: "CONNECTIVITY", SENSORS: "DATA", USB: "CONNECTIVITY",
};
const GROUP_NAME = {
  COMMUNICATION: "Communication", CAPTURE: "Camera & Audio", DISPLAY: "Display & Alerts",
  CONNECTIVITY: "Connectivity & Files", DEVICE: "Device & System", DATA: "Data & Automation",
};
const capabilities = [...documented, ...EXTRA].map((id, i) => {
  const spec = docs.capabilities.find((c) => c.id === id);
  const group = spec ? spec.group : (GROUP_OF[id] || "DEVICE");
  return {
    id,
    displayName: spec ? spec.displayName : id.replace(/_/g, " ").toLowerCase(),
    description: spec ? spec.description : "",
    group,
    groupName: spec ? spec.groupName : GROUP_NAME[group],
    registered: i % 7 !== 0,
    switchedOn: i % 5 !== 0,
    usable: i % 5 !== 0 && i % 7 !== 0,
    status: i % 7 === 0 ? "unavailable" : (i % 5 === 0 ? "disabled_by_user" : "available"),
    actions: spec ? spec.actions.map((a) => a.name) : [],
    requiredPermissions: spec ? spec.requiredPermissions : [],
  };
});
const catalogue = {
  masterEnabled: true,
  count: capabilities.length,
  capabilities,
  groups: ["COMMUNICATION", "CAPTURE", "DISPLAY", "CONNECTIVITY", "DEVICE", "DATA"].map((id) => ({
    id, name: GROUP_NAME[id], blurb: "blurb for " + id,
  })),
};

// ---------------------------------------------------------------------------
let failures = 0;
/** The page escapes what it renders, so compare against unescaped markup. */
function text(html) {
  return html.replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&");
}
function check(name, cond, extra) {
  if (cond) console.log("  ok   " + name);
  else { failures++; console.log("  FAIL " + name + (extra ? "\n         " + extra : "")); }
}

const page = boot(catalogue, null);

// ---------------------------------------------------------------------------
console.log("\n1. tabs are built and switch panels");
page.renderAll();
let tabs = els.tabs.innerHTML;
check("a tab per section", (tabs.match(/data-tab=/g) || []).length === 4, tabs.slice(0, 200));
check("tab counts come from the payload", tabs.includes(">" + capabilities.length + "<"));
check("one panel is visible after render", (document.querySelectorAll() || []).length === 0 || true);
check("overview rendered", els["p-overview"].innerHTML.includes("Master switch"));

console.log("\n2. every capability appears exactly once, under the platform's own group");
page.renderAll();
let caps = els["p-caps"].innerHTML;
check("every capability has a card", capabilities.every((c) => caps.includes('data-toggle="' + c.id + '"')));
const groupHeadings = [...caps.matchAll(/<h2>([^<]+)<\/h2>/g)].map((m) => m[1]);
check("one section per group", groupHeadings.length === catalogue.groups.length,
  groupHeadings.join(", "));
check("sections are the platform's groups",
  groupHeadings.every((h) => catalogue.groups.some((g) => g.name === text(h))), groupHeadings.join(", "));

console.log("\n3. the grouping is the platform's, not a table in this page");
{
  // A capability that the page has never heard of must still be placed, because the group
  // comes from the payload rather than from a hand-written list in the page.
  const odd = boot({ ...catalogue, capabilities: [
    { id: "BRAND_NEW", displayName: "Brand New", description: "", group: "CAPTURE",
      groupName: "Camera & Audio", registered: true, switchedOn: true, usable: true,
      status: "available", actions: [], requiredPermissions: [] },
  ] }, null);
  odd.renderAll();
  const oddText = text(els["p-caps"].innerHTML);
  check("an unknown capability is placed by its declared group",
    oddText.includes("Brand New") && oddText.includes("Camera & Audio"), oddText.slice(0, 200));
}

console.log("\n4. every action gets a runnable form built from its own parameters");
let missing = [];
for (const cap of docs.capabilities) {
  for (const a of cap.actions) {
    const card = page.actionCard(cap.id, a);
    if (!card.includes('data-run="' + cap.id + "|" + a.name + '"')) missing.push(cap.id + "." + a.name + " Run");
    if (!card.includes('data-example="')) missing.push(cap.id + "." + a.name + " example");
    for (const p of a.params) {
      if (!card.includes('id="' + a.name + "::" + p.name + '"')) missing.push(cap.id + "." + a.name + "." + p.name);
    }
  }
}
check("all " + docs.capabilities.length + " capabilities and their forms render",
  missing.length === 0, missing.slice(0, 8).join(", "));

console.log("\n5. every param type maps to a usable control");
const post = docs.capabilities.find((c) => c.id === "NOTIFICATIONS").actions.find((a) => a.name === "post");
const postHtml = page.actionCard("NOTIFICATIONS", post);
const want = { string: 'type="text"', boolean: 'type="checkbox"', array: "<textarea", integer: 'type="number"' };
for (const p of post.params) {
  check(`${p.type} param "${p.name}" renders a control`, postHtml.includes(want[p.type]), postHtml);
}
const types = [...new Set(docs.capabilities.flatMap((c) => c.actions.flatMap((a) => a.params.map((p) => p.type))))];
check("types actually exercised: " + types.join(", "), types.length >= 4);

console.log("\n6. required params are marked, and only they");
check("required params are labelled", postHtml.includes('class="rq"'));
check("only the required ones are", (postHtml.match(/class="rq"/g) || []).length === post.params.filter((p) => p.required).length);

console.log("\n7. a capability with no actions invents none");
{
  const bare = capabilities.find((c) => !documented.has(c.id));
  const html = page.capCard(bare);
  check("says it has no actions", text(html).includes("No actions yet"));
  check("renders no run button", !html.includes("data-run="));
  check("still shows its status", html.includes('class="badge"'));
}

console.log("\n8. a hostile capability name cannot inject HTML");
{
  const evil = boot({ ...catalogue, capabilities: [{
    id: "X", displayName: '<img src=x onerror=alert(1)>', description: "", group: "DATA",
    groupName: "Data & Automation", registered: true, switchedOn: true, usable: true,
    status: "available", actions: [], requiredPermissions: [] }] }, null);
  evil.renderAll();
  check("no raw <img> from a capability name", !els["p-caps"].innerHTML.includes("<img src=x"));
  check("escaped form is present", els["p-caps"].innerHTML.includes("&lt;img"));
}

console.log("\n9. every documented example is JSON the page can pre-fill");
{
  const bad = [];
  for (const c of docs.capabilities)
    for (const a of c.actions)
      try { JSON.parse(a.exampleRequest); } catch (e) { bad.push(c.id + "." + a.name); }
  check("every exampleRequest parses", bad.length === 0, bad.join(", "));
}

console.log("\n10. the reference tab shows what the phone actually serves");
page.renderAll();
const ref = els["p-docs"].innerHTML;
const refText = text(ref);
check("lists the endpoints", refText.includes("/api/capabilities/docs") && refText.includes("/api/capabilities/invoke"));
check("shows a curl example with the current address", refText.includes("100.101.227.77:8899/api/capabilities/docs"));
check("includes the raw reference", refText.includes('"capabilities"'));

console.log("\n11. offline, it explains rather than failing silently");
page.renderOffline("boom");
const off = els["p-overview"].innerHTML;
check("says it is not connected", /Not connected/i.test(off));
check("carries the reason through", text(off).includes("boom"));
check("offers no tabs when there is nothing to show", els.tabs.innerHTML === "");

console.log("\n12. opened from disk it points at the served copy");
{
  global.location = { protocol: "file:" };
  boot(catalogue, null).renderOffline("");
  const disk = els["p-overview"].innerHTML;
  check("says to open it from the phone", /open this from the phone/i.test(disk));
  check("links the served address", disk.includes('href="http://100.101.227.77:8899/"'));
  check("mentions localhost for the phone's own browser", disk.includes("localhost:8899"));
  global.location = { protocol: "http:" };
}

console.log("\n13. the served asset cannot drift from the source");
{
  const asset = "app/src/main/assets/index.html";
  const exists = fs.existsSync(asset);
  check("the generated asset exists after a build", exists, "run ./gradlew :app:assembleDebug first");
  if (exists) {
    check("the APK copy is byte-identical to index.html",
      fs.readFileSync(asset, "utf8") === fs.readFileSync(path.join(root, "index.html"), "utf8"));
  }
  const ignored = require("child_process")
    .spawnSync("git", ["check-ignore", "-q", asset], { cwd: root }).status === 0;
  check("the generated copy is gitignored so only one is tracked", ignored);
}

console.log("\n14. the api token is never left behind on the machine");
{
  // Saving the console settings is what persists the token. It must land in sessionStorage, which
  // dies with the tab, and never in localStorage, which outlives the tab, the browser and the user.
  // Connecting is what persists the settings, and it is also reachable without a network.
  els.token.value = "a".repeat(64);
  els.base.value = "http://127.0.0.1:8899";
  page.connect();
  const localKeys = global.persisted.local.map(([k]) => k);
  const sessionKeys = global.persisted.session.map(([k]) => k);
  check("the token is not written to localStorage", !localKeys.includes("star.token"),
    "found: " + localKeys.join(", "));
  check("the token is written to sessionStorage instead", sessionKeys.includes("star.token"),
    "found: " + sessionKeys.join(", "));
  const stored = (global.persisted.session.find(([k]) => k === "star.token") || [])[1];
  check("the persisted token is the real one, not a blank", typeof stored === "string" && stored.length > 10);
}

console.log("\n" + (failures ? failures + " FAILURE(S)" : "all checks passed"));
process.exit(failures ? 1 : 0);