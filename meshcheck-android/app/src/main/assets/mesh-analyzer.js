(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.MeshAnalyzer = api;
})(typeof window !== "undefined" ? window : globalThis, function () {
  "use strict";

  function toGray(imageData) {
    const source = imageData.data;
    const pixels = new Uint8Array(imageData.width * imageData.height);
    const histogram = new Uint32Array(256);
    for (let pixel = 0, sourceIndex = 0; pixel < pixels.length; pixel += 1, sourceIndex += 4) {
      const value = Math.round(source[sourceIndex] * 0.2126 + source[sourceIndex + 1] * 0.7152 + source[sourceIndex + 2] * 0.0722);
      pixels[pixel] = value;
      histogram[value] += 1;
    }
    return { pixels: pixels, histogram: histogram };
  }

  function otsuThreshold(histogram, total) {
    let sum = 0;
    for (let i = 0; i < 256; i += 1) sum += i * histogram[i];
    let backgroundWeight = 0;
    let backgroundSum = 0;
    let bestThreshold = 127;
    let largestVariance = -1;
    for (let threshold = 0; threshold < 256; threshold += 1) {
      backgroundWeight += histogram[threshold];
      if (backgroundWeight === 0) continue;
      const foregroundWeight = total - backgroundWeight;
      if (foregroundWeight === 0) break;
      backgroundSum += threshold * histogram[threshold];
      const backgroundMean = backgroundSum / backgroundWeight;
      const foregroundMean = (sum - backgroundSum) / foregroundWeight;
      const betweenVariance = backgroundWeight * foregroundWeight * Math.pow(backgroundMean - foregroundMean, 2);
      if (betweenVariance > largestVariance) {
        largestVariance = betweenVariance;
        bestThreshold = threshold;
      }
    }
    return bestThreshold;
  }

  function numericStats(values) {
    if (!values.length) return null;
    let sum = 0;
    let min = Infinity;
    let max = -Infinity;
    for (let i = 0; i < values.length; i += 1) {
      const value = values[i];
      sum += value;
      min = Math.min(min, value);
      max = Math.max(max, value);
    }
    const mean = sum / values.length;
    let squaredDifference = 0;
    for (let i = 0; i < values.length; i += 1) squaredDifference += Math.pow(values[i] - mean, 2);
    const standardDeviation = Math.sqrt(squaredDifference / values.length);
    return { mean: mean, min: min, max: max, standardDeviation: standardDeviation };
  }

  function clamp(value, minimum, maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  function cropImageData(imageData, x, y, width, height) {
    const x0 = clamp(Math.floor(x), 0, imageData.width - 1);
    const y0 = clamp(Math.floor(y), 0, imageData.height - 1);
    const cropWidth = clamp(Math.floor(width), 1, imageData.width - x0);
    const cropHeight = clamp(Math.floor(height), 1, imageData.height - y0);
    const data = new Uint8ClampedArray(cropWidth * cropHeight * 4);
    for (let row = 0; row < cropHeight; row += 1) {
      const sourceStart = ((y0 + row) * imageData.width + x0) * 4;
      const targetStart = row * cropWidth * 4;
      data.set(imageData.data.subarray(sourceStart, sourceStart + cropWidth * 4), targetStart);
    }
    return { imageData: { data: data, width: cropWidth, height: cropHeight }, x: x0, y: y0 };
  }

  function componentsForPolarity(imageData, options) {
    const settings = options || {};
    const width = imageData.width;
    const height = imageData.height;
    const minArea = Math.max(1, Math.floor(Number(settings.minAreaPx) || 1));
    const openingsBright = settings.openingsBright !== false;
    const grayResult = toGray(imageData);
    const threshold = otsuThreshold(grayResult.histogram, grayResult.pixels.length);
    const openingMask = new Uint8Array(width * height);
    for (let index = 0; index < openingMask.length; index += 1) {
      openingMask[index] = openingsBright ? Number(grayResult.pixels[index] > threshold) : Number(grayResult.pixels[index] <= threshold);
    }

    const visited = new Uint8Array(width * height);
    const queue = new Int32Array(width * height);
    const components = [];
    let acceptedArea = 0;
    for (let start = 0; start < openingMask.length; start += 1) {
      if (!openingMask[start] || visited[start]) continue;
      let head = 0;
      let tail = 0;
      queue[tail++] = start;
      visited[start] = 1;
      let area = 0;
      let xMin = width;
      let xMax = 0;
      let yMin = height;
      let yMax = 0;
      let touchesEdge = false;
      while (head < tail) {
        const index = queue[head++];
        const y = Math.floor(index / width);
        const x = index - y * width;
        area += 1;
        xMin = Math.min(xMin, x);
        xMax = Math.max(xMax, x);
        yMin = Math.min(yMin, y);
        yMax = Math.max(yMax, y);
        if (x === 0 || x === width - 1 || y === 0 || y === height - 1) touchesEdge = true;
        for (let dy = -1; dy <= 1; dy += 1) {
          for (let dx = -1; dx <= 1; dx += 1) {
            if (dx === 0 && dy === 0) continue;
            const neighborX = x + dx;
            const neighborY = y + dy;
            if (neighborX < 0 || neighborX >= width || neighborY < 0 || neighborY >= height) continue;
            const neighbor = neighborY * width + neighborX;
            if (openingMask[neighbor] && !visited[neighbor]) {
              visited[neighbor] = 1;
              queue[tail++] = neighbor;
            }
          }
        }
      }
      if (touchesEdge || area < minArea) continue;
      const widthPx = xMax - xMin + 1;
      const heightPx = yMax - yMin + 1;
      components.push({ x: xMin, y: yMin, widthPx: widthPx, heightPx: heightPx, areaPx: area });
      acceptedArea += area;
    }
    return { threshold: threshold, components: components, acceptedArea: acceptedArea };
  }

  // Manual calibrated analysis remains available when automatic detection is
  // not reliable enough for the image.
  function analyze(imageData, options) {
    const settings = options || {};
    const micronsPerPixel = Number(settings.micronsPerPixel);
    if (!(micronsPerPixel > 0)) throw new Error("micronsPerPixel must be greater than zero");
    const targetMicrons = Number(settings.targetMicrons);
    const hasTarget = targetMicrons > 0;
    const tolerancePercent = Math.max(0, Number(settings.tolerancePercent) || 0);
    const componentResult = componentsForPolarity(imageData, settings);
    const components = componentResult.components.map(function (component) {
      const widthMicrons = component.widthPx * micronsPerPixel;
      const heightMicrons = component.heightPx * micronsPerPixel;
      const openingMicrons = (widthMicrons + heightMicrons) / 2;
      const deviationPercent = hasTarget ? Math.abs(openingMicrons - targetMicrons) / targetMicrons * 100 : null;
      return Object.assign({}, component, {
        widthMicrons: widthMicrons,
        heightMicrons: heightMicrons,
        openingMicrons: openingMicrons,
        deviationPercent: deviationPercent,
        flagged: hasTarget && deviationPercent > tolerancePercent
      });
    });
    const openings = components.map(function (component) { return component.openingMicrons; });
    const widths = components.map(function (component) { return component.widthMicrons; });
    const heights = components.map(function (component) { return component.heightMicrons; });
    const apertureStats = numericStats(openings);
    const widthStats = numericStats(widths);
    const heightStats = numericStats(heights);
    const flaggedCount = components.filter(function (component) { return component.flagged; }).length;
    return {
      threshold: componentResult.threshold,
      components: components,
      stats: {
        count: components.length,
        mean: apertureStats ? apertureStats.mean : null,
        min: apertureStats ? apertureStats.min : null,
        max: apertureStats ? apertureStats.max : null,
        standardDeviation: apertureStats ? apertureStats.standardDeviation : null,
        coefficientOfVariation: apertureStats && apertureStats.mean ? apertureStats.standardDeviation / apertureStats.mean * 100 : null,
        meanWidth: widthStats ? widthStats.mean : null,
        meanHeight: heightStats ? heightStats.mean : null,
        openAreaPercent: componentResult.acceptedArea / (imageData.width * imageData.height) * 100,
        flaggedCount: flaggedCount
      }
    };
  }

  function meanProfile(imageData, bounds, axis) {
    const x0 = clamp(Math.floor(bounds.x), 0, imageData.width - 1);
    const y0 = clamp(Math.floor(bounds.y), 0, imageData.height - 1);
    const x1 = clamp(Math.ceil(bounds.x + bounds.width), x0 + 1, imageData.width);
    const y1 = clamp(Math.ceil(bounds.y + bounds.height), y0 + 1, imageData.height);
    const length = axis === "y" ? y1 - y0 : x1 - x0;
    const profile = new Float64Array(length);
    const data = imageData.data;
    if (axis === "y") {
      const divisor = Math.max(1, x1 - x0);
      for (let y = y0; y < y1; y += 1) {
        let sum = 0;
        for (let x = x0; x < x1; x += 1) {
          const index = (y * imageData.width + x) * 4;
          sum += data[index] * 0.2126 + data[index + 1] * 0.7152 + data[index + 2] * 0.0722;
        }
        profile[y - y0] = sum / divisor;
      }
    } else {
      const divisor = Math.max(1, y1 - y0);
      for (let x = x0; x < x1; x += 1) {
        let sum = 0;
        for (let y = y0; y < y1; y += 1) {
          const index = (y * imageData.width + x) * 4;
          sum += data[index] * 0.2126 + data[index + 1] * 0.7152 + data[index + 2] * 0.0722;
        }
        profile[x - x0] = sum / divisor;
      }
    }
    return profile;
  }

  function detrend(values) {
    const radius = clamp(Math.round(values.length / 35), 5, 45);
    const result = new Float64Array(values.length);
    for (let index = 0; index < values.length; index += 1) {
      let sum = 0;
      let count = 0;
      const start = Math.max(0, index - radius);
      const end = Math.min(values.length, index + radius + 1);
      for (let cursor = start; cursor < end; cursor += 1) {
        sum += values[cursor];
        count += 1;
      }
      result[index] = values[index] - sum / count;
    }
    return result;
  }

  function estimatePeriod(profile, minimum, maximum) {
    const values = detrend(profile);
    const start = Math.max(2, Math.round(minimum));
    const end = Math.min(values.length - 3, Math.round(maximum));
    if (end <= start) return null;
    const scores = [];
    let strongest = { lag: 0, score: -Infinity };
    for (let lag = start; lag <= end; lag += 1) {
      let numerator = 0;
      let leftEnergy = 0;
      let rightEnergy = 0;
      for (let index = 0; index < values.length - lag; index += 1) {
        const left = values[index];
        const right = values[index + lag];
        numerator += left * right;
        leftEnergy += left * left;
        rightEnergy += right * right;
      }
      const score = leftEnergy > 0 && rightEnergy > 0 ? numerator / Math.sqrt(leftEnergy * rightEnergy) : -1;
      scores.push({ lag: lag, score: score });
      if (score > strongest.score) strongest = { lag: lag, score: score };
    }
    const peaks = scores.filter(function (entry, index) {
      const previous = index ? scores[index - 1].score : -Infinity;
      const next = index < scores.length - 1 ? scores[index + 1].score : -Infinity;
      return entry.score >= previous && entry.score >= next && entry.score > 0;
    });
    const closePeaks = peaks.filter(function (entry) { return entry.score >= strongest.score * 0.86; });
    const chosen = closePeaks.length ? closePeaks[0] : strongest;
    return { periodPx: chosen.lag, confidence: clamp(chosen.score, 0, 1), strongestScore: strongest.score };
  }

  function bestRulerPeriod(imageData) {
    const width = imageData.width;
    const height = imageData.height;
    const topStart = Math.round(height * 0.015);
    const topEnd = Math.max(topStart + 20, Math.round(height * 0.17));
    const minPeriod = Math.max(6, Math.round(width * 0.012));
    const maxPeriod = Math.max(minPeriod + 4, Math.round(width * 0.09));
    const candidates = [];
    const bandHeight = Math.max(16, Math.round((topEnd - topStart) * 0.55));
    const positions = [topStart, Math.round(topStart + (topEnd - topStart) * 0.22), Math.round(topStart + (topEnd - topStart) * 0.44)];
    positions.forEach(function (y) {
      const profile = meanProfile(imageData, { x: width * 0.08, y: y, width: width * 0.84, height: bandHeight }, "x");
      const estimate = estimatePeriod(profile, minPeriod, maxPeriod);
      if (estimate) candidates.push(estimate);
    });
    if (!candidates.length) return null;
    candidates.sort(function (a, b) { return b.confidence - a.confidence; });
    return candidates[0];
  }

  function bestMeshPeriod(imageData, rulerPeriodPx) {
    const width = imageData.width;
    const height = imageData.height;
    const minPeriod = Math.max(5, rulerPeriodPx * 0.55);
    const maxPeriod = Math.min(width * 0.22, rulerPeriodPx * 3.5);
    const meshStart = Math.round(height * 0.16);
    const meshEnd = Math.max(meshStart + 30, Math.round(height * 0.40));
    const bandHeight = Math.max(20, Math.round((meshEnd - meshStart) * 0.50));
    const candidates = [];
    [meshStart, Math.round(meshStart + (meshEnd - meshStart) * 0.3)].forEach(function (y) {
      const profile = meanProfile(imageData, { x: width * 0.11, y: y, width: width * 0.78, height: bandHeight }, "x");
      const estimate = estimatePeriod(profile, minPeriod, maxPeriod);
      if (estimate) candidates.push(estimate);
    });
    if (!candidates.length) return null;
    candidates.sort(function (a, b) { return b.confidence - a.confidence; });
    return candidates[0];
  }

  function verticalMeshPeriod(imageData, rulerPeriodPx) {
    const profile = meanProfile(imageData, { x: imageData.width * 0.28, y: imageData.height * 0.16, width: imageData.width * 0.44, height: imageData.height * 0.68 }, "y");
    return estimatePeriod(profile, Math.max(5, rulerPeriodPx * 0.55), Math.min(imageData.height * 0.22, rulerPeriodPx * 3.5));
  }

  function openingEstimate(imageData, scaleMicronsPerPixel, pitchPixels) {
    const crop = cropImageData(imageData, imageData.width * 0.12, imageData.height * 0.18, imageData.width * 0.76, imageData.height * 0.68);
    const minArea = Math.max(8, Math.round(pitchPixels * pitchPixels * 0.035));
    const candidates = [true, false].map(function (openingsBright) {
      const raw = componentsForPolarity(crop.imageData, { openingsBright: openingsBright, minAreaPx: minArea });
      const components = raw.components.filter(function (component) {
        const aspect = component.widthPx / Math.max(1, component.heightPx);
        const averageSize = (component.widthPx + component.heightPx) / 2;
        const areaRatio = component.areaPx / (pitchPixels * pitchPixels);
        return averageSize >= pitchPixels * 0.28 && averageSize <= pitchPixels * 0.93 && aspect >= 0.55 && aspect <= 1.8 && areaRatio >= 0.05 && areaRatio <= 0.85;
      });
      const dimensions = components.map(function (component) { return (component.widthPx + component.heightPx) / 2; });
      const stats = numericStats(dimensions);
      const consistency = stats && stats.mean ? 1 / (1 + stats.standardDeviation / stats.mean) : 0;
      return { openingsBright: openingsBright, components: components, stats: stats, score: components.length * consistency };
    }).sort(function (a, b) { return b.score - a.score; });
    const best = candidates[0];
    if (!best || !best.stats || best.components.length < 4) return null;
    const openingPixels = best.stats.mean;
    return {
      openingMicrons: openingPixels * scaleMicronsPerPixel,
      openingRatio: openingPixels / pitchPixels,
      count: best.components.length,
      standardDeviationMicrons: best.stats.standardDeviation * scaleMicronsPerPixel,
      components: best.components.map(function (component) {
        return {
          x: component.x + crop.x,
          y: component.y + crop.y,
          widthPx: component.widthPx,
          heightPx: component.heightPx,
          openingMicrons: (component.widthPx + component.heightPx) / 2 * scaleMicronsPerPixel
        };
      }),
      openingsBright: best.openingsBright,
      confidence: clamp(Math.min(1, best.components.length / 30) * (1 - Math.min(0.5, best.stats.standardDeviation / best.stats.mean)), 0, 1)
    };
  }

  // Automatic inspection assumes the photo contains the mill's 1 mm ruler.
  // It reports low confidence rather than inventing a material or a Sefar code.
  function autoInspect(imageData) {
    const ruler = bestRulerPeriod(imageData);
    if (!ruler || ruler.confidence < 0.12) {
      return { ok: false, reason: "لم يتم التعرف على تدريج 1 مم في أعلى الصورة. صوّر المنخل مع المسطرة الظاهرة." };
    }
    const mesh = bestMeshPeriod(imageData, ruler.periodPx);
    if (!mesh || mesh.confidence < 0.12) {
      return { ok: false, reason: "تمت قراءة المسطرة، لكن لم يتم التعرف على نسيج منتظم. استخدم صورة أوضح ومباشرة." };
    }
    const micronsPerPixel = 1000 / ruler.periodPx;
    const pitchMicrons = mesh.periodPx * micronsPerPixel;
    const vertical = verticalMeshPeriod(imageData, ruler.periodPx);
    const perspectiveError = vertical && vertical.periodPx > 0 ? Math.abs(vertical.periodPx - mesh.periodPx) / ((vertical.periodPx + mesh.periodPx) / 2) : 0.25;
    const opening = openingEstimate(imageData, micronsPerPixel, mesh.periodPx);
    const geometryConfidence = clamp(1 - perspectiveError * 2.2, 0, 1);
    const confidence = clamp((ruler.confidence * 0.42 + mesh.confidence * 0.38 + geometryConfidence * 0.20) * (opening ? 0.75 + opening.confidence * 0.25 : 0.7), 0, 1);
    const warnings = [];
    if (perspectiveError > 0.10) warnings.push("الصورة مائلة أو القماش غير مستوٍ؛ استخدمها كقراءة إرشادية فقط.");
    if (!opening) warnings.push("تم احتساب عدد الخيوط من التكرار، لكن فتحة الشبك لم تُقَس تلقائيًا بثقة.");
    if (confidence < 0.55) warnings.push("الثقة منخفضة؛ أعد التصوير بإضاءة خلفية وتأكد أن المسطرة كاملة وواضحة.");
    return {
      ok: true,
      rulerPeriodPx: ruler.periodPx,
      meshPitchPx: mesh.periodPx,
      micronsPerPixel: micronsPerPixel,
      pitchMicrons: pitchMicrons,
      threadsPerInch: 25400 / pitchMicrons,
      threadsPerCm: 10000 / pitchMicrons,
      openingMicrons: opening ? opening.openingMicrons : null,
      yarnMicrons: opening ? Math.max(0, pitchMicrons - opening.openingMicrons) : null,
      openingRatio: opening ? opening.openingRatio : null,
      openingPolarityBright: opening ? opening.openingsBright : null,
      components: opening ? opening.components : [],
      perspectiveError: perspectiveError,
      confidence: confidence,
      warnings: warnings
    };
  }

  return {
    analyze: analyze,
    autoInspect: autoInspect,
    otsuThreshold: otsuThreshold,
    estimatePeriod: estimatePeriod,
    cropImageData: cropImageData
  };
});
