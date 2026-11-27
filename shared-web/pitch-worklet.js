// Shared WSOLA / Dual-Tap Overlap-Add Real-Time Pitch Shifter AudioWorkletProcessor
// Used identically by both the iOS WKWebView Browser Player and the iOS Safari Web Extension.

class PitchScalerWorkletProcessor extends AudioWorkletProcessor {
  static get parameterDescriptors() {
    return [
      {
        name: 'semitones',
        defaultValue: 0,
        minValue: -12,
        maxValue: 12,
        automationRate: 'k-rate'
      }
    ];
  }

  constructor() {
    super();
    this.enabled = true;
    this.semitones = 0;
    this.pitchRatio = 1.0;

    // Ring buffer size (power of 2 for fast masking, ~170ms @ 48kHz)
    this.bufSize = 8192;
    this.bufMask = this.bufSize - 1;
    // Hann crossfade grain window (~50ms @ 48kHz)
    this.windowSize = 2400;
    this.channels = [];
    this.writeIndex = 0;
    this.phase = 0.0;
    this.frameCounter = 0;

    this.port.onmessage = (event) => {
      const msg = event.data || {};
      if (typeof msg.semitones === 'number') {
        this.semitones = Math.max(-12, Math.min(12, msg.semitones));
        this.pitchRatio = Math.pow(2.0, this.semitones / 12.0);
      }
      if (typeof msg.enabled === 'boolean') {
        this.enabled = msg.enabled;
      }
    };
  }

  _ensureChannels(count) {
    while (this.channels.length < count) {
      this.channels.push(new Float32Array(this.bufSize));
    }
  }

  _readLinear(buf, pos) {
    const i0 = Math.floor(pos) & this.bufMask;
    const i1 = (i0 + 1) & this.bufMask;
    const frac = pos - Math.floor(pos);
    return buf[i0] * (1.0 - frac) + buf[i1] * frac;
  }

  process(inputs, outputs, parameters) {
    const input = inputs[0];
    const output = outputs[0];
    if (!input || !output || input.length === 0) {
      return true;
    }

    const numChannels = Math.min(input.length, output.length);
    const blockLen = output[0].length;
    this._ensureChannels(numChannels);

    const paramSt = parameters.semitones ? parameters.semitones[0] : this.semitones;
    const activeSemitones = paramSt !== 0 ? paramSt : this.semitones;
    const ratio = Math.pow(2.0, activeSemitones / 12.0);

    let peak = 0.0;

    if (!this.enabled || Math.abs(activeSemitones) < 0.01) {
      for (let ch = 0; ch < numChannels; ch++) {
        const inCh = input[ch];
        const outCh = output[ch];
        const ring = this.channels[ch];
        for (let i = 0; i < blockLen; i++) {
          const s = inCh[i] || 0;
          ring[(this.writeIndex + i) & this.bufMask] = s;
          outCh[i] = s;
          const a = Math.abs(s);
          if (a > peak) peak = a;
        }
      }
      this.writeIndex = (this.writeIndex + blockLen) & this.bufMask;
    } else {
      const rateDelta = (1.0 - ratio) / this.windowSize;
      for (let i = 0; i < blockLen; i++) {
        const wIdx = this.writeIndex;
        for (let ch = 0; ch < numChannels; ch++) {
          const s = (input[ch] && input[ch][i]) || 0;
          this.channels[ch][wIdx] = s;
          const a = Math.abs(s);
          if (a > peak) peak = a;
        }

        // Two half-cycle overlapping taps with raised-cosine (Hann) crossfade
        let p1 = this.phase;
        let p2 = p1 + 0.5;
        if (p2 >= 1.0) p2 -= 1.0;

        const d1 = p1 * this.windowSize;
        const d2 = p2 * this.windowSize;
        const g1 = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * p1));
        const g2 = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * p2));

        const r1 = wIdx - d1;
        const r2 = wIdx - d2;

        for (let ch = 0; ch < numChannels; ch++) {
          const ring = this.channels[ch];
          output[ch][i] = this._readLinear(ring, r1) * g1 + this._readLinear(ring, r2) * g2;
        }

        this.phase += rateDelta;
        if (this.phase >= 1.0) this.phase -= 1.0;
        if (this.phase < 0.0) this.phase += 1.0;
        this.writeIndex = (wIdx + 1) & this.bufMask;
      }
    }

    this.frameCounter++;
    if (this.frameCounter >= 12) {
      this.frameCounter = 0;
      this.port.postMessage({ amplitude: peak });
    }

    return true;
  }
}

registerProcessor('pitch-scaler-processor', PitchScalerWorkletProcessor);
