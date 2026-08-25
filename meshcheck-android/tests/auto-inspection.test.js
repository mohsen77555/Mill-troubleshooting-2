const assert = require("assert");
const MeshAnalyzer = require("../app/src/main/assets/mesh-analyzer.js");
const SefarCatalog = require("../app/src/main/assets/sefar-catalog.js");

function createSyntheticRulerMesh() {
  const width = 800;
  const height = 800;
  const data = new Uint8ClampedArray(width * height * 4);
  function paint(x, y, value) {
    const index = (y * width + x) * 4;
    data[index] = value;
    data[index + 1] = value;
    data[index + 2] = value;
    data[index + 3] = 255;
  }
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) paint(x, y, 18);
  }
  // 1 mm ruler: 20 pixels between ticks.
  for (let x = 60; x < 745; x += 20) {
    for (let y = 32; y < 118; y += 1) {
      for (let dx = -2; dx <= 2; dx += 1) paint(x + dx, y, 235);
    }
  }
  // A 1.30 mm pitch fabric: 26 px pitch at 20 px/mm.
  const meshLeft = 70;
  const meshTop = 145;
  const meshRight = 735;
  const meshBottom = 730;
  for (let y = meshTop; y < meshBottom; y += 1) {
    for (let x = meshLeft; x < meshRight; x += 1) paint(x, y, 30);
  }
  for (let x = meshLeft; x < meshRight; x += 26) {
    for (let y = meshTop; y < meshBottom; y += 1) {
      for (let dx = 0; dx < 6 && x + dx < meshRight; dx += 1) paint(x + dx, y, 220);
    }
  }
  for (let y = meshTop; y < meshBottom; y += 26) {
    for (let x = meshLeft; x < meshRight; x += 1) {
      for (let dy = 0; dy < 6 && y + dy < meshBottom; dy += 1) paint(x, y + dy, 220);
    }
  }
  return { data, width, height };
}

const result = MeshAnalyzer.autoInspect(createSyntheticRulerMesh());
assert.equal(result.ok, true, result.reason);
assert.ok(Math.abs(result.pitchMicrons - 1300) < 90, "pitch should be near 1300 microns");
assert.ok(Math.abs(result.threadsPerInch - 19.5) < 1.3, "thread count should be near 19.5/in");
assert.ok(result.openingMicrons > 700 && result.openingMicrons < 1100, "opening should be detected");
const candidates = SefarCatalog.matchCandidates(result, "PA");
assert.equal(candidates[0].code, "PA-20GG-1000");
console.log("automatic ruler and Sefar matching tests passed");
