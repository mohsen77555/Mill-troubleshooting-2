(function () {
  "use strict";

  const ANALYSIS_INTERVAL_MS = 650;
  const LIVE_MAX_DIMENSION = 1000;
  const video = document.getElementById("cameraPreview");
  const placeholder = document.getElementById("cameraPlaceholder");
  const startButton = document.getElementById("startCamera");
  const captureButton = document.getElementById("captureCamera");
  const stopButton = document.getElementById("stopCamera");
  const status = document.getElementById("cameraStatus");
  const picker = document.getElementById("imagePicker");
  const imageInfo = document.getElementById("imageInfo");
  const autoAnalyzeButton = document.getElementById("autoAnalyze");
  const materialFilter = document.getElementById("materialFilter");
  const liveOverlay = document.getElementById("liveOverlay");
  const liveState = document.getElementById("liveState");
  const liveCode = document.getElementById("liveCode");
  const liveConfidence = document.getElementById("liveConfidence");
  const liveOpening = document.getElementById("liveOpening");
  const liveMesh = document.getElementById("liveMesh");
  const livePitch = document.getElementById("livePitch");
  const liveHint = document.getElementById("liveHint");

  const analysisCanvas = document.createElement("canvas");
  const analysisContext = analysisCanvas.getContext("2d", { willReadFrequently: true });
  const stabilizer = LiveStabilizer.create({ windowSize: 5, minSamples: 4, toleranceRatio: 0.08 });

  let stream = null;
  let liveTimer = null;
  let analysisBusy = false;
  let consecutiveFailures = 0;

  startButton.addEventListener("click", startCamera);
  captureButton.addEventListener("click", captureFrame);
  stopButton.addEventListener("click", stopCamera);
  materialFilter.addEventListener("change", function () {
    stabilizer.reset();
    setLiveWaiting("تم تغيير نوع القماش. جارٍ جمع قراءات جديدة...");
  });
  window.addEventListener("pagehide", stopCamera);

  window.MeshCheckNativeCameraResult = function (dataUrl, fileName) {
    stopCamera();
    try {
      const blob = dataUrlToBlob(dataUrl);
      const file = new File([blob], fileName || ("meshcheck-native-" + Date.now() + ".jpg"), { type: "image/jpeg" });
      const transfer = new DataTransfer();
      transfer.items.add(file);
      picker.files = transfer.files;

      const observer = new MutationObserver(function () {
        if (imageInfo.textContent.indexOf("الصورة:") === 0) {
          observer.disconnect();
          status.textContent = "تم التقاط الصورة بالكاميرا الأصلية. بدأ التحليل تلقائيًا.";
          window.setTimeout(function () {
            if (!autoAnalyzeButton.disabled) autoAnalyzeButton.click();
          }, 100);
        }
      });
      observer.observe(imageInfo, { childList: true, characterData: true, subtree: true });
      window.setTimeout(function () { observer.disconnect(); }, 5000);
      picker.dispatchEvent(new Event("change", { bubbles: true }));
    } catch (error) {
      status.textContent = "تم التقاط الصورة، لكن تعذر تمريرها للتحليل: " + error.message;
    }
  };

  window.MeshCheckNativeCameraCanceled = function () {
    status.textContent = "تم إغلاق الكاميرا بدون التقاط صورة.";
  };

  function dataUrlToBlob(dataUrl) {
    const parts = dataUrl.split(",");
    const mime = (parts[0].match(/data:([^;]+)/) || [null, "image/jpeg"])[1];
    const binary = atob(parts[1]);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
    return new Blob([bytes], { type: mime });
  }

  async function startCamera() {
    if (window.MeshNativeCamera && typeof window.MeshNativeCamera.open === "function") {
      status.textContent = "جارٍ فتح كاميرا Android الأصلية...";
      setLiveWaiting("ستفتح الكاميرا بملء الشاشة مع مسطرة 1 cm وFlash.");
      window.MeshNativeCamera.open();
      return;
    }

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      status.textContent = "هذا الجهاز لا يتيح الكاميرا المباشرة داخل المتصفح. استخدم اختيار صورة بدلًا منها.";
      return;
    }

    stopCamera();
    startButton.disabled = true;
    status.textContent = "جارٍ فتح الكاميرا الخلفية...";
    setLiveWaiting("جارٍ فتح الكاميرا...");

    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
          facingMode: { ideal: "environment" },
          width: { ideal: 3840 },
          height: { ideal: 2160 }
        }
      });
      video.srcObject = stream;
      await video.play();
      placeholder.hidden = true;
      captureButton.disabled = false;
      stopButton.disabled = false;
      status.textContent = "القياس الحي يعمل. ثبّت الجوال واجعل تدريج 1 مم ظاهرًا داخل الصورة.";
      stabilizer.reset();
      consecutiveFailures = 0;
      scheduleLiveAnalysis(180);
    } catch (error) {
      stream = null;
      placeholder.hidden = false;
      captureButton.disabled = true;
      stopButton.disabled = true;
      setLiveError("تعذر فتح الكاميرا");
      status.textContent = "تعذر فتح الكاميرا داخل WebView. استخدم الكاميرا الأصلية أو اختيار صورة.";
    } finally {
      startButton.disabled = false;
    }
  }

  function stopCamera() {
    if (liveTimer) {
      clearTimeout(liveTimer);
      liveTimer = null;
    }
    analysisBusy = false;
    stabilizer.reset();
    if (stream) {
      stream.getTracks().forEach(function (track) { track.stop(); });
      stream = null;
    }
    video.srcObject = null;
    placeholder.hidden = false;
    captureButton.disabled = true;
    stopButton.disabled = true;
    setLiveWaiting("افتح الكاميرا ووجّهها إلى القماش.");
  }

  function scheduleLiveAnalysis(delay) {
    if (liveTimer) clearTimeout(liveTimer);
    if (!stream) return;
    liveTimer = setTimeout(analyzeLiveFrame, delay == null ? ANALYSIS_INTERVAL_MS : delay);
  }

  function analyzeLiveFrame() {
    if (!stream) return;
    if (analysisBusy || video.readyState < 2 || !video.videoWidth || !video.videoHeight) {
      scheduleLiveAnalysis();
      return;
    }

    analysisBusy = true;
    try {
      const scale = Math.min(1, LIVE_MAX_DIMENSION / Math.max(video.videoWidth, video.videoHeight));
      analysisCanvas.width = Math.max(1, Math.round(video.videoWidth * scale));
      analysisCanvas.height = Math.max(1, Math.round(video.videoHeight * scale));
      analysisContext.drawImage(video, 0, 0, analysisCanvas.width, analysisCanvas.height);
      const imageData = analysisContext.getImageData(0, 0, analysisCanvas.width, analysisCanvas.height);
      const inspected = MeshAnalyzer.autoInspect(imageData);

      if (!inspected || !inspected.ok) {
        consecutiveFailures += 1;
        if (consecutiveFailures >= 4) stabilizer.reset();
        setLiveWaiting(inspected && inspected.reason ? inspected.reason : "لم تُقرأ المسطرة/الشبكة بوضوح. ثبّت الجوال وحسّن الإضاءة.");
        return;
      }

      consecutiveFailures = 0;
      const stable = stabilizer.push({
        pitchMicrons: inspected.pitchMicrons,
        openingMicrons: inspected.openingMicrons,
        confidence: inspected.confidence
      });
      renderLiveMeasurement(stable);
    } catch (error) {
      setLiveError("تعذر التحليل الحي: " + error.message);
    } finally {
      analysisBusy = false;
      scheduleLiveAnalysis();
    }
  }

  function renderLiveMeasurement(stable) {
    if (!stable || !(stable.pitchMicrons > 0)) {
      setLiveWaiting("جارٍ البحث عن الشبكة...");
      return;
    }

    const candidates = SefarCatalog.matchCandidates({
      pitchMicrons: stable.pitchMicrons,
      openingMicrons: stable.openingMicrons
    }, materialFilter.value).slice(0, 3);
    const best = candidates[0];
    const threadsPerInch = 25400 / stable.pitchMicrons;
    const opening = stable.openingMicrons || (best && best.openingMicrons) || null;
    const confidence = best
      ? Math.min(99, Math.round((stable.confidence || 0) * 100 * 0.72 + best.confidence * 0.28))
      : Math.min(99, Math.round((stable.confidence || 0) * 100));

    liveCode.textContent = best ? best.code : "لا توجد مطابقة";
    liveOpening.textContent = opening ? Math.round(opening) + " µm" : "—";
    liveMesh.textContent = threadsPerInch.toFixed(1);
    livePitch.textContent = Math.round(stable.pitchMicrons) + " µm";
    liveConfidence.textContent = confidence + "%";

    if (!stable.ready) {
      liveOverlay.className = "live-overlay measuring";
      liveState.textContent = "جمع القراءات " + stable.samples + "/4";
      liveHint.textContent = "أبقِ الهاتف ثابتًا حتى يثبت القياس.";
      return;
    }

    if (stable.stable) {
      liveOverlay.className = "live-overlay stable";
      liveState.textContent = "قراءة مستقرة";
      liveHint.textContent = "يمكنك الضغط على «تثبيت وتحليل نهائي» للحصول على تقرير الصورة عالية الدقة.";
    } else {
      liveOverlay.className = "live-overlay measuring";
      liveState.textContent = "جارٍ التثبيت";
      const spread = stable.pitchSpreadRatio == null ? null : Math.round(stable.pitchSpreadRatio * 100);
      liveHint.textContent = spread == null ? "ثبّت الجوال أكثر." : "تذبذب الـPitch حاليًا " + spread + "% — ثبّت الجوال وحافظ على نفس المسافة.";
    }
  }

  function setLiveWaiting(message) {
    liveOverlay.className = "live-overlay waiting";
    liveState.textContent = "جارٍ القياس";
    liveCode.textContent = "—";
    liveConfidence.textContent = "—";
    liveOpening.textContent = "—";
    liveMesh.textContent = "—";
    livePitch.textContent = "—";
    liveHint.textContent = message;
  }

  function setLiveError(message) {
    liveOverlay.className = "live-overlay error";
    liveState.textContent = "تعذر القياس";
    liveCode.textContent = "—";
    liveConfidence.textContent = "—";
    liveOpening.textContent = "—";
    liveMesh.textContent = "—";
    livePitch.textContent = "—";
    liveHint.textContent = message;
  }

  function captureFrame() {
    if (!stream || video.readyState < 2 || !video.videoWidth || !video.videoHeight) {
      status.textContent = "استخدم زر «فتح الكاميرا» لفتح كاميرا Android الأصلية.";
      return;
    }

    captureButton.disabled = true;
    status.textContent = "جارٍ تثبيت الإطار عالي الدقة...";
    const maximumDimension = 2200;
    const scale = Math.min(1, maximumDimension / Math.max(video.videoWidth, video.videoHeight));
    const captureCanvas = document.createElement("canvas");
    captureCanvas.width = Math.max(1, Math.round(video.videoWidth * scale));
    captureCanvas.height = Math.max(1, Math.round(video.videoHeight * scale));
    const captureContext = captureCanvas.getContext("2d");
    captureContext.drawImage(video, 0, 0, captureCanvas.width, captureCanvas.height);
    captureCanvas.toBlob(function (blob) {
      if (!blob) {
        captureButton.disabled = false;
        return;
      }
      const file = new File([blob], "meshcheck-camera-" + Date.now() + ".jpg", { type: "image/jpeg" });
      const transfer = new DataTransfer();
      transfer.items.add(file);
      picker.files = transfer.files;
      picker.dispatchEvent(new Event("change", { bubbles: true }));
      captureButton.disabled = false;
    }, "image/jpeg", 0.96);
  }
})();
