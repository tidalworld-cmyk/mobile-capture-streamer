class MediaCaptureManager {
  constructor() {
    this.currentStream = null;
    this.videoDevices = [];
    this.audioDevices = [];
    this.selectedVideoDeviceId = null;
    this.selectedAudioDeviceId = null;
    this.currentCameraType = null; // 'front', 'back', 'external', or deviceId
    this.resolution = '1080p';
    this.fps = 30;
    this.isCompatibilityMode = false; // Safe mode for non-standard USB capture cards
    this.isLocked = true; // Maintain selected camera
    this.onDevicesChanged = null;
    this.onStreamChanged = null;
    this.onDeviceDisconnected = null;

    if (navigator.mediaDevices && navigator.mediaDevices.ondevicechange !== undefined) {
      navigator.mediaDevices.ondevicechange = async () => {
        console.log('[MediaCapture] Hardware change detected');
        await this.enumerateDevices();
        if (this.onDevicesChanged) this.onDevicesChanged();
      };
    }
  }

  getResolutionConstraints() {
    if (this.isCompatibilityMode) {
      // Non-standard capture cards fail with ideal/exact resolutions
      return {};
    }
    switch (this.resolution) {
      case '1080p':
        return { width: { ideal: 1920 }, height: { ideal: 1080 } };
      case '720p':
        return { width: { ideal: 1280 }, height: { ideal: 720 } };
      case '480p':
        return { width: { ideal: 854 }, height: { ideal: 480 } };
      default:
        return { width: { ideal: 1280 }, height: { ideal: 720 } };
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
    this.stopStream();

    if (videoDeviceId) this.selectedVideoDeviceId = videoDeviceId;
    if (audioDeviceId) this.selectedAudioDeviceId = audioDeviceId;

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

    let audioConstraints = false;
    if (this.selectedAudioDeviceId) {
      audioConstraints = {
        deviceId: { exact: this.selectedAudioDeviceId },
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false
      };
    } else {
      audioConstraints = {
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false
      };
    }

    const constraints = {
      video: videoConstraints,
      audio: audioConstraints
    };

    console.log('[MediaCapture] Starting stream with constraints:', JSON.stringify(constraints));

    try {
      this.currentStream = await navigator.mediaDevices.getUserMedia(constraints);

      this.currentStream.getVideoTracks().forEach(track => {
        track.onended = () => {
          console.warn('[MediaCapture] Video track ended');
          if (this.onDeviceDisconnected) this.onDeviceDisconnected('video');
        };
      });

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
      console.error('[MediaCapture] getUserMedia failed:', err);
      // Fallback 1: try without strict constraints
      console.warn('[MediaCapture] Fallback: Retrying with basic constraints...');
      try {
        const fallbackVideo = this.selectedVideoDeviceId ? { deviceId: { exact: this.selectedVideoDeviceId } } : true;
        this.currentStream = await navigator.mediaDevices.getUserMedia({ video: fallbackVideo, audio: true });
        if (this.onStreamChanged) this.onStreamChanged(this.currentStream);
        return this.currentStream;
      } catch (err2) {
        // Fallback 2: try video-only (non-standard capture cards without audio)
        console.warn('[MediaCapture] Fallback 2: Retrying video only...');
        const fallbackVideo = this.selectedVideoDeviceId ? { deviceId: { exact: this.selectedVideoDeviceId } } : true;
        this.currentStream = await navigator.mediaDevices.getUserMedia({ video: fallbackVideo, audio: false });
        if (this.onStreamChanged) this.onStreamChanged(this.currentStream);
        return this.currentStream;
      }
    }
  }

  stopStream() {
    if (this.currentStream) {
      this.currentStream.getTracks().forEach(track => track.stop());
      this.currentStream = null;
    }
  }
}

window.MediaCaptureManager = MediaCaptureManager;
