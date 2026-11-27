// Thin Safari Extension bridge to the shared pitch-engine.js
const api = typeof browser !== 'undefined' ? browser : chrome;

api.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  const engine = window.__PitchScalerEngine;
  if (!engine) {
    sendResponse({ enabled: false, semitones: 0 });
    return false;
  }

  if (message.type === 'SET_ENABLED') {
    engine.setEnabled(message.enabled);
  } else if (message.type === 'SET_SEMITONES') {
    engine.setSemitones(message.semitones);
  }

  sendResponse(engine.getState());
  return false;
});
