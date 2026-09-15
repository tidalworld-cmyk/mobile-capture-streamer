class MediaCaptureManager {
  constructor() {
    this.rawStream = null;          // Physical hardware stream from camera/capture card
    this.currentStream = null;      // Virtual mixed broadcast stream (Canvas + Web Audio)
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
    this.onDevicesChanged = null;
    this.onStreamChanged = null;
    this.onDeviceDisconnected = null;

    // Virtual Video Canvas & Offscreen Video Player
    this.virtualCanvas = document.createElement('canvas');
    this.canvasCtx = this.virtualCanvas.getContext('2d', { alpha: false });
    this.virtualVideo = document.createElement('video');
    this.virtualVideo.muted = true;
    this.virtualVideo.playsInline = true;
    this.virtualVideo.autoplay = true;

    this.virtualCanvasStream = null;
    this.renderRafId = null;

    // Virtual Web Audio Mixer
    this.virtualAudioCtx = null;
    this.virtualAudioDest = null;
    this.virtualAudioSource = null;

    this._setupCanvasDimensions();
    this._startRenderLoop();

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

  _startRenderLoop() {
    const render = () => {
      const cw = this.virtualCanvas.width;
      const ch = this.virtualCanvas.height;
      const ctx = this.canvasCtx;

      if (this.virtualVideo && this.virtualVideo.readyState >= 2) {
        const vw = this.virtualVideo.videoWidth;
        const vh = this.virtualVideo.videoHeight;

        if (vw > 0 && vh > 0) {
          const scale = Math.min(cw / vw, ch / vh);
          const dw = vw * scale;
          const dh = vh * scale;
          const dx = (cw - dw) / 2;
          const dy = (ch - dh) / 2;

          ctx.fillStyle = '#000000';
          ctx.fillRect(0, 0, cw, ch);
          ctx.drawImage(this.virtualVideo, dx, dy, dw, dh);
        }
      } else {
        ctx.fillStyle = '#05070b';
        ctx.fillRect(0, 0, cw, ch);
      }

      this.renderRafId = requestAnimationFrame(render);
    };

    if (!this.renderRafId) {
      this.renderRafId = requestAnimationFrame(render);
    }
  }

  _ensureBroadcastStream() {
    if (!this.virtualCanvasStream) {
      this.virtualCanvasStream = this.virtualCanvas.captureStream(this.fps || 30);
    }

    if (!this.virtualAudioCtx) {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (AudioContextClass) {
        this.virtualAudioCtx = new AudioContextClass();
        this.virtualAudioDest = this.virtualAudioCtx.createMediaStreamDestination();
      }
    }

    if (this.virtualAudioCtx && this.virtualAudioCtx.state === 'suspended') {
      this.virtualAudioCtx.resume().catch(() => {});
    }

    const videoTrack = this.virtualCanvasStream ? this.virtualCanvasStream.getVideoTracks()[0] : null;
    const audioTrack = this.virtualAudioDest ? this.virtualAudioDest.stream.getAudioTracks()[0] : null;

    const tracks = [];
    if (videoTrack) tracks.push(videoTrack);
    if (audioTrack) tracks.push(audioTrack);

    if (!this.currentStream) {
      this.currentStream = new MediaStream(tracks);
    } else {
      const currentVideo = this.currentStream.getVideoTracks()[0];
      if (!currentVideo && videoTrack) this.currentStream.addTrack(videoTrack);
      const currentAudio = this.currentStream.getAudioTracks()[0];
      if (!currentAudio && audioTrack) this.currentStream.addTrack(audioTrack);
    }

    return this.currentStream;
  }

  getResolutionConstraints() {
    if (this.isCompatibilityMode) {
      // Non-standard capture cards fail with ideal/exact resolutions
      return {};
    }
    const isPortrait = this.aspectRatio === '9:16';
    switch (this.resolution) {
      case '1080p':
        return isPortrait
          ? { width: { ideal: 1080 }, height: { ideal: 1920 }, aspectRatio: { ideal: 9/16 } }
          : { width: { ideal: 1920 }, height: { ideal: 1080 }, aspectRatio: { ideal: 16/9 } };
      case '720p':
        return isPortrait
          ? { width: { ideal: 720 }, height: { ideal: 1280 }, aspectRatio: { ideal: 9/16 } }
          : { width: { ideal: 1280 }, height: { ideal: 720 }, aspectRatio: { ideal: 16/9 } };
      case '480p':
        return isPortrait
          ? { width: { ideal: 480 }, height: { ideal: 854 }, aspectRatio: { ideal: 9/16 } }
          : { width: { ideal: 854 }, height: { ideal: 480 }, aspectRatio: { ideal: 16/9 } };
      default:
        return isPortrait
          ? { width: { ideal: 720 }, height: { ideal: 1280 }, aspectRatio: { ideal: 9/16 } }
          : { width: { ideal: 1280 }, height: { ideal: 720 }, aspectRatio: { ideal: 16/9 } };
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

  async startStream(videoDeviceId = null, audioDeviceId = null) {
    if (videoDeviceId) this.selectedVideoDeviceId = videoDeviceId;
    if (audioDeviceId) this.selectedAudioDeviceId = audioDeviceId;

    this._setupCanvasDimensions();
    this._ensureBroadcastStream();

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

    console.log('[MediaCapture] Switching hardware input to:', this.selectedVideoDeviceId);

    try {
      const newHardwareStream = await navigator.mediaDevices.getUserMedia(constraints);

      // Stop old hardware tracks only (NOT the broadcast currentStream!)
      if (this.rawStream) {
        this.rawStream.getTracks().forEach(track => {
          try { track.stop(); } catch (e) {}
        });
      }
      this.rawStream = newHardwareStream;

      // Attach new video to offscreen player
      this.virtualVideo.srcObject = this.rawStream;
      try {
        await this.virtualVideo.play();
      } catch (e) {
        console.warn('virtualVideo play warning:', e);
      }

      // Connect new audio track to AudioContext mixer
      if (this.virtualAudioCtx && this.virtualAudioDest) {
        if (this.virtualAudioSource) {
          try { this.virtualAudioSource.disconnect(); } catch (e) {}
          this.virtualAudioSource = null;
        }

        const audioTracks = this.rawStream.getAudioTracks();
        if (audioTracks.length > 0) {
          try {
            const audioOnlyStream = new MediaStream([audioTracks[0]]);
            this.virtualAudioSource = this.virtualAudioCtx.createMediaStreamSource(audioOnlyStream);
            this.virtualAudioSource.connect(this.virtualAudioDest);
          } catch (audioErr) {
            console.warn('[MediaCapture] Virtual audio connect error:', audioErr);
          }
        }
      }

      // Listen for genuine hardware disconnection (e.g. cable unplugged)
      this.rawStream.getVideoTracks().forEach(track => {
        track.onended = () => {
          if (!this.isSwitching) {
            console.warn('[MediaCapture] Hardware camera disconnected');
            if (this.onDeviceDisconnected) this.onDeviceDisconnected('video');
          }
        };
      });

      this.isSwitching = false;

      await this.enumerateDevices();

      const activeVideoTrack = this.rawStream.getVideoTracks()[0];
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

      // Fallback 1: try without strict constraints
      console.warn('[MediaCapture] Fallback: Retrying with basic constraints...');
      try {
        const fallbackVideo = this.selectedVideoDeviceId ? { deviceId: { exact: this.selectedVideoDeviceId } } : true;
        const newHardwareStream = await navigator.mediaDevices.getUserMedia({ video: fallbackVideo, audio: true });
        
        if (this.rawStream) {
          this.rawStream.getTracks().forEach(track => { try { track.stop(); } catch (e) {} });
        }
        this.rawStream = newHardwareStream;
        this.virtualVideo.srcObject = this.rawStream;
        await this.virtualVideo.play().catch(() => {});

        if (this.onStreamChanged) this.onStreamChanged(this.currentStream);
        return this.currentStream;
      } catch (err2) {
        // Fallback 2: try video-only
        console.warn('[MediaCapture] Fallback 2: Retrying video only...');
        const fallbackVideo = this.selectedVideoDeviceId ? { deviceId: { exact: this.selectedVideoDeviceId } } : true;
        const newHardwareStream = await navigator.mediaDevices.getUserMedia({ video: fallbackVideo, audio: false });
        
        if (this.rawStream) {
          this.rawStream.getTracks().forEach(track => { try { track.stop(); } catch (e) {} });
        }
        this.rawStream = newHardwareStream;
        this.virtualVideo.srcObject = this.rawStream;
        await this.virtualVideo.play().catch(() => {});

        if (this.onStreamChanged) this.onStreamChanged(this.currentStream);
        return this.currentStream;
      }
    }
  }

  stopStream() {
    if (this.rawStream) {
      this.rawStream.getTracks().forEach(track => {
        try { track.stop(); } catch (e) {}
      });
      this.rawStream = null;
    }
    if (this.virtualVideo) {
      this.virtualVideo.srcObject = null;
    }
    if (this.virtualAudioSource) {
      try { this.virtualAudioSource.disconnect(); } catch (e) {}
      this.virtualAudioSource = null;
    }
  }
}

window.MediaCaptureManager = MediaCaptureManager;
