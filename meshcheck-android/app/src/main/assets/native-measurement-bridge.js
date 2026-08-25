(function () {
  "use strict";
  if (window.MeshCheckMeasurementBridgeInstalled) return;
  window.MeshCheckMeasurementBridgeInstalled = true;

  function nativeMeasurement() {
    const value = window.MeshCheckNativeMeasurement;
    return value && value.valid && Number(value.threadsPerCm) > 0 ? value : null;
  }

  const originalInspect = MeshAnalyzer.autoInspect;
  MeshAnalyzer.autoInspect = function (imageData) {
    const native = nativeMeasurement();
    let original = null;
    try {
      original = originalInspect(imageData);
    } catch (error) {
      if (!native) throw error;
    }
    if (!native) return original;

    const warnings = original && Array.isArray(original.warnings) ? original.warnings.slice() : [];
    warnings.push(
      "عدد الخيوط مأخوذ من العد المباشر للخطوط الكاملة داخل مسطرة 1 cm الوسطية: " +
      native.fullLinesInOneCm + " خط في الإطار، ومتوسط ثابت " +
      Number(native.threadsPerCm).toFixed(1) + " خيط/سم."
    );

    const result = original && original.ok ? Object.assign({}, original) : {
      ok: true,
      components: [],
      openingMicrons: null,
      micronsPerPixel: null,
      warnings: []
    };
    result.pitchMicrons = 10000 / Number(native.threadsPerCm);
    result.threadsPerCm = Number(native.threadsPerCm);
    result.threadsPerInch = Number(native.threadsPerCm) * 2.54;
    result.confidence = Math.max(Number(result.confidence) || 0, Number(native.confidence) || 0.55);
    result.warnings = warnings;
    result.threadCountSource = "centered_1cm_full_line_count";
    result.fullLinesInOneCm = Number(native.fullLinesInOneCm) || 0;
    return result;
  };

  const originalMatchCandidates = SefarCatalog.matchCandidates;
  SefarCatalog.matchCandidates = function (measurement, materialFilter) {
    const native = nativeMeasurement();
    if (!native) return originalMatchCandidates(measurement, materialFilter);

    const measuredCount = Number(native.threadsPerCm);
    const measuredOpening = Number(measurement && measurement.openingMicrons);
    const material = materialFilter && materialFilter !== "Auto" ? materialFilter : null;

    return SefarCatalog.fabrics
      .filter(function (item) { return !material || item.material === material; })
      .map(function (item) {
        const options = Array.isArray(item.threadCountsPerCm)
          ? item.threadCountsPerCm.map(Number).filter(function (value) { return value > 0; })
          : [Number(item.threadsPerCm)].filter(function (value) { return value > 0; });
        let countError = Infinity;
        let matchedThreadCount = null;
        options.forEach(function (catalogCount) {
          const error = Math.abs(measuredCount - catalogCount) / Math.max(1, catalogCount);
          if (error < countError) {
            countError = error;
            matchedThreadCount = catalogCount;
          }
        });
        const openingError = measuredOpening > 0
          ? Math.abs(measuredOpening - item.openingMicrons) / Math.max(1, item.openingMicrons)
          : null;
        const score = openingError == null ? countError : countError * 0.82 + openingError * 0.18;
        return Object.assign({}, item, {
          matchedThreadCountPerCm: matchedThreadCount,
          threadCountError: countError,
          openingError: openingError,
          score: score,
          confidence: Math.max(0, Math.min(99, Math.round(100 * (1 - score * 2.3))))
        });
      })
      .sort(function (a, b) {
        if (a.score !== b.score) return a.score - b.score;
        return a.code.localeCompare(b.code);
      });
  };

  const picker = document.getElementById("imagePicker");
  if (picker) {
    picker.addEventListener("change", function () {
      if (window.MeshCheckNativeMeasurementPending) {
        window.MeshCheckNativeMeasurementPending = false;
      } else {
        window.MeshCheckNativeMeasurement = null;
      }
    }, true);
  }
})();
