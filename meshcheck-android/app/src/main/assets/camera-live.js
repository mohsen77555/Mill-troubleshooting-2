(function () {
  "use strict";

  const video = document.getElementById("cameraPreview");
  const placeholder = document.getElementById("cameraPlaceholder");
  const startButton = document.getElementById("startCamera");
  const captureButton = document.getElementById("captureCamera");
  const stopButton = document.getElementById("stopCamera");
  const status = document.getElementById("cameraStatus");
  const picker = document.getElementById("imagePicker");
  const imageInfo = document.getElementById("imageInfo");
  const autoAnalyzeButton = document.getElementById("autoAnalyze");

  let stream = null;

  startButton.addEventListener("click", startCamera);
  captureButton.addEventListener("click", captureFrame);
  stopButton.addEventListener("click", stopCamera);
  window.addEventListener("pagehide", stopCamera);

  async function startCamera() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      status.textContent = "هذا الجهاز لا يتيح الكاميرا المباشرة داخل التطبيق. استخدم اختيار صورة بدلًا منها.";
      return;
    }

    stopCamera();
    startButton.disabled = true;
    status.textContent = "جارٍ فتح الكاميرا الخلفية...";

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
      status.textContent = "الكاميرا تعمل. اجعل تدريج 1 مم ظاهرًا، ثبّت الجوال ثم اضغط «التقاط وتحليل».";
    } catch (error) {
      stream = null;
      placeholder.hidden = false;
      captureButton.disabled = true;
      stopButton.disabled = true;
      if (error && (error.name === "NotAllowedError" || error.name === "SecurityError")) {
        status.textContent = "لم يتم السماح بالكاميرا. افتح أذونات التطبيق واسمح باستخدام Camera.";
      } else if (error && error.name === "NotFoundError") {
        status.textContent = "لم يتم العثور على كاميرا في الجهاز.";
      } else {
        status.textContent = "تعذر فتح الكاميرا: " + ((error && error.message) || "خطأ غير معروف") + ". يمكنك اختيار صورة بدلًا منها.";
      }
    } finally {
      startButton.disabled = false;
    }
  }

  function stopCamera() {
    if (stream) {
      stream.getTracks().forEach(function (track) { track.stop(); });
      stream = null;
    }
    video.srcObject = null;
    placeholder.hidden = false;
    captureButton.disabled = true;
    stopButton.disabled = true;
  }

  function captureFrame() {
    if (!stream || video.readyState < 2 || !video.videoWidth || !video.videoHeight) {
      status.textContent = "الكاميرا لم تصبح جاهزة بعد.";
      return;
    }

    captureButton.disabled = true;
    status.textContent = "جارٍ التقاط الإطار...";

    const maximumDimension = 2200;
    const scale = Math.min(1, maximumDimension / Math.max(video.videoWidth, video.videoHeight));
    const captureCanvas = document.createElement("canvas");
    captureCanvas.width = Math.max(1, Math.round(video.videoWidth * scale));
    captureCanvas.height = Math.max(1, Math.round(video.videoHeight * scale));
    const captureContext = captureCanvas.getContext("2d");
    captureContext.drawImage(video, 0, 0, captureCanvas.width, captureCanvas.height);

    captureCanvas.toBlob(function (blob) {
      if (!blob) {
        status.textContent = "تعذر إنشاء صورة من الكاميرا.";
        captureButton.disabled = false;
        return;
      }

      try {
        const file = new File([blob], "meshcheck-camera-" + Date.now() + ".jpg", { type: "image/jpeg" });
        const transfer = new DataTransfer();
        transfer.items.add(file);

        const observer = new MutationObserver(function () {
          if (imageInfo.textContent.indexOf("الصورة:") === 0) {
            observer.disconnect();
            status.textContent = "تم التقاط الصورة. بدأ التحليل التلقائي...";
            window.setTimeout(function () {
              if (!autoAnalyzeButton.disabled) autoAnalyzeButton.click();
            }, 80);
          }
        });
        observer.observe(imageInfo, { childList: true, characterData: true, subtree: true });
        window.setTimeout(function () { observer.disconnect(); }, 5000);

        picker.files = transfer.files;
        picker.dispatchEvent(new Event("change", { bubbles: true }));
        status.textContent = "تم التقاط إطار بدقة " + captureCanvas.width + " × " + captureCanvas.height + ".";
      } catch (error) {
        status.textContent = "تم التقاط الإطار، لكن تعذر تمريره إلى التحليل: " + error.message;
      } finally {
        captureButton.disabled = false;
      }
    }, "image/jpeg", 0.96);
  }
})();
