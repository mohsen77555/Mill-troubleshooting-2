const assert = require("assert");
const MeshAnalyzer = require("../app/src/main/assets/mesh-analyzer.js");

function makeImage(width, height, brightOpenings) {
  const data = new Uint8ClampedArray(width * height * 4);
  const background = brightOpenings ? 0 : 255;
  const opening = brightOpenings ? 255 : 0;
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const index = (y * width + x) * 4;
      data[index] = background;
      data[index + 1] = background;
      data[index + 2] = background;
      data[index + 3] = 255;
    }
  }
  [[10, 10], [35, 10], [10, 35], [35, 35]].forEach(([left, top]) => {
    for (let y = top; y < top + 10; y += 1) {
      for (let x = left; x < left + 10; x += 1) {
        const index = (y * width + x) * 4;
        data[index] = opening;
        data[index + 1] = opening;
        data[index + 2] = opening;
      }
    }
  });
  return { data, width, height };
}

function runCase(openingsBright) {
  const result = MeshAnalyzer.analyze(makeImage(80, 80, openingsBright), {
    micronsPerPixel: 10,
    minAreaPx: 25,
    openingsBright,
    targetMicrons: 100,
    tolerancePercent: 5
  });
  assert.equal(result.components.length, 4, "four independent 10 x 10 openings should be detected");
  assert.equal(result.stats.mean, 100, "mean aperture should use the calibrated bbox dimensions");
  assert.equal(result.stats.flaggedCount, 0, "all synthetic openings should meet target tolerance");
  assert.equal(result.stats.openAreaPercent, 6.25, "open area should equal 400 / 6400");
}

runCase(true);
runCase(false);
console.log("mesh-analyzer tests passed");
