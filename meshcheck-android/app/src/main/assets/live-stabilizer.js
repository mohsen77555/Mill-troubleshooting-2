(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.LiveStabilizer = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function median(values) {
    if (!values.length) return null;
    const copy = values.slice().sort(function (a, b) { return a - b; });
    const middle = Math.floor(copy.length / 2);
    return copy.length % 2 ? copy[middle] : (copy[middle - 1] + copy[middle]) / 2;
  }

  function relativeSpread(values, center) {
    if (!values.length || !(center > 0)) return null;
    const low = Math.min.apply(null, values);
    const high = Math.max.apply(null, values);
    return (high - low) / center;
  }

  function create(options) {
    options = options || {};
    const windowSize = Math.max(3, Number(options.windowSize) || 5);
    const minSamples = Math.max(2, Math.min(windowSize, Number(options.minSamples) || 4));
    const toleranceRatio = Math.max(0.01, Number(options.toleranceRatio) || 0.08);
    let samples = [];

    function reset() {
      samples = [];
    }

    function snapshot() {
      const pitches = samples.map(function (sample) { return sample.pitchMicrons; }).filter(function (value) { return value > 0; });
      const openings = samples.map(function (sample) { return sample.openingMicrons; }).filter(function (value) { return value > 0; });
      const confidences = samples.map(function (sample) { return sample.confidence; }).filter(Number.isFinite);
      const pitch = median(pitches);
      const opening = openings.length >= Math.min(3, minSamples) ? median(openings) : null;
      const pitchSpread = relativeSpread(pitches, pitch);
      const openingSpread = opening ? relativeSpread(openings, opening) : null;
      const ready = pitches.length >= minSamples;
      const stable = ready && pitchSpread !== null && pitchSpread <= toleranceRatio && (openingSpread === null || openingSpread <= toleranceRatio * 1.5);
      return {
        ready: ready,
        stable: stable,
        samples: pitches.length,
        pitchMicrons: pitch,
        openingMicrons: opening,
        confidence: confidences.length ? median(confidences) : 0,
        pitchSpreadRatio: pitchSpread,
        openingSpreadRatio: openingSpread
      };
    }

    function push(sample) {
      if (!sample || !(Number(sample.pitchMicrons) > 0)) return snapshot();
      samples.push({
        pitchMicrons: Number(sample.pitchMicrons),
        openingMicrons: Number(sample.openingMicrons) > 0 ? Number(sample.openingMicrons) : null,
        confidence: Number.isFinite(Number(sample.confidence)) ? Number(sample.confidence) : 0
      });
      if (samples.length > windowSize) samples.shift();
      return snapshot();
    }

    return { push: push, reset: reset, snapshot: snapshot };
  }

  return { create: create, median: median };
});
