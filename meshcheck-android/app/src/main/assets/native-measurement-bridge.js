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

  function numericArray(value) {
    return Array.isArray(value) ? value.map(Number).filter(v => Number.isFinite(v) && v > 0) : [];
  }

  function average(values) {
    return values.length ? values.reduce((a, b) => a + b, 0) / values.length : 0;
  }

  const originalInspect = MeshAnalyzer.autoInspect;
  MeshAnalyzer.autoInspect = function (imageData) {
    const native = nativeMeasurement();
    let original = null;
    try { original = originalInspect(imageData); }
    catch (error) { if (!native) throw error; }
    if (!native) return original;

    const counts = measuredCounts(native);
    const avgCount = average(counts);
    const nativeOpening = Number(native.openingMicrons) || average(numericArray(native.openingMicronsXY));
    const nativeYarn = Number(native.yarnMicrons) || average(numericArray(native.yarnMicronsXY));
    const nativePitch = Number(native.pitchMicrons) || average(numericArray(native.pitchMicronsXY));
    const uncertainty = Number(native.uncertaintyMicrons) || average(numericArray(native.uncertaintyMicronsXY));
    const quality = Number(native.quality) || 0;

    const warnings = original && Array.isArray(original.warnings) ? original.warnings.slice() : [];
    if (native.source === "lens_crop_20x20_high_accuracy") {
      warnings.push(
        "High-accuracy 20×20 mm crop: X=" + Number(native.threadsXPerCm || 0).toFixed(2) +
        "/cm, Y=" + Number(native.threadsYPerCm || 0).toFixed(2) +
        "/cm, opening≈" + Math.round(nativeOpening) + " µm, yarn≈" + Math.round(nativeYarn) +
        " µm, uncertainty≈±" + Math.round(uncertainty) + " µm, quality=" + Math.round(quality * 100) + "% ."
      );
    } else if (native.source === "manual_20x20_mm_roi") {
      warnings.push(
        "Thread count measured inside the physical 20×20 mm square: X=" +
        Number(native.threadsXPerCm || 0).toFixed(1) + "/cm, Y=" +
        Number(native.threadsYPerCm || 0).toFixed(1) + "/cm."
      );
    } else if (native.source === "marker_20x20_mm") {
      warnings.push(
        "Thread count measured from the physical 20×20 mm marker: X=" +
        Number(native.threadsXPerCm || 0).toFixed(1) + "/cm, Y=" +
        Number(native.threadsYPerCm || 0).toFixed(1) + "/cm."
      );
    }

    const result = original && original.ok ? Object.assign({}, original) : {
      ok: true, components: [], openingMicrons: null, micronsPerPixel: null, warnings: []
    };
    result.pitchMicrons = nativePitch > 0 ? nativePitch : (avgCount > 0 ? 10000 / avgCount : null);
    result.threadsPerCm = avgCount;
    result.threadCountsPerCm = counts;
    result.threadsPerInch = avgCount * 2.54;
    if (nativeOpening > 0) result.openingMicrons = nativeOpening;
    if (nativeYarn > 0) result.yarnMicrons = nativeYarn;
    result.pitchMicronsXY = numericArray(native.pitchMicronsXY);
    result.openingMicronsXY = numericArray(native.openingMicronsXY);
    result.yarnMicronsXY = numericArray(native.yarnMicronsXY);
    result.uncertaintyMicrons = uncertainty || null;
    result.measurementQuality = quality || null;
    result.nativeSharpness = Number(native.sharpness) || null;
    result.burstSharpness = Number(native.burstSharpness) || null;
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

  function catalogYarnOptions(item) {
    const out = [];
    if (typeof item.yarnMicrons === "number" && item.yarnMicrons > 0) out.push(item.yarnMicrons);
    if (Array.isArray(item.yarns)) {
      item.yarns.forEach(v => {
        const text = String(v).trim();
        if (/^\d+(\.\d+)?$/.test(text)) out.push(Number(text));
      });
    }
    return out;
  }

  function nearestScalarError(measured, options) {
    if (!(measured > 0) || !options.length) return null;
    return Math.min.apply(null, options.map(v => relativeError(measured, v)));
  }

  const originalMatchCandidates = SefarCatalog.matchCandidates;
  SefarCatalog.matchCandidates = function (measurement, materialFilter) {
    const native = nativeMeasurement();
    if (!native) return originalMatchCandidates(measurement, materialFilter);

    const measuredCountsArray = measuredCounts(native);
    const measuredOpenings = numericArray(native.openingMicronsXY);
    const measuredYarns = numericArray(native.yarnMicronsXY);
    const measuredOpening = Number(native.openingMicrons) || average(measuredOpenings);
    const measuredYarn = Number(native.yarnMicrons) || average(measuredYarns);
    const material = materialFilter && materialFilter !== "Auto" ? materialFilter : null;

    return SefarCatalog.fabrics
      .filter(item => !material || item.material === material)
      .map(item => {
        const catalogCounts = Array.isArray(item.threadCountsPerCm)
          ? item.threadCountsPerCm.map(Number).filter(v => v > 0)
          : [Number(item.threadsPerCm)].filter(v => v > 0);
        const threadCountError = countMatchError(measuredCountsArray, catalogCounts);
        const openingError = measuredOpening > 0
          ? relativeError(measuredOpening, Number(item.openingMicrons))
          : null;
        const yarnOptions = catalogYarnOptions(item);
        const yarnError = nearestScalarError(measuredYarn, yarnOptions);

        let score;
        if (openingError != null && yarnError != null) {
          score = threadCountError * 0.58 + openingError * 0.28 + yarnError * 0.14;
        } else if (openingError != null) {
          score = threadCountError * 0.68 + openingError * 0.32;
        } else {
          score = threadCountError;
        }

        const quality = Number(native.quality) || 0.7;
        const qualityPenalty = Math.max(0, 0.75 - quality) * 0.10;
        score += qualityPenalty;

        return Object.assign({}, item, {
          measuredThreadCountsPerCm: measuredCountsArray.slice(),
          measuredOpeningMicrons: measuredOpening || null,
          measuredYarnMicrons: measuredYarn || null,
          threadCountError,
          openingError,
          yarnError,
          score,
          confidence: Math.max(0, Math.min(99, Math.round(100 * (1 - score * 2.3) * Math.min(1, 0.82 + quality * 0.18))))
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
