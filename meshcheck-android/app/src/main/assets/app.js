(function () {
  "use strict";

  const MAX_IMAGE_DIMENSION = 1600;
  const canvas = document.getElementById("imageCanvas");
  const context = canvas.getContext("2d", { willReadFrequently: true });
  const picker = document.getElementById("imagePicker");
  const canvasWrap = document.getElementById("canvasWrap");
  const placeholder = document.getElementById("canvasPlaceholder");
  const imageInfo = document.getElementById("imageInfo");
  const materialFilter = document.getElementById("materialFilter");
  const autoAnalyzeButton = document.getElementById("autoAnalyze");
  const autoStatus = document.getElementById("autoStatus");
  const statusBadge = document.getElementById("statusBadge");
  const qualityLabel = document.getElementById("qualityLabel");
  const codeMetric = document.getElementById("codeMetric");
  const materialMetric = document.getElementById("materialMetric");
  const openingMetric = document.getElementById("openingMetric");
  const yarnMetric = document.getElementById("yarnMetric");
  const pitchMetric = document.getElementById("pitchMetric");
  const threadMetric = document.getElementById("threadMetric");
  const uncertaintyMetric = document.getElementById("uncertaintyMetric");
  const measurementQualityMetric = document.getElementById("measurementQualityMetric");
  const confidenceMetric = document.getElementById("confidenceMetric");
  const resultSummary = document.getElementById("resultSummary");
  const warnings = document.getElementById("warnings");
  const candidateBox = document.getElementById("candidateBox");
  const candidateList = document.getElementById("candidateList");
  const copyReport = document.getElementById("copyReport");
  const exportCsv = document.getElementById("exportCsv");
  const referenceMicrons = document.getElementById("referenceMicrons");
  const micronsPerPixel = document.getElementById("micronsPerPixel");
  const startCalibration = document.getElementById("startCalibration");
  const applyCalibration = document.getElementById("applyCalibration");
  const minArea = document.getElementById("minArea");
  const openingsBright = document.getElementById("openingsBright");
  const manualAnalyzeButton = document.getElementById("manualAnalyze");
  const calibrationStatus = document.getElementById("calibrationStatus");

  let sourceImageData = null;
  let sourceName = "";
  let calibrationMode = false;
  let calibrationPoints = [];
  let automaticResult = null;
  let manualResult = null;
  let currentCandidates = [];

  const storedScale = Number(localStorage.getItem("meshcheck.micronsPerPixel"));
  if (storedScale > 0) {
    micronsPerPixel.value = formatNumber(storedScale, 5);
    calibrationStatus.textContent = "تمت استعادة قيمة معايرة يدوية محفوظة. استخدمها فقط للمسار الاحتياطي.";
  }

  picker.addEventListener("change", loadImage);
  autoAnalyzeButton.addEventListener("click", runAutoAnalysis);
  materialFilter.addEventListener("change", rerankCurrentResult);
  startCalibration.addEventListener("click", beginCalibration);
  applyCalibration.addEventListener("click", finishCalibration);
  manualAnalyzeButton.addEventListener("click", runManualAnalysis);
  canvas.addEventListener("pointerup", placeCalibrationPoint);
  copyReport.addEventListener("click", copyTextReport);
  exportCsv.addEventListener("click", saveCsv);

  function formatNumber(value, maximumFractionDigits) {
    if (!Number.isFinite(Number(value))) return "—";
    return new Intl.NumberFormat("en-US", { maximumFractionDigits, minimumFractionDigits: 0 }).format(value);
  }

  function setStatus(text) {
    statusBadge.textContent = text;
  }

  function loadImage(event) {
    const file = event.target.files && event.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = function (loadEvent) {
      const image = new Image();
      image.onload = function () {
        const scale = Math.min(1, MAX_IMAGE_DIMENSION / Math.max(image.naturalWidth, image.naturalHeight));
        canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
        canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
        context.clearRect(0, 0, canvas.width, canvas.height);
        context.drawImage(image, 0, 0, canvas.width, canvas.height);
        sourceImageData = context.getImageData(0, 0, canvas.width, canvas.height);
        sourceName = file.name;
        calibrationPoints = [];
        calibrationMode = false;
        automaticResult = null;
        manualResult = null;
        currentCandidates = [];
        canvasWrap.classList.remove("empty");
        placeholder.hidden = true;
        imageInfo.textContent = "الصورة: " + sourceName + " - " + canvas.width + " × " + canvas.height + " بكسل.";
        autoStatus.textContent = "جاهزة. اضغط «تحليل الصورة ومطابقة Sefar».";
        calibrationStatus.textContent = "المعايرة اليدوية احتياطية فقط للصور خارج مسار عدسة 20×20 mm.";
        setStatus("تم تحميل الصورة");
        resetResultCards();
        render();
      };
      image.onerror = function () { imageInfo.textContent = "تعذر قراءة هذه الصورة."; };
      image.src = loadEvent.target.result;
    };
    reader.readAsDataURL(file);
  }

  function runAutoAnalysis() {
    if (!sourceImageData) {
      autoStatus.textContent = "اختر صورة أولًا.";
      setStatus("اختر صورة");
      return;
    }
    autoAnalyzeButton.disabled = true;
    autoAnalyzeButton.textContent = "جارٍ التحليل...";
    setTimeout(function () {
      try {
        automaticResult = MeshAnalyzer.autoInspect(sourceImageData);
        manualResult = null;
        if (!automaticResult.ok) {
          currentCandidates = [];
          resetResultCards();
          resultSummary.textContent = automaticResult.reason;
          autoStatus.textContent = automaticResult.reason;
          setStatus("تحتاج صورة أوضح");
          render();
          return;
        }
        assignCandidates();
        render();
        showAutomaticResult();
        setStatus("اكتمل التحليل");
      } catch (error) {
        automaticResult = null;
        currentCandidates = [];
        resetResultCards();
        resultSummary.textContent = "تعذر التحليل: " + error.message;
        autoStatus.textContent = "تعذر التحليل. جرّب صورة أوضح.";
        setStatus("تعذر التحليل");
      } finally {
        autoAnalyzeButton.disabled = false;
        autoAnalyzeButton.textContent = "تحليل الصورة ومطابقة Sefar";
      }
    }, 30);
  }

  function assignCandidates() {
    if (!automaticResult || !automaticResult.ok) {
      currentCandidates = [];
      return;
    }
    currentCandidates = SefarCatalog.matchCandidates({
      pitchMicrons: automaticResult.pitchMicrons,
      openingMicrons: automaticResult.openingMicrons,
      yarnMicrons: automaticResult.yarnMicrons
    }, materialFilter.value).slice(0, 3);
  }

  function rerankCurrentResult() {
    if (!automaticResult || !automaticResult.ok) return;
    assignCandidates();
    showAutomaticResult();
    render();
  }

  function showAutomaticResult() {
    const best = currentCandidates[0];
    if (!best) {
      resetResultCards();
      resultSummary.textContent = "تم القياس، لكن لا توجد مطابقة ضمن جدول القماش المحدد.";
      return;
    }

    const detectedOpening = Number(automaticResult.openingMicrons) || 0;
    const detectedYarn = Number(automaticResult.yarnMicrons) || 0;
    const uncertainty = Number(automaticResult.uncertaintyMicrons) || 0;
    const measurementQuality = Number(automaticResult.measurementQuality) || 0;
    const confidence = Math.min(99, Math.round(automaticResult.confidence * 100 * 0.72 + best.confidence * 0.28));
    const isAutoMaterial = materialFilter.value === "Auto";

    codeMetric.textContent = best.code;
    materialMetric.textContent = SefarCatalog.materialArabic(best.material) + " - مطابقة متعددة المعايير " + best.confidence + "%";
    openingMetric.textContent = detectedOpening > 0
      ? formatNumber(detectedOpening, 0) + " µm"
      : formatNumber(best.openingMicrons, 0) + " µm (catalog)";
    yarnMetric.textContent = detectedYarn > 0 ? formatNumber(detectedYarn, 0) + " µm" : "—";
    pitchMetric.textContent = formatNumber(automaticResult.pitchMicrons, 1) + " µm";
    threadMetric.textContent = formatNumber(automaticResult.threadsPerCm, 2) + "/cm  |  " + formatNumber(automaticResult.threadsPerInch, 2) + "/in";
    uncertaintyMetric.textContent = uncertainty > 0 ? "±" + formatNumber(uncertainty, 0) + " µm" : "—";
    measurementQualityMetric.textContent = measurementQuality > 0 ? Math.round(measurementQuality * 100) + "%" : "—";
    confidenceMetric.textContent = confidence + "%";

    const qualityScore = measurementQuality > 0 ? measurementQuality * 100 : confidence;
    const qualityClass = qualityScore >= 78 ? "good" : qualityScore >= 58 ? "warn" : "neutral";
    qualityLabel.textContent = qualityScore >= 78 ? "High Accuracy" : qualityScore >= 58 ? "جودة مقبولة" : "تحقق مطلوب";
    qualityLabel.className = "quality " + qualityClass;

    const measuredText = detectedOpening > 0
      ? "Opening " + formatNumber(detectedOpening, 0) + " µm، Yarn " + formatNumber(detectedYarn, 0) + " µm، Pitch " + formatNumber(automaticResult.pitchMicrons, 1) + " µm"
      : "Pitch " + formatNumber(automaticResult.pitchMicrons, 1) + " µm";
    const uncertaintyText = uncertainty > 0 ? "، uncertainty ±" + formatNumber(uncertainty, 0) + " µm" : "";
    resultSummary.textContent = measuredText + uncertaintyText + ". أقرب مواصفة من ملف Sefar هي " + best.code + " بفتحة catalog " + best.openingMicrons + " µm.";

    const warningsToShow = automaticResult.warnings.slice();
    if (measurementQuality > 0 && measurementQuality < 0.65) warningsToShow.push("Quality أقل من 65%؛ أعد التصوير للحصول على نتيجة أقوى قبل الاعتماد على الكود.");
    if (isAutoMaterial) warningsToShow.push("اختر المادة PA أو PET أو Metal إذا كانت معروفة؛ الصورة وحدها لا تثبت نوع البوليمر دائمًا.");
    renderWarnings(warningsToShow);
    renderCandidates();
    copyReport.disabled = false;
    exportCsv.disabled = false;
    autoStatus.textContent = "تم القياس عالي الدقة والمطابقة باستخدام n/cm + Opening + Yarn عندما تكون متاحة.";
  }

  function renderWarnings(items) {
    warnings.replaceChildren();
    if (!items.length) {
      warnings.hidden = true;
      return;
    }
    items.forEach(function (item) {
      const row = document.createElement("li");
      row.textContent = item;
      warnings.appendChild(row);
    });
    warnings.hidden = false;
  }

  function renderCandidates() {
    candidateList.replaceChildren();
    if (!currentCandidates.length) {
      candidateBox.hidden = true;
      return;
    }
    currentCandidates.forEach(function (candidate, index) {
      const item = document.createElement("div");
      item.className = "candidate" + (index === 0 ? " selected" : "");
      const yarnText = typeof candidate.yarnMicrons === "number" ? candidate.yarnMicrons + " µm" : String(candidate.yarnMicrons || "—") + " µm";
      item.innerHTML = "<strong>" + escapeHtml(candidate.code) + "</strong><span>" + escapeHtml(SefarCatalog.materialArabic(candidate.material)) + "</span><small>Catalog: فتحة " + candidate.openingMicrons + " µm - خيط " + escapeHtml(yarnText) + " - " + candidate.confidence + "%</small>";
      candidateList.appendChild(item);
    });
    candidateBox.hidden = false;
  }

  function beginCalibration() {
    if (!sourceImageData) {
      calibrationStatus.textContent = "اختر صورة أولًا.";
      return;
    }
    calibrationMode = true;
    calibrationPoints = [];
    calibrationStatus.textContent = "اضغط نقطتين تمثلان طولًا معروفًا على مرجع القياس.";
    setStatus("اختر نقطتين");
    render();
  }

  function placeCalibrationPoint(event) {
    if (!calibrationMode || !sourceImageData || calibrationPoints.length >= 2) return;
    const bounds = canvas.getBoundingClientRect();
    const x = clamp(Math.round((event.clientX - bounds.left) * canvas.width / bounds.width), 0, canvas.width - 1);
    const y = clamp(Math.round((event.clientY - bounds.top) * canvas.height / bounds.height), 0, canvas.height - 1);
    calibrationPoints.push({ x, y });
    calibrationStatus.textContent = calibrationPoints.length === 1 ? "تم اختيار النقطة الأولى؛ اختر النقطة الثانية." : "تم اختيار النقطتين؛ اضغط «اعتماد المعايرة».";
    render();
  }

  function finishCalibration() {
    if (calibrationPoints.length !== 2) {
      calibrationStatus.textContent = "اختر نقطتين على الصورة أولًا.";
      return;
    }
    const knownMicrons = Number(referenceMicrons.value);
    const pixelDistance = Math.hypot(calibrationPoints[1].x - calibrationPoints[0].x, calibrationPoints[1].y - calibrationPoints[0].y);
    if (!(knownMicrons > 0) || !(pixelDistance > 0)) {
      calibrationStatus.textContent = "أدخل طولًا مرجعيًا أكبر من صفر واختر نقطتين مختلفتين.";
      return;
    }
    const scale = knownMicrons / pixelDistance;
    micronsPerPixel.value = formatNumber(scale, 5);
    localStorage.setItem("meshcheck.micronsPerPixel", String(scale));
    calibrationMode = false;
    calibrationStatus.textContent = "تمت المعايرة اليدوية: " + formatNumber(scale, 5) + " µm/بكسل.";
    setStatus("تمت المعايرة");
    render();
  }

  function runManualAnalysis() {
    if (!sourceImageData) {
      calibrationStatus.textContent = "اختر صورة أولًا.";
      return;
    }
    const scale = Number(micronsPerPixel.value);
    if (!(scale > 0)) {
      calibrationStatus.textContent = "عاير الصورة أو أدخل قيمة µm/بكسل أولًا.";
      return;
    }
    try {
      manualResult = MeshAnalyzer.analyze(sourceImageData, {
        micronsPerPixel: scale,
        minAreaPx: Number(minArea.value),
        openingsBright: openingsBright.value === "bright"
      });
      automaticResult = null;
      currentCandidates = [];
      render();
      showManualResult();
      setStatus(manualResult.stats.count ? "اكتمل القياس اليدوي" : "لم تُكتشف فتحات");
    } catch (error) {
      resultSummary.textContent = "تعذر القياس اليدوي: " + error.message;
      setStatus("تعذر القياس");
    }
  }

  function showManualResult() {
    const stats = manualResult.stats;
    if (!stats.count) {
      resetResultCards();
      resultSummary.textContent = "لم تُكتشف فتحات مكتملة. جرّب إضاءة مختلفة أو بدّل خيار لون الفتحات.";
      return;
    }
    codeMetric.textContent = "قياس يدوي";
    materialMetric.textContent = "لم تتم مطابقة كود Sefar تلقائيًا في الوضع اليدوي.";
    openingMetric.textContent = formatNumber(stats.mean, 0) + " µm";
    yarnMetric.textContent = "—";
    pitchMetric.textContent = "—";
    threadMetric.textContent = formatNumber(stats.count, 0) + " فتحة مكتشفة";
    uncertaintyMetric.textContent = "—";
    measurementQualityMetric.textContent = "—";
    confidenceMetric.textContent = "تحقق مرجعي";
    qualityLabel.textContent = "قياس يدوي";
    qualityLabel.className = "quality neutral";
    resultSummary.textContent = "متوسط الفتحة " + formatNumber(stats.mean, 1) + " µm؛ المجال " + formatNumber(stats.min, 1) + " إلى " + formatNumber(stats.max, 1) + " µm.";
    renderWarnings(["القياس اليدوي احتياطي ولا يحدد مادة القماش أو كود Sefar وحده."]);
    candidateBox.hidden = true;
    copyReport.disabled = false;
    exportCsv.disabled = false;
  }

  function render() {
    if (!sourceImageData) return;
    context.putImageData(sourceImageData, 0, 0);
    const lineWidth = Math.max(1, Math.round(canvas.width / 900));
    const components = automaticResult && automaticResult.ok ? automaticResult.components : manualResult ? manualResult.components : [];
    if (components) {
      components.forEach(function (component) {
        context.strokeStyle = component.flagged ? "#d94343" : "#14866d";
        context.lineWidth = lineWidth;
        context.strokeRect(component.x, component.y, component.widthPx, component.heightPx);
      });
    }
    if (calibrationPoints.length) {
      context.fillStyle = "#3178c6";
      context.strokeStyle = "#3178c6";
      context.lineWidth = lineWidth * 2;
      calibrationPoints.forEach(function (point) {
        context.beginPath();
        context.arc(point.x, point.y, lineWidth * 3, 0, Math.PI * 2);
        context.fill();
      });
      if (calibrationPoints.length === 2) {
        context.beginPath();
        context.moveTo(calibrationPoints[0].x, calibrationPoints[0].y);
        context.lineTo(calibrationPoints[1].x, calibrationPoints[1].y);
        context.stroke();
      }
    }
  }

  function resetResultCards() {
    codeMetric.textContent = "—";
    materialMetric.textContent = "—";
    openingMetric.textContent = "—";
    yarnMetric.textContent = "—";
    pitchMetric.textContent = "—";
    threadMetric.textContent = "—";
    uncertaintyMetric.textContent = "—";
    measurementQualityMetric.textContent = "—";
    confidenceMetric.textContent = "—";
    qualityLabel.textContent = "لا توجد نتيجة";
    qualityLabel.className = "quality neutral";
    resultSummary.textContent = "بعد التحليل ستظهر هنا نتيجة القياس والمطابقة.";
    warnings.hidden = true;
    warnings.replaceChildren();
    candidateBox.hidden = true;
    candidateList.replaceChildren();
    copyReport.disabled = true;
    exportCsv.disabled = true;
  }

  function buildReport() {
    const lines = ["MeshCheck Sefar v0.18 - High Accuracy Report", "الصورة: " + sourceName];
    if (automaticResult && automaticResult.ok) {
      const best = currentCandidates[0];
      lines.push("Pitch: " + formatNumber(automaticResult.pitchMicrons, 2) + " µm");
      lines.push("Threads: " + formatNumber(automaticResult.threadsPerCm, 3) + "/cm | " + formatNumber(automaticResult.threadsPerInch, 3) + "/inch");
      lines.push("Opening measured: " + (automaticResult.openingMicrons ? formatNumber(automaticResult.openingMicrons, 1) + " µm" : "N/A"));
      lines.push("Yarn measured: " + (automaticResult.yarnMicrons ? formatNumber(automaticResult.yarnMicrons, 1) + " µm" : "N/A"));
      lines.push("Uncertainty: " + (automaticResult.uncertaintyMicrons ? "±" + formatNumber(automaticResult.uncertaintyMicrons, 1) + " µm" : "N/A"));
      lines.push("Measurement quality: " + (automaticResult.measurementQuality ? Math.round(automaticResult.measurementQuality * 100) + "%" : "N/A"));
      if (best) {
        lines.push("Closest Sefar: " + best.code + " (" + SefarCatalog.materialArabic(best.material) + ")");
        lines.push("Sefar catalog: opening " + best.openingMicrons + " µm, yarn/wire " + best.yarnMicrons + " µm, " + best.threadsPerInch + "/inch");
      }
      lines.push("Image confidence: " + Math.round(automaticResult.confidence * 100) + "%");
      automaticResult.warnings.forEach(function (warning) { lines.push("Warning: " + warning); });
    } else if (manualResult) {
      const stats = manualResult.stats;
      lines.push("Manual calibration: " + formatNumber(Number(micronsPerPixel.value), 5) + " µm/px");
      lines.push("Openings: " + stats.count);
      lines.push("Mean opening: " + formatNumber(stats.mean, 1) + " µm");
      lines.push("Range: " + formatNumber(stats.min, 1) + " to " + formatNumber(stats.max, 1) + " µm");
    }
    lines.push("Note: validate against known reference samples before production purchasing decisions.");
    return lines.join("\n");
  }

  function copyTextReport() {
    if (!automaticResult && !manualResult) return;
    const report = buildReport();
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(report).then(function () { resultSummary.textContent = "تم نسخ التقرير."; }).catch(copyViaTextArea);
    } else {
      copyViaTextArea();
    }
  }

  function copyViaTextArea() {
    const area = document.createElement("textarea");
    area.value = buildReport();
    document.body.appendChild(area);
    area.select();
    document.execCommand("copy");
    area.remove();
    resultSummary.textContent = "تم نسخ التقرير.";
  }

  function saveCsv() {
    if (!automaticResult && !manualResult) return;
    const header = ["source", "mode", "sefar_code", "material", "opening_um", "yarn_um", "pitch_um", "uncertainty_um", "threads_per_cm", "threads_per_in", "measurement_quality_percent", "confidence_percent"];
    const best = currentCandidates[0];
    const data = automaticResult && automaticResult.ok ? [
      sourceName, "auto", best ? best.code : "", best ? best.material : "",
      automaticResult.openingMicrons || "", automaticResult.yarnMicrons || "", automaticResult.pitchMicrons || "",
      automaticResult.uncertaintyMicrons || "", automaticResult.threadsPerCm || "", automaticResult.threadsPerInch || "",
      automaticResult.measurementQuality ? Math.round(automaticResult.measurementQuality * 100) : "",
      Math.round(automaticResult.confidence * 100)
    ] : [sourceName, "manual", "", "", manualResult.stats.mean || "", "", "", "", "", "", "", ""];
    const csv = "\uFEFF" + header.join(",") + "\n" + data.map(csvValue).join(",") + "\n";
    const fileName = "meshcheck_sefar_" + new Date().toISOString().slice(0, 10) + ".csv";
    if (window.MeshExport && typeof window.MeshExport.saveCsv === "function") {
      window.MeshExport.saveCsv(fileName, csv);
    } else {
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
      const link = document.createElement("a");
      link.href = URL.createObjectURL(blob);
      link.download = fileName;
      link.click();
      URL.revokeObjectURL(link.href);
    }
  }

  function csvValue(value) {
    const text = String(value == null ? "" : value);
    return /[",\n]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text;
  }

  function clamp(value, minimum, maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, function (character) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;" }[character];
    });
  }
})();
