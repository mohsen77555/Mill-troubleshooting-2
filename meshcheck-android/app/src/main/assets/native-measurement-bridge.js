(function () {
  "use strict";
  if (window.MeshCheckMeasurementBridgeInstalled) return;
  window.MeshCheckMeasurementBridgeInstalled = true;

  function nativeMeasurement() {
    const value = window.MeshCheckNativeMeasurement;
    return value && value.valid && Number(value.threadsPerCm) > 0 ? value : null;
  }

  function measuredCounts(native) {
    const arr = Array.isArray(native.threadCountsPerCm)
      ? native.threadCountsPerCm.map(Number).filter(v => v > 0)
      : [];
    if (arr.length) return arr;
    const single = Number(native.threadsPerCm);
    return single > 0 ? [single] : [];
  }

  const originalInspect = MeshAnalyzer.autoInspect;
  MeshAnalyzer.autoInspect = function (imageData) {
    const native = nativeMeasurement();
    let original = null;
    try { original = originalInspect(imageData); }
    catch (error) { if (!native) throw error; }
    if (!native) return original;

    const counts = measuredCounts(native);
    const average = counts.reduce((a, b) => a + b, 0) / Math.max(1, counts.length);
    const warnings = original && Array.isArray(original.warnings) ? original.warnings.slice() : [];
    if (native.source === "marker_20x20_mm") {
      warnings.push(
        "Thread count measured from the physical 20×20 mm marker: X=" +
        Number(native.threadsXPerCm || 0).toFixed(1) + "/cm, Y=" +
        Number(native.threadsYPerCm || 0).toFixed(1) + "/cm."
      );
    }

    const result = original && original.ok ? Object.assign({}, original) : {
      ok: true, components: [], openingMicrons: null, micronsPerPixel: null, warnings: []
    };
    result.pitchMicrons = average > 0 ? 10000 / average : null;
    result.threadsPerCm = average;
    result.threadCountsPerCm = counts;
    result.threadsPerInch = average * 2.54;
    result.confidence = Math.max(Number(result.confidence) || 0, Number(native.confidence) || 0.55);
    result.warnings = warnings;
    result.threadCountSource = native.source || "native_camera";
    result.physicalWindowMm = Number(native.physicalWindowMm) || null;
    return result;
  };

  function relativeError(a, b) {
    return Math.abs(a - b) / Math.max(1, b);
  }

  function countMatchError(measured, catalog) {
    if (!measured.length || !catalog.length) return Infinity;
    if (measured.length === 1) {
      return Math.min.apply(null, catalog.map(c => relativeError(measured[0], c)));
    }
    if (catalog.length === 1) {
      return measured.reduce((sum, m) => sum + relativeError(m, catalog[0]), 0) / measured.length;
    }
    const m0 = measured[0], m1 = measured[1], c0 = catalog[0], c1 = catalog[1];
    const direct = (relativeError(m0, c0) + relativeError(m1, c1)) / 2;
    const swapped = (relativeError(m0, c1) + relativeError(m1, c0)) / 2;
    return Math.min(direct, swapped);
  }

  const originalMatchCandidates = SefarCatalog.matchCandidates;
  SefarCatalog.matchCandidates = function (measurement, materialFilter) {
    const native = nativeMeasurement();
    if (!native) return originalMatchCandidates(measurement, materialFilter);

    const measured = measuredCounts(native);
    const measuredOpening = Number(measurement && measurement.openingMicrons);
    const material = materialFilter && materialFilter !== "Auto" ? materialFilter : null;

    return SefarCatalog.fabrics
      .filter(item => !material || item.material === material)
      .map(item => {
        const catalog = Array.isArray(item.threadCountsPerCm)
          ? item.threadCountsPerCm.map(Number).filter(v => v > 0)
          : [Number(item.threadsPerCm)].filter(v => v > 0);
        const threadCountError = countMatchError(measured, catalog);
        const openingError = measuredOpening > 0
          ? Math.abs(measuredOpening - item.openingMicrons) / Math.max(1, item.openingMicrons)
          : null;
        const score = openingError == null
          ? threadCountError
          : threadCountError * 0.84 + openingError * 0.16;
        return Object.assign({}, item, {
          measuredThreadCountsPerCm: measured.slice(),
          threadCountError,
          openingError,
          score,
          confidence: Math.max(0, Math.min(99, Math.round(100 * (1 - score * 2.3))))
        });
      })
      .sort((a, b) => a.score !== b.score ? a.score - b.score : a.code.localeCompare(b.code));
  };

  const picker = document.getElementById("imagePicker");
  if (picker) {
    picker.addEventListener("change", function () {
      if (window.MeshCheckNativeMeasurementPending) window.MeshCheckNativeMeasurementPending = false;
      else window.MeshCheckNativeMeasurement = null;
    }, true);
  }
})();
