const api = typeof browser !== 'undefined' ? browser : chrome;

let state = { enabled: false, semitones: 0 };

const statusEl = document.getElementById('status');
const semitonesEl = document.getElementById('semitones');
const minusBtn = document.getElementById('minus');
const plusBtn = document.getElementById('plus');
const toggleBtn = document.getElementById('toggle');

function formatSemitones(st) {
  if (st > 0) return `+${st} st`;
  if (st < 0) return `${st} st`;
  return 'Original Pitch';
}

function render() {
  semitonesEl.textContent = formatSemitones(state.semitones);
  minusBtn.disabled = !state.enabled;
  plusBtn.disabled = !state.enabled;
  toggleBtn.textContent = state.enabled ? 'STOP ENGINE' : 'START ENGINE';
  toggleBtn.classList.toggle('active', state.enabled);
  statusEl.textContent = state.enabled ? 'Active Media Pitch Shifting' : 'Ready on this tab';
}

async function sendToActiveTab(message) {
  const tabs = await api.tabs.query({ active: true, currentWindow: true });
  if (!tabs[0]) return;
  try {
    const resp = await api.tabs.sendMessage(tabs[0].id, message);
    if (resp) {
      state = resp;
      render();
    }
  } catch (_) {
    statusEl.textContent = 'Reload tab to attach Pitch Scaler';
  }
}

toggleBtn.addEventListener('click', () => {
  sendToActiveTab({ type: 'SET_ENABLED', enabled: !state.enabled });
});

minusBtn.addEventListener('click', () => {
  sendToActiveTab({ type: 'SET_SEMITONES', semitones: Math.max(-12, state.semitones - 1) });
});

plusBtn.addEventListener('click', () => {
  sendToActiveTab({ type: 'SET_SEMITONES', semitones: Math.min(12, state.semitones + 1) });
});

sendToActiveTab({ type: 'GET_STATE' });
render();
