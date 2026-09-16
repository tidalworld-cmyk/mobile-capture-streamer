class MediaCaptureManager {
  constructor() {
    this.currentStream = null;      // Direct native hardware stream from camera/capture card
    this.videoDevices = [];
    this.audioDevices = [];
    this.selectedVideoDeviceId = null;
    this.selectedAudioDeviceId = null;
    this.currentCameraType = null;
    this.resolution = '1080p';
    this.aspectRatio = '16:9';      // '16:9' or '9:16'
    this.fps = 30;
    this.isCompatibilityMode = false;
    this.isLocked = true;
    this.isSwitching = false;
    this.isBroadcasting = false;
    this.onDevicesChanged = null;
    this.onStreamChanged = null;
    this.onDeviceDisconnected = null;

    // Broadcast Canvas & Audio Mixer
    this.virtualCanvas = document.createElement('canvas');
    this.canvasCtx = this.virtualCanvas.getContext('2d', { alpha: false });
    this.broadcastCanvasStream = null;
    this.renderRafId = null;

    this.virtualAudioCtx = null;
    this.virtualAudioDest = null;
    this.virtualAudioSource = null;

    this._setupCanvasDimensions();

    if (navigator.mediaDevices && navigator.mediaDevices.ondevicechange !== undefined) {
      navigator.mediaDevices.ondevicechange = async () => {
        console.log('[MediaCapture] Hardware change detected');
        await this.enumerateDevices();
        if (this.onDevicesChanged) this.onDevicesChanged();
      };
    }
  }

  _setupCanvasDimensions() {
    const isPortrait = this.aspectRatio === '9:16';
    let w = 1920;
    let h = 1080;
    if (this.resolution === '720p') {
      w = 1280; h = 720;
    } else if (this.resolution === '480p') {
      w = 854; h = 480;
    }

    this.virtualCanvas.width = isPortrait ? h : w;
    this.virtualCanvas.height = isPortrait ? w : h;
  }

  getBroadcastStream() {
    this._setupCanvasDimensions();
    this.isBroadcasting = true;

    if (!this.broadcastCanvasStream) {
      this.broadcastCanvasStream = this.virtualCanvas.captureStream(this.fps || 30);
    }
    this._startBroadcastRenderLoop();

    // Ensure virtual audio mixer
    if (!this.virtualAudioCtx) {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (AudioCtx) {
        this.virtualAudioCtx = new AudioCtx();
        this.virtualAudioDest = this.virtualAudioCtx.createMediaStreamDestination();
      }
    }
    if (this.virtualAudioCtx && this.virtualAudioCtx.state === 'suspended') {
      this.virtualAudioCtx.resume().catch(() => {});
    }

    this._connectAudioToMixer();

    const vTrack = this.broadcastCanvasStream.getVideoTracks()[0];
    const aTrack = this.virtualAudioDest ? this.virtualAudioDest.stream.getAudioTracks()[0] : null;

    const tracks = [];
    if (vTrack) tracks.push(vTrack);
    if (aTrack) {
      tracks.push(aTrack);
    } else if (this.currentStream && this.currentStream.getAudioTracks().length > 0) {
      tracks.push(this.currentStream.getAudioTracks()[0]);
    }

    return new MediaStream(tracks);
  }

  stopBroadcastStream() {
    this.isBroadcasting = false;
    if (this.renderRafId) {
      cancelAnimationFrame(this.renderRafId);
      this.renderRafId = null;
    }
  }

  _startBroadcastRenderLoop() {
    if (this.renderRafId) return;
    const videoElem = document.getElementById('videoPreview');

    const render = () => {
      const cw = this.virtualCanvas.width;
      const ch = this.virtualCanvas.height;
      const ctx = this.canvasCtx;

      if (videoElem && videoElem.readyState >= 2 && !videoElem.paused) {
        const vw = videoElem.videoWidth;
        const vh = videoElem.videoHeight;
        if (vw > 0 && vh > 0) {
          const scale = Math.min(cw / vw, ch / vh);
          const dw = vw * scale;
          const dh = vh * scale;
          const dx = (cw - dw) / 2;
          const dy = (ch - dh) / 2;

          ctx.fillStyle = '#000000';
          ctx.fillRect(0, 0, cw, ch);
          ctx.drawImage(videoElem, dx, dy, dw, dh);
        }
      }

      if (this.isBroadcasting) {
        this.renderRafId = requestAnimationFrame(render);
      } else {
        this.renderRafId = null;
      }
    };

    this.renderRafId = requestAnimationFrame(render);
  }

  _connectAudioToMixer() {
    if (!this.virtualAudioCtx || !this.virtualAudioDest || !this.currentStream) return;
    if (this.virtualAudioSource) {
      try { this.virtualAudioSource.disconnect(); } catch (e) {}
      this.virtualAudioSource = null;
    }
    const audioTracks = this.currentStream.getAudioTracks();
    if (audioTracks.length > 0) {
      try {
        const audioOnlyStream = new MediaStream([audioTracks[0]]);
        this.virtualAudioSource = this.virtualAudioCtx.createMediaStreamSource(audioOnlyStream);
        this.virtualAudioSource.connect(this.virtualAudioDest);
      } catch (e) {
        console.warn('[MediaCapture] Audio mixer connect error:', e);
      }
    }
  }

  getResolutionConstraints() {
    if (this.isCompatibilityMode) {
      // Non-standard capture cards fail with ideal/exact resolutions
      return {};
    }
    const ratio = this.aspectRatio;
    if (ratio === '9:16') {
      switch (this.resolution) {
        case '1080p': return { width: { ideal: 1080 }, height: { ideal: 1920 }, aspectRatio: { ideal: 9/16 } };
        case '720p': return { width: { ideal: 720 }, height: { ideal: 1280 }, aspectRatio: { ideal: 9/16 } };
        case '480p': return { width: { ideal: 480 }, height: { ideal: 854 }, aspectRatio: { ideal: 9/16 } };
        default: return { width: { ideal: 720 }, height: { ideal: 1280 }, aspectRatio: { ideal: 9/16 } };
      }
    } else if (ratio === '4:3') {
      switch (this.resolution) {
        case '1080p': return { width: { ideal: 1440 }, height: { ideal: 1080 }, aspectRatio: { ideal: 4/3 } };
        case '720p': return { width: { ideal: 960 }, height: { ideal: 720 }, aspectRatio: { ideal: 4/3 } };
        case '480p': return { width: { ideal: 640 }, height: { ideal: 480 }, aspectRatio: { ideal: 4/3 } };
        default: return { width: { ideal: 960 }, height: { ideal: 720 }, aspectRatio: { ideal: 4/3 } };
      }
    } else {
      // 16:9 default
      switch (this.resolution) {
        case '1080p': return { width: { ideal: 1920 }, height: { ideal: 1080 }, aspectRatio: { ideal: 16/9 } };
        case '720p': return { width: { ideal: 1280 }, height: { ideal: 720 }, aspectRatio: { ideal: 16/9 } };
        case '480p': return { width: { ideal: 854 }, height: { ideal: 480 }, aspectRatio: { ideal: 16/9 } };
        default: return { width: { ideal: 1280 }, height: { ideal: 720 }, aspectRatio: { ideal: 16/9 } };
      }
    }
  }

  async enumerateDevices() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) {
      throw new Error('WebRTC / mediaDevices API is not supported in this browser.');
    }

    const devices = await navigator.mediaDevices.enumerateDevices();
    this.videoDevices = devices.filter(d => d.kind === 'videoinput');
    this.audioDevices = devices.filter(d => d.kind === 'audioinput');

    return {
      videoDevices: this.videoDevices,
      audioDevices: this.audioDevices,
      categorized: this.getCategorizedCameras()
    };
  }

  /**
   * Categorizes detected cameras into Front, Back, and External Type-C / Non-Standard
   */
  getCategorizedCameras() {
    let front = null;
    let back = null;
    let external = null;
    const others = [];

    const extKeywords = ['capture', 'cam link', 'usb', 'hdmi', 'uvc', 'fhd', 'video grabber', 'ezcap', 'elgato', 'ms2109', 'ms2130', 'external', 'camera 2', 'camera 3'];

    // 1. Check for explicit external keywords
    external = this.videoDevices.find(d => {
      const label = (d.label || '').toLowerCase();
      return extKeywords.some(k => label.includes(k));
    });

    // 2. Identify front and back cameras
    for (const d of this.videoDevices) {
      if (external && d.deviceId === external.deviceId) continue;
      const label = (d.label || '').toLowerCase();
      if (!front && (label.includes('front') || label.includes('selfie') || label.includes('user'))) {
        front = d;
      } else if (!back && (label.includes('back') || label.includes('rear') || label.includes('environment') || label.includes('main'))) {
        back = d;
      } else {
        others.push(d);
      }
    }

    // Fallbacks if labels are generic (e.g., "Camera 0", "Camera 1", "Camera 2")
    if (!front && this.videoDevices.length > 0) front = this.videoDevices[0];
    if (!back && this.videoDevices.length > 1) back = this.videoDevices[1];
    if (!external && this.videoDevices.length > 2) external = this.videoDevices[2];

    return { front, back, external, others };
  }

  findLikelyCaptureCard() {
    const { external } = this.getCategorizedCameras();
    return external;
  }

  findLikelyCaptureAudio() {
    const keywords = ['capture', 'usb', 'hdmi', 'cam link', 'digital', 'external', 'line in'];
    return this.audioDevices.find(d => {
      const label = (d.label || '').toLowerCase();
      return keywords.some(k => label.includes(k));
    }) || null;
  }

  async startScreenCapture() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
      throw new Error('Screen / App capture is not supported in this browser.');
    }
    const screenStream = await navigator.mediaDevices.getDisplayMedia({
      video: { frameRate: { ideal: this.fps, max: this.fps } },
      audio: true
    });
    if (this.currentStream) {
      this.currentStream.getTracks().forEach(t => { try { t.stop(); } catch (e) {} });
    }
    this.currentStream = screenStream;
    this.selectedVideoDeviceId = 'screen';
    this._connectAudioToMixer();
    if (this.onStreamChanged) this.onStreamChanged(this.currentStream);
    return this.currentStream;
  }

  async startStream(videoDeviceId = null, audioDeviceId = null) {
    if (videoDeviceId) this.selectedVideoDeviceId = videoDeviceId;
    if (audioDeviceId) this.selectedAudioDeviceId = audioDeviceId;

    this.isSwitching = true;

    const res = this.getResolutionConstraints();

    let videoConstraints = {};
    if (this.isCompatibilityMode) {
      // Safe mode for non-standard USB capture cards
      videoConstraints = this.selectedVideoDeviceId 
        ? { deviceId: { exact: this.selectedVideoDeviceId } }
        : true;
    } else {
      videoConstraints = {
        ...res,
        frameRate: { ideal: this.fps, max: this.fps }
      };
      if (this.selectedVideoDeviceId) {
        videoConstraints.deviceId = { exact: this.selectedVideoDeviceId };
      } else {
        videoConstraints.facingMode = { ideal: 'environment' };
      }
    }

    let audioConstraints = {
      echoCancellation: false,
      noiseSuppression: false,
      autoGainControl: false
    };
    if (this.selectedAudioDeviceId) {
      audioConstraints.deviceId = { exact: this.selectedAudioDeviceId };
    }

    const constraints = {
      video: videoConstraints,
      audio: audioConstraints
    };

    console.log('[MediaCapture] Requesting hardware camera:', this.selectedVideoDeviceId);

    try {
      const newStream = await navigator.mediaDevices.getUserMedia(constraints);

      // Stop previous camera tracks cleanly
      if (this.currentStream) {
        this.currentStream.getTracks().forEach(track => {
          try { track.stop(); } catch (e) {}
        });
      }

      this.currentStream = newStream;

      // Connect new audio to mixer if live
      this._connectAudioToMixer();

      // Listen for genuine hardware disconnection (e.g. cable unplugged)
      this.currentStream.getVideoTracks().forEach(track => {
        track.onended = () => {
          if (!this.isSwitching) {
            console.warn('[MediaCapture] Hardware camera disconnected');
            if (this.onDeviceDisconnected) this.onDeviceDisconnected('video');
          }
        };
      });

      this.isSwitching = false;

      await this.enumerateDevices();

      const activeVideoTrack = this.currentStream.getVideoTracks()[0];
      if (activeVideoTrack) {
        const settings = activeVideoTrack.getSettings();
        if (settings.deviceId) {
          this.selectedVideoDeviceId = settings.deviceId;
        }
      }

      if (this.onStreamChanged) {
        this.onStreamChanged(this.currentStream);
      }

      return this.currentStream;
    } catch (err) {
      this.isSwitching = false;
      console.error('[MediaCapture] getUserMedia failed:', err);

      // Fallback 1: try without strict resolution constraints
      console.warn('[MediaCapture] Fallback: Retrying with basic constraints...');
      try {
        const fallbackVideo = this.selectedVideoDeviceId ? { deviceId: { exact: this.selectedVideoDeviceId } } : true;
        const newStream = await navigator.mediaDevices.getUserMedia({ video: fallbackVideo, audio: true });
        
        if (this.currentStream) {
          this.currentStream.getTracks().forEach(track => { try { track.stop(); } catch (e) {} });
        }
        this.currentStream = newStream;
        this._connectAudioToMixer();

        if (this.onStreamChanged) this.onStreamChanged(this.currentStream);
        return this.currentStream;
      } catch (err2) {
        // Fallback 2: try video-only
        console.warn('[MediaCapture] Fallback 2: Retrying video only...');
        const fallbackVideo = this.selectedVideoDeviceId ? { deviceId: { exact: this.selectedVideoDeviceId } } : true;
        const newStream = await navigator.mediaDevices.getUserMedia({ video: fallbackVideo, audio: false });
        
        if (this.currentStream) {
          this.currentStream.getTracks().forEach(track => { try { track.stop(); } catch (e) {} });
        }
        this.currentStream = newStream;

        if (this.onStreamChanged) this.onStreamChanged(this.currentStream);
        return this.currentStream;
      }
    }
  }

  stopStream() {
    if (this.currentStream) {
      this.currentStream.getTracks().forEach(track => {
        try { track.stop(); } catch (e) {}
      });
      this.currentStream = null;
    }
    this.stopBroadcastStream();
  }
}

window.MediaCaptureManager = MediaCaptureManager;
