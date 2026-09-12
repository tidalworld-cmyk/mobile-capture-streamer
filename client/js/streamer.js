class LiveStreamer {
  constructor() {
    this.ws = null;
    this.mediaRecorder = null;
    this.isStreaming = false;
    this.serverUrl = null;
    this.onStatusChange = null;
    this.onStats = null;
    this.onError = null;
    this.timesliceMs = 1000;
  }

  getBestMimeType() {
    const types = [
      'video/webm;codecs=vp8,opus',
      'video/webm;codecs=h264,opus',
      'video/webm;codecs=vp9,opus',
      'video/webm',
      'video/mp4'
    ];
    for (const t of types) {
      if (MediaRecorder.isTypeSupported(t)) {
        console.log(`[LiveStreamer] Supported MIME type selected: ${t}`);
        return t;
      }
    }
    return '';
  }

  async startBroadcast(stream, config) {
    if (this.isStreaming) {
      throw new Error('Broadcast is already active.');
    }

    const {
      relayWsUrl,
      rtmpUrl,
      secondaryRtmpUrl,
      videoBitrate,
      audioBitrate,
      fps
    } = config;

    // Use current host if no custom relay URL specified
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const defaultWsUrl = `${wsProtocol}//${window.location.host}/live-stream`;
    this.serverUrl = relayWsUrl || defaultWsUrl;

    console.log(`[LiveStreamer] Connecting to streaming relay at ${this.serverUrl}`);

    return new Promise((resolve, reject) => {
      try {
        this.ws = new WebSocket(this.serverUrl);
        this.ws.binaryType = 'arraybuffer';
      } catch (err) {
        return reject(new Error(`Failed to initialize WebSocket connection: ${err.message}`));
      }

      this.ws.onopen = () => {
        console.log('[LiveStreamer] WebSocket connected. Sending stream configuration...');
        
        // 1. Send start command with RTMP config
        this.ws.send(JSON.stringify({
          type: 'start',
          rtmpUrl,
          secondaryRtmpUrl,
          videoBitrate,
          audioBitrate,
          fps
        }));

        // 2. Initialize MediaRecorder
        try {
          const mimeType = this.getBestMimeType();
          const recorderOptions = {
            videoBitsPerSecond: parseInt(videoBitrate) * 1000 || 3000000,
            audioBitsPerSecond: parseInt(audioBitrate) * 1000 || 128000
          };
          if (mimeType) {
            recorderOptions.mimeType = mimeType;
          }

          this.mediaRecorder = new MediaRecorder(stream, recorderOptions);

          this.mediaRecorder.ondataavailable = (event) => {
            if (event.data && event.data.size > 0 && this.ws && this.ws.readyState === WebSocket.OPEN) {
              this.ws.send(event.data);
            }
          };

          this.mediaRecorder.onerror = (e) => {
            console.error('[MediaRecorder error]:', e);
            if (this.onError) this.onError(e);
          };

          this.mediaRecorder.start(this.timesliceMs);
          this.isStreaming = true;

          if (this.onStatusChange) {
            this.onStatusChange('live');
          }

          resolve(true);
        } catch (recorderError) {
          this.stopBroadcast();
          reject(recorderError);
        }
      };

      this.ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'stats') {
            if (this.onStats) this.onStats(msg.data);
          } else if (msg.type === 'status') {
            if (msg.state === 'stopped') {
              this.stopBroadcast();
            }
          } else if (msg.type === 'error') {
            console.error('[LiveStreamer Server Error]:', msg.message);
            if (this.onError) this.onError(msg.message);
          }
        } catch (e) {}
      };

      this.ws.onerror = (err) => {
        console.error('[LiveStreamer] WebSocket error:', err);
        if (!this.isStreaming) {
          reject(new Error('Could not connect to streaming relay server.'));
        } else if (this.onError) {
          this.onError('Relay connection error');
        }
      };

      this.ws.onclose = () => {
        console.log('[LiveStreamer] WebSocket closed');
        this.stopBroadcast();
      };
    });
  }

  stopBroadcast() {
    if (!this.isStreaming && !this.mediaRecorder && !this.ws) return;

    this.isStreaming = false;

    if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      try {
        this.mediaRecorder.stop();
      } catch (e) {}
    }
    this.mediaRecorder = null;

    if (this.ws) {
      if (this.ws.readyState === WebSocket.OPEN) {
        try {
          this.ws.send(JSON.stringify({ type: 'stop' }));
        } catch (e) {}
      }
      this.ws.close();
      this.ws = null;
    }

    if (this.onStatusChange) {
      this.onStatusChange('stopped');
    }
  }
}

window.LiveStreamer = LiveStreamer;
