const { spawn } = require('child_process');
const EventEmitter = require('events');

class FFmpegRelay extends EventEmitter {
  constructor(options = {}) {
    super();
    this.rtmpUrl = options.rtmpUrl;
    this.secondaryRtmpUrl = options.secondaryRtmpUrl || null;
    this.videoBitrate = options.videoBitrate || '3000k';
    this.audioBitrate = options.audioBitrate || '128k';
    this.fps = options.fps || 30;
    this.ffmpegProcess = null;
    this.isActive = false;
    this.stats = {
      fps: 0,
      bitrate: '0kbits/s',
      frames: 0,
      time: '00:00:00.00',
      speed: '0x'
    };
  }

  start() {
    if (this.isActive) {
      console.warn('[FFmpegRelay] Process is already active');
      return;
    }

    if (!this.rtmpUrl) {
      throw new Error('RTMP destination URL is required');
    }

    const gopSize = Math.max(30, this.fps * 2); // 2 second keyframe interval for YouTube/RTMP

    let outputArgs = [];
    if (this.secondaryRtmpUrl) {
      // Multi-streaming via tee muxer
      console.log(`[FFmpegRelay] Multi-streaming to: \n  1: ${this.rtmpUrl}\n  2: ${this.secondaryRtmpUrl}`);
      outputArgs = [
        '-flags', '+global_header',
        '-f', 'tee',
        '-map', '0:v',
        '-map', '0:a',
        `[f=flv:flvflags=no_duration_filesize]${this.rtmpUrl}|[f=flv:flvflags=no_duration_filesize]${this.secondaryRtmpUrl}`
      ];
    } else {
      // Single RTMP destination
      console.log(`[FFmpegRelay] Streaming to: ${this.rtmpUrl.replace(/(live2\/).+/, '$1[HIDDEN_STREAM_KEY]')}`);
      outputArgs = [
        '-f', 'flv',
        '-flvflags', 'no_duration_filesize',
        this.rtmpUrl
      ];
    }

    const ffmpegArgs = [
      '-loglevel', 'info',
      // Real-time pipe input
      '-f', 'webm',
      '-i', 'pipe:0',
      // Video encoding for YouTube RTMP compatibility (ultrafast for low CPU usage)
      '-c:v', 'libx264',
      '-preset', 'ultrafast',
      '-tune', 'zerolatency',
      '-threads', '0',
      '-b:v', this.videoBitrate,
      '-maxrate', this.videoBitrate,
      '-bufsize', `${parseInt(this.videoBitrate) * 2}k`,
      '-pix_fmt', 'yuv420p',
      '-g', gopSize.toString(),
      '-r', this.fps.toString(),
      // Audio encoding
      '-c:a', 'aac',
      '-ar', '44100',
      '-b:a', this.audioBitrate,
      ...outputArgs
    ];

    console.log('[FFmpegRelay] Spawning FFmpeg with args:', ffmpegArgs.join(' '));

    try {
      this.ffmpegProcess = spawn('ffmpeg', ffmpegArgs, {
        windowsHide: true,
        stdio: ['pipe', 'ignore', 'pipe'] // stdin pipe, stdout ignore, stderr pipe
      });

      this.isActive = true;

      this.ffmpegProcess.stdin.on('error', (err) => {
        console.error('[FFmpegRelay] stdin error:', err.message);
        this.emit('error', err);
      });

      this.ffmpegProcess.stderr.on('data', (data) => {
        const text = data.toString();
        this._parseStats(text);
        if (text.includes('Error') || text.includes('failed') || text.includes('Connection refused')) {
          console.error('[FFmpeg stderr]:', text.trim());
          this.emit('log', { level: 'error', message: text.trim() });
        }
      });

      this.ffmpegProcess.on('close', (code, signal) => {
        console.log(`[FFmpegRelay] Process exited with code ${code}, signal ${signal}`);
        this.isActive = false;
        this.ffmpegProcess = null;
        this.emit('close', { code, signal });
      });

      this.ffmpegProcess.on('error', (err) => {
        console.error('[FFmpegRelay] Spawn error:', err);
        this.isActive = false;
        this.emit('error', err);
      });

      this.emit('start');
    } catch (err) {
      this.isActive = false;
      console.error('[FFmpegRelay] Exception starting FFmpeg:', err);
      throw err;
    }
  }

  write(chunk) {
    if (this.isActive && this.ffmpegProcess && this.ffmpegProcess.stdin.writable) {
      return this.ffmpegProcess.stdin.write(chunk);
    }
    return false;
  }

  _parseStats(text) {
    // Parse: frame=  120 fps= 30.2 q=28.0 size=    1024kB time=00:00:04.00 bitrate=2097.1kbits/s speed=1.01x
    const frameMatch = text.match(/frame=\s*(\d+)/);
    const fpsMatch = text.match(/fps=\s*([\d.]+)/);
    const timeMatch = text.match(/time=\s*([\d:.]+)/);
    const bitrateMatch = text.match(/bitrate=\s*([\d.]+\s*\w+\/s)/);
    const speedMatch = text.match(/speed=\s*([\d.]+x)/);

    let updated = false;
    if (frameMatch) { this.stats.frames = parseInt(frameMatch[1], 10); updated = true; }
    if (fpsMatch) { this.stats.fps = parseFloat(fpsMatch[1]); updated = true; }
    if (timeMatch) { this.stats.time = timeMatch[1]; updated = true; }
    if (bitrateMatch) { this.stats.bitrate = bitrateMatch[1]; updated = true; }
    if (speedMatch) { this.stats.speed = speedMatch[1]; updated = true; }

    if (updated) {
      this.emit('stats', { ...this.stats });
    }
  }

  stop() {
    if (!this.isActive || !this.ffmpegProcess) {
      return;
    }
    console.log('[FFmpegRelay] Stopping FFmpeg process...');
    try {
      if (this.ffmpegProcess.stdin && this.ffmpegProcess.stdin.writable) {
        this.ffmpegProcess.stdin.end();
      }
    } catch (e) {}

    setTimeout(() => {
      if (this.ffmpegProcess) {
        console.log('[FFmpegRelay] Sending SIGTERM to FFmpeg');
        this.ffmpegProcess.kill('SIGTERM');
      }
    }, 1500);

    setTimeout(() => {
      if (this.ffmpegProcess) {
        console.log('[FFmpegRelay] Force killing FFmpeg with SIGKILL');
        this.ffmpegProcess.kill('SIGKILL');
      }
    }, 3500);
  }
}

module.exports = FFmpegRelay;
