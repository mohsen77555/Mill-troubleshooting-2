const assert = require('assert');
const LiveStabilizer = require('../app/src/main/assets/live-stabilizer.js');

const stable = LiveStabilizer.create({ windowSize: 5, minSamples: 4, toleranceRatio: 0.08 });
stable.push({ pitchMicrons: 1300, openingMicrons: 1000, confidence: 0.90 });
stable.push({ pitchMicrons: 1292, openingMicrons: 995, confidence: 0.91 });
stable.push({ pitchMicrons: 1310, openingMicrons: 1008, confidence: 0.88 });
let result = stable.push({ pitchMicrons: 1304, openingMicrons: 1002, confidence: 0.92 });
assert.equal(result.ready, true);
assert.equal(result.stable, true);
assert.ok(Math.abs(result.pitchMicrons - 1302) < 12);
assert.ok(Math.abs(result.openingMicrons - 1001) < 12);

const unstable = LiveStabilizer.create({ windowSize: 5, minSamples: 4, toleranceRatio: 0.08 });
unstable.push({ pitchMicrons: 1300, confidence: 0.9 });
unstable.push({ pitchMicrons: 1120, confidence: 0.9 });
unstable.push({ pitchMicrons: 1450, confidence: 0.9 });
result = unstable.push({ pitchMicrons: 1250, confidence: 0.9 });
assert.equal(result.ready, true);
assert.equal(result.stable, false);

console.log('live-stabilizer tests passed');
