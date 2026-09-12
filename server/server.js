const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const os = require('os');
const express = require('express');
const { WebSocketServer } = require('ws');
const cors = require('cors');
require('dotenv').config();

const FFmpegRelay = require('./ffmpeg-relay');

const app = express();
const HTTP_PORT = process.env.PORT || 3000;
const HTTPS_PORT = process.env.HTTPS_PORT || 3443;

app.use(cors());
app.use(express.json());

// Serve static frontend files
const clientDir = path.join(__dirname, '..', 'client');
app.use(express.static(clientDir));

// Helper: get local LAN IPv4 addresses
function getLocalIpAddresses() {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        addresses.push({ interface: name, address: iface.address });
      }
    }
  }
  return addresses;
}

const activeRelays = new Map();

// Attach streaming logic to a WebSocket server
function setupWebSocketServer(wss) {
  wss.on('connection', (ws, req) => {
    const clientIp = req.socket.remoteAddress;
    console.log(`[WebSocket] New streaming client connected from ${clientIp}`);

    let relay = null;
    let isStreaming = false;

    ws.on('message', (message, isBinary) => {
      if (!isBinary) {
        try {
          const payload = JSON.parse(message.toString());
          
          if (payload.type === 'start') {
            if (isStreaming) {
              ws.send(JSON.stringify({ type: 'warning', message: 'Stream is already running.' }));
              return;
            }

            const { rtmpUrl, secondaryRtmpUrl, videoBitrate, audioBitrate, fps } = payload;
            
            if (!rtmpUrl) {
              ws.send(JSON.stringify({ type: 'error', message: 'RTMP URL / Stream Key is missing.' }));
              return;
            }

            console.log('[WebSocket] Starting live stream session...');
            relay = new FFmpegRelay({
              rtmpUrl,
              secondaryRtmpUrl,
              videoBitrate: videoBitrate || '3000k',
              audioBitrate: audioBitrate || '128k',
              fps: fps || 30
            });

            relay.on('start', () => {
              isStreaming = true;
              activeRelays.set(ws, relay);
              ws.send(JSON.stringify({ type: 'status', state: 'live', message: 'FFmpeg relay started. Streaming to RTMP.' }));
            });

            relay.on('stats', (stats) => {
              if (ws.readyState === ws.OPEN) {
                ws.send(JSON.stringify({ type: 'stats', data: stats }));
              }
            });

            relay.on('log', (log) => {
              if (ws.readyState === ws.OPEN) {
                ws.send(JSON.stringify({ type: 'log', data: log }));
              }
            });

            relay.on('error', (err) => {
              console.error('[Relay Error]', err.message);
              if (ws.readyState === ws.OPEN) {
                ws.send(JSON.stringify({ type: 'error', message: err.message }));
              }
            });

            relay.on('close', ({ code, signal }) => {
              console.log(`[Relay Close] Code ${code}, signal ${signal}`);
              isStreaming = false;
              activeRelays.delete(ws);
              if (ws.readyState === ws.OPEN) {
                ws.send(JSON.stringify({ type: 'status', state: 'stopped', message: 'Stream stopped.' }));
              }
            });

            relay.start();
          } else if (payload.type === 'stop') {
            console.log('[WebSocket] Client requested stop stream');
            if (relay) {
              relay.stop();
              relay = null;
            }
            isStreaming = false;
          } else if (payload.type === 'ping') {
            ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
          }
        } catch (e) {
          console.error('[WebSocket] JSON parse error:', e.message);
        }
      } else {
        if (relay && isStreaming) {
          relay.write(message);
        }
      }
    });

    ws.on('close', () => {
      console.log(`[WebSocket] Client disconnected (${clientIp})`);
      if (relay) {
        relay.stop();
        relay = null;
      }
      activeRelays.delete(ws);
    });

    ws.on('error', (err) => {
      console.error(`[WebSocket error] ${clientIp}:`, err.message);
      if (relay) {
        relay.stop();
        relay = null;
      }
      activeRelays.delete(ws);
    });
  });
}

// 1. HTTP Server
const httpServer = http.createServer(app);
const httpWss = new WebSocketServer({ server: httpServer, path: '/live-stream' });
setupWebSocketServer(httpWss);

httpServer.listen(HTTP_PORT, '0.0.0.0', () => {
  console.log(`[HTTP] Running on http://localhost:${HTTP_PORT}`);
});

// 2. HTTPS Server (Required for Mobile Browser Camera Access)
const certPath = path.join(__dirname, 'ssl', 'cert.pem');
const keyPath = path.join(__dirname, 'ssl', 'key.pem');

if (fs.existsSync(certPath) && fs.existsSync(keyPath)) {
  const httpsOptions = {
    key: fs.readFileSync(keyPath),
    cert: fs.readFileSync(certPath)
  };

  const httpsServer = https.createServer(httpsOptions, app);
  const httpsWss = new WebSocketServer({ server: httpsServer, path: '/live-stream' });
  setupWebSocketServer(httpsWss);

  httpsServer.listen(HTTPS_PORT, '0.0.0.0', () => {
    const ips = getLocalIpAddresses();
    console.log('====================================================');
    console.log(`🔒 SECURE HTTPS SERVER running on Port ${HTTPS_PORT}`);
    console.log('📱 MOBILE ACCESS URLS (REQUIRED FOR MOBILE CAMERA):');
    ips.forEach(item => {
      console.log(`   👉 https://${item.address}:${HTTPS_PORT} (${item.interface})`);
    });
    console.log('----------------------------------------------------');
    console.log('⚠️  Note for Mobile Browser: When opening HTTPS on mobile,');
    console.log('   tap "Advanced" -> "Proceed to site (unsafe)" once to accept the local cert.');
    console.log('====================================================');
  });
}

// Info endpoint
app.get('/api/info', (req, res) => {
  res.json({
    status: 'online',
    httpPort: HTTP_PORT,
    httpsPort: HTTPS_PORT,
    localIps: getLocalIpAddresses(),
    activeStreams: activeRelays.size,
    timestamp: new Date().toISOString()
  });
});
