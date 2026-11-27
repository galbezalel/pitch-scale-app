// Shared Web Audio Pitch Scaler Engine
// Reused by BOTH the iOS WKWebView Browser Player AND the iOS Safari Web Extension.
(function () {
  if (window.__PitchScalerEngine) return;

  // 1. Pre-load HLS guard: nudge YouTube / YouTube Music on iOS WebKit to prefer
  //    MSE/MP4 over native .m3u8 HLS so AudioContext.createMediaElementSource works cleanly.
  try {
    const origCanPlayType = HTMLMediaElement.prototype.canPlayType;
    HTMLMediaElement.prototype.canPlayType = function (type) {
      if (typeof type === 'string' && /mpegurl/i.test(type)) {
        return '';
      }
      return origCanPlayType.call(this, type);
    };
  } catch (_) {}

  let audioCtx = null;
  let workletNode = null;
  let workletReady = false;
  let workletLoading = false;
  const hookedElements = new WeakSet();

  const state = {
    enabled: false,
    semitones: 0,
    amplitude: 0,
    attachedCount: 0
  };

  function postToHost(payload) {
    try {
      if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.pitchScalerBridge) {
        window.webkit.messageHandlers.pitchScalerBridge.postMessage(payload);
      }
    } catch (_) {}
    try {
      window.postMessage({ source: 'pitch-scaler-engine', ...payload }, '*');
    } catch (_) {}
  }

  async function ensureAudioGraph() {
    if (!audioCtx) {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return false;
      audioCtx = new Ctx();
    }
    if (audioCtx.state === 'suspended') {
      try {
        await audioCtx.resume();
      } catch (_) {}
    }

    if (workletReady && workletNode) return true;
    if (workletLoading) return false;
    workletLoading = true;

    try {
      if (audioCtx.audioWorklet && typeof window.__PITCH_SCALER_WORKLET_SRC === 'string') {
        const blob = new Blob([window.__PITCH_SCALER_WORKLET_SRC], { type: 'application/javascript' });
        const url = URL.createObjectURL(blob);
        await audioCtx.audioWorklet.addModule(url);
        URL.revokeObjectURL(url);

        workletNode = new AudioWorkletNode(audioCtx, 'pitch-scaler-processor', {
          numberOfInputs: 1,
          numberOfOutputs: 1,
          outputChannelCount: [2]
        });
        workletNode.port.onmessage = (e) => {
          if (e.data && typeof e.data.amplitude === 'number') {
            state.amplitude = e.data.amplitude;
            postToHost({ type: 'amplitude', amplitude: e.data.amplitude });
          }
        };
        workletNode.connect(audioCtx.destination);
        syncWorkletParams();
        workletReady = true;
      }
    } catch (err) {
      console.warn('[PitchScaler] AudioWorklet init failed:', err);
    } finally {
      workletLoading = false;
    }

    return workletReady;
  }

  function syncWorkletParams() {
    if (!workletNode) return;
    try {
      const param = workletNode.parameters.get('semitones');
      if (param) {
        param.value = state.enabled ? state.semitones : 0;
      }
      workletNode.port.postMessage({
        enabled: state.enabled,
        semitones: state.semitones
      });
    } catch (_) {}
  }

  async function hookElement(el) {
    if (!el || hookedElements.has(el)) return;
    const ready = await ensureAudioGraph();
    if (!ready || !audioCtx || !workletNode) return;
    if (hookedElements.has(el)) return;

    try {
      el.crossOrigin = el.crossOrigin || 'anonymous';
      const src = audioCtx.createMediaElementSource(el);
      src.connect(workletNode);
      hookedElements.add(el);
      state.attachedCount++;
      postToHost({ type: 'attached', count: state.attachedCount });
    } catch (err) {
      console.warn('[PitchScaler] createMediaElementSource failed:', err);
    }
  }

  function scanMediaElements() {
    const mediaEls = document.querySelectorAll('video, audio');
    mediaEls.forEach((el) => {
      if (!el.dataset.pitchScalerWatched) {
        el.dataset.pitchScalerWatched = '1';
        el.addEventListener('play', () => {
          if (state.enabled) {
            hookElement(el);
          }
          if (audioCtx && audioCtx.state === 'suspended') {
            audioCtx.resume().catch(() => {});
          }
        });
      }
      if (state.enabled && !el.paused) {
        hookElement(el);
      }
    });
  }

  // Keep watching DOM for SPA track changes (YouTube / YouTube Music)
  const observer = new MutationObserver(() => {
    if (state.enabled) {
      scanMediaElements();
    }
  });

  function startObserver() {
    if (document.body) {
      observer.observe(document.body, { childList: true, subtree: true });
      scanMediaElements();
    } else {
      document.addEventListener('DOMContentLoaded', () => {
        if (document.body) {
          observer.observe(document.body, { childList: true, subtree: true });
        }
        scanMediaElements();
      });
    }
  }
  startObserver();

  // Unlock AudioContext on first touch/click gesture on iOS WebKit
  ['touchstart', 'click'].forEach((evt) => {
    window.addEventListener(
      evt,
      () => {
        if (audioCtx && audioCtx.state === 'suspended') {
          audioCtx.resume().catch(() => {});
        }
        if (state.enabled) {
          scanMediaElements();
        }
      },
      { passive: true }
    );
  });

  window.__PitchScalerEngine = {
    setEnabled(enabled) {
      state.enabled = Boolean(enabled);
      if (state.enabled) {
        ensureAudioGraph().then(() => {
          scanMediaElements();
          syncWorkletParams();
        });
      } else {
        syncWorkletParams();
        state.amplitude = 0;
        postToHost({ type: 'amplitude', amplitude: 0 });
      }
      postToHost({ type: 'state', ...state });
    },
    setSemitones(semitones) {
      state.semitones = Math.max(-12, Math.min(12, Number(semitones) || 0));
      syncWorkletParams();
      postToHost({ type: 'state', ...state });
    },
    attachAll() {
      scanMediaElements();
    },
    getState() {
      return { ...state };
    }
  };
})();
