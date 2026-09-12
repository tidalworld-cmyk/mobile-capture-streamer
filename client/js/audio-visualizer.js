class AudioVisualizer {
  constructor(canvasElement) {
    this.canvas = canvasElement;
    this.ctx = canvasElement.getContext('2d');
    this.audioContext = null;
    this.analyser = null;
    this.source = null;
    this.animationId = null;
    this.dataArray = null;
    this.peak = 0;
    this.peakDecay = 0.95;
  }

  attachStream(stream) {
    this.stop();

    const audioTracks = stream.getAudioTracks();
    if (!audioTracks || audioTracks.length === 0) {
      this.drawEmpty();
      return;
    }

    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      this.audioContext = new AudioCtx();
      this.analyser = this.audioContext.createAnalyser();
      this.analyser.fftSize = 256;
      this.analyser.smoothingTimeConstant = 0.8;

      this.source = this.audioContext.createMediaStreamSource(stream);
      this.source.connect(this.analyser);

      const bufferLength = this.analyser.frequencyBinCount;
      this.dataArray = new Uint8Array(bufferLength);

      this.render();
    } catch (e) {
      console.warn('[AudioVisualizer] Could not initialize Web Audio:', e);
      this.drawEmpty();
    }
  }

  render() {
    if (!this.analyser) return;

    this.animationId = requestAnimationFrame(() => this.render());

    this.analyser.getByteFrequencyData(this.dataArray);

    let sum = 0;
    for (let i = 0; i < this.dataArray.length; i++) {
      sum += this.dataArray[i];
    }
    const average = sum / this.dataArray.length;
    const level = Math.min(1, average / 128); // 0.0 to 1.0

    if (level > this.peak) {
      this.peak = level;
    } else {
      this.peak *= this.peakDecay;
    }

    this.draw(level, this.peak);
  }

  draw(level, peak) {
    const width = this.canvas.width;
    const height = this.canvas.height;
    this.ctx.clearRect(0, 0, width, height);

    // Background track
    this.ctx.fillStyle = '#1e293b';
    this.ctx.fillRect(0, 0, width, height);

    // Dynamic gradient
    const gradient = this.ctx.createLinearGradient(0, 0, width, 0);
    gradient.addColorStop(0, '#10b981');   // Safe Green
    gradient.addColorStop(0.7, '#f59e0b'); // Warning Amber
    gradient.addColorStop(0.9, '#ef4444'); // Clip Red

    this.ctx.fillStyle = gradient;
    const barWidth = Math.max(2, width * level);
    this.ctx.fillRect(0, 0, barWidth, height);

    // Draw peak tick mark
    const peakX = Math.min(width - 2, width * peak);
    this.ctx.fillStyle = '#ffffff';
    this.ctx.fillRect(peakX, 0, 2, height);
  }

  drawEmpty() {
    const width = this.canvas.width;
    const height = this.canvas.height;
    this.ctx.clearRect(0, 0, width, height);
    this.ctx.fillStyle = '#1e293b';
    this.ctx.fillRect(0, 0, width, height);
  }

  stop() {
    if (this.animationId) {
      cancelAnimationFrame(this.animationId);
      this.animationId = null;
    }
    if (this.source) {
      this.source.disconnect();
      this.source = null;
    }
    if (this.audioContext && this.audioContext.state !== 'closed') {
      this.audioContext.close();
      this.audioContext = null;
    }
    this.peak = 0;
    this.drawEmpty();
  }
}

window.AudioVisualizer = AudioVisualizer;
