// Checks that the documentation compiled into ascli matches the fixture on disk.
//
// There are two copies on purpose: the working-tree one, which is what `make generate` builds from
// and what a developer edits, and the embedded one, which is what an installed binary reads. They
// drift silently otherwise — an installed ascli would describe an older API from a checkout of the
// same tag, which is the worst kind of disagreement because both look right.
//
//   node tools/check-fixture-embedded.js     verify
//   node tools/check-fixture-embedded.js --fix   copy the fixture in

const fs = require("fs");
const path = require("path");

// This script lives in the repository's tools/, while both copies live under ascli/, so the paths
// are spelled out rather than derived from a root that would be one level too high.
const root = path.join(path.resolve(__dirname, ".."), "ascli");
const fixture = path.join(root, "testdata", "docs.json");
const embedded = path.join(root, "internal", "cmds", "docsfixture", "docs.json");

const fix = process.argv.includes("--fix");

if (!fs.existsSync(fixture)) {
  console.log("  no fixture at testdata/docs.json — nothing to compare");
  process.exit(0);
}

if (fix) {
  fs.mkdirSync(path.dirname(embedded), { recursive: true });
  fs.copyFileSync(fixture, embedded);
  console.log("  embedded copy updated from testdata/docs.json");
  process.exit(0);
}

if (!fs.existsSync(embedded)) {
  console.log("  FAIL the docs fixture is not embedded, so an installed ascli cannot describe the API");
  console.log("       fix it with: node tools/check-fixture-embedded.js --fix");
  process.exit(1);
}

const left = JSON.parse(fs.readFileSync(fixture, "utf8"));
const right = JSON.parse(fs.readFileSync(embedded, "utf8"));

if (JSON.stringify(left) === JSON.stringify(right)) {
  const features = (left.capabilities || []).length;
  const actions = (left.capabilities || []).reduce((n, c) => n + (c.actions || []).length, 0);
  console.log(`  ok   the embedded copy matches the fixture (${features} features, ${actions} actions)`);
  process.exit(0);
}

// Name what differs, so this is fixable without reading both files side by side.
const differences = [];
for (const key of new Set([...Object.keys(left), ...Object.keys(right)])) {
  if (JSON.stringify(left[key]) !== JSON.stringify(right[key])) {
    differences.push(key);
  }
}

console.log("  FAIL the embedded copy differs from testdata/docs.json");
console.log(`       differing: ${differences.join(", ") || "(formatting only)"}`);
console.log("       fix it with: node tools/check-fixture-embedded.js --fix");
process.exit(1);
