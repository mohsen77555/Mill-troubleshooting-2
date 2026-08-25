const assert = require("assert");
const SefarCatalog = require("../app/src/main/assets/sefar-catalog.js");

assert.equal(SefarCatalog.sourceOnly, true);
assert.equal(SefarCatalog.sourceDocument, "sefar milling-EN(3).pdf");
assert.equal(SefarCatalog.fabrics.length, 189, "catalog should contain exactly 189 complete specifications from pages 3-8");

function row(code, family) {
  const found = SefarCatalog.fabrics.find(item => item.code === code && (!family || item.family === family));
  assert.ok(found, `missing ${code}${family ? ` / ${family}` : ""}`);
  return found;
}

// Page 3 — PA-GG
let item = row("PA-20GG-1000", "PA-GG");
assert.equal(item.openingMicrons, 1000);
assert.equal(item.yarnMicrons, 320);
assert.equal(item.threadsPerCm, 7.5);
assert.equal(item.threadsPerInch, 19.1);
assert.equal(item.openAreaPercent, 57);
assert.equal(item.sourcePage, 3);

// Page 4 — PA-Milling directional construction
item = row("PA-9-150", "PA-Milling");
assert.equal(item.openingMicrons, 150);
assert.deepEqual(item.yarns, ["70+2·60", "60"]);
assert.deepEqual(item.threadCountsPerCm, [42.5, 47.6]);
assert.deepEqual(item.threadCountsPerInch, [108, 121]);
assert.equal(item.openAreaPercent, 44);
assert.equal(item.sourcePage, 4);

// Page 5 — Metal Mesh
item = row("26-1120/69", "Metal Mesh");
assert.equal(item.openingMicrons, 1120);
assert.equal(item.yarnMicrons, 220);
assert.equal(item.threadsPerInch, 19.0);
assert.equal(item.openAreaPercent, 69.4);

// Page 5 — Heavy must remain a separate family
item = row("26-1120/61", "Metal Mesh Heavy");
assert.equal(item.openingMicrons, 1120);
assert.equal(item.yarnMicrons, 315);
assert.equal(item.threadsPerCm, 7.0);
assert.equal(item.threadsPerInch, 17.8);
assert.equal(item.openAreaPercent, 61);

// Page 6 — values that were wrong in the previous app catalog
item = row("PET-38GG-500", "PET-GG");
assert.equal(item.threadsPerCm, 14);
assert.equal(item.threadsPerInch, 35.6);
assert.equal(item.openAreaPercent, 48);
item = row("PET-40GG-475", "PET-GG");
assert.equal(item.threadsPerCm, 15);
assert.equal(item.threadsPerInch, 38.1);
assert.equal(item.openAreaPercent, 49);
item = row("PET-42GG-450", "PET-GG");
assert.equal(item.threadsPerCm, 15.4);
assert.equal(item.threadsPerInch, 39.1);
assert.equal(item.openAreaPercent, 48);
item = row("PET-45GG-400", "PET-GG");
assert.equal(item.threadsPerCm, 17.2);
assert.equal(item.threadsPerInch, 43.7);
assert.equal(item.openAreaPercent, 48);
item = row("PET-56GG-300", "PET-GG");
assert.equal(item.threadsPerCm, 22.5);
assert.equal(item.threadsPerInch, 57.2);
assert.equal(item.openAreaPercent, 46);

// Page 8 — PA-HD
item = row("PA-9HD-150", "PA-HD");
assert.equal(item.openingMicrons, 150);
assert.equal(item.yarnMicrons, 95);
assert.equal(item.threadsPerCm, 41);
assert.equal(item.threadsPerInch, 104);
assert.equal(item.openAreaPercent, 38);

// Matching still identifies the classic 1.32 mm pitch PA-GG example.
const match = SefarCatalog.matchCandidates({ pitchMicrons: 1320, openingMicrons: 1000 }, "PA");
assert.equal(match[0].code, "PA-20GG-1000");
assert.equal(match[0].family, "PA-GG");

console.log("SEFAR PDF-only catalog source tests passed");
