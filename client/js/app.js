document.addEventListener('DOMContentLoaded', () => {
  // Check Secure Context on Mobile
  const isLocalHost = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
  if (!window.isSecureContext && !isLocalHost && location.protocol === 'http:') {
    const httpsUrl = `https://${location.hostname}:3443${location.pathname}`;
    console.warn(`[Secure Context Warning] Camera API disabled on HTTP. Redirecting to ${httpsUrl}`);
    setTimeout(() => {
      if (confirm('Mobile browsers require HTTPS for camera access. Switch to secure HTTPS now?')) {
        window.location.href = httpsUrl;
      }
    }, 500);
  }

  // Elements
  const videoPreview = document.getElementById('videoPreview');
  const placeholderView = document.getElementById('placeholderView');
  const startPreviewBtn = document.getElementById('startPreviewBtn');
  const statusBadge = document.getElementById('statusBadge');
  const statusText = document.getElementById('statusText');
  const liveTimer = document.getElementById('liveTimer');
  const liveTimerChip = document.getElementById('liveTimerChip');
  const statsFps = document.getElementById('statsFps');
  const statsBitrate = document.getElementById('statsBitrate');
  const audioMeterCanvas = document.getElementById('audioMeterCanvas');

  // Dynamic Camera Grid & Lock
  const cameraTabsGrid = document.getElementById('cameraTabsGrid');
  const toggleLockCamBtn = document.getElementById('toggleLockCamBtn');

  // Control Buttons
  const broadcastBtn = document.getElementById('broadcastBtn');
  const broadcastBtnText = document.getElementById('broadcastBtnText');
  const switchCameraBtn = document.getElementById('switchCameraBtn');
  const mirrorBtn = document.getElementById('mirrorBtn');
  const muteBtn = document.getElementById('muteBtn');
  const fullscreenBtn = document.getElementById('fullscreenBtn');
  const openDeviceModalBtn = document.getElementById('openDeviceModalBtn');
  const openStreamModalBtn = document.getElementById('openStreamModalBtn');
  const openInfoModalBtn = document.getElementById('openInfoModalBtn');

  // Modals
  const deviceModal = document.getElementById('deviceModal');
  const streamModal = document.getElementById('streamModal');
  const infoModal = document.getElementById('infoModal');

  // Device Form Fields
  const videoDeviceSelect = document.getElementById('videoDeviceSelect');
  const audioDeviceSelect = document.getElementById('audioDeviceSelect');
  const resolutionSelect = document.getElementById('resolutionSelect');
  const fpsSelect = document.getElementById('fpsSelect');
  const maintainCameraLockCheckbox = document.getElementById('maintainCameraLock');
  const compatModeToggleCheckbox = document.getElementById('compatModeToggle');
  const applyDeviceSettingsBtn = document.getElementById('applyDeviceSettingsBtn');

  // Stream Form Fields
  const streamPresetSelect = document.getElementById('streamPresetSelect');
  const primaryRtmpUrlInput = document.getElementById('primaryRtmpUrl');
  const primaryStreamKeyInput = document.getElementById('primaryStreamKey');
  const toggleKeyVisibilityBtn = document.getElementById('toggleKeyVisibilityBtn');
  const enableSecondaryStreamCheckbox = document.getElementById('enableSecondaryStream');
  const secondaryStreamSection = document.getElementById('secondaryStreamSection');
  const secondaryRtmpUrlInput = document.getElementById('secondaryRtmpUrl');
  const secondaryStreamKeyInput = document.getElementById('secondaryStreamKey');
  const videoBitrateSelect = document.getElementById('videoBitrateSelect');
  const audioBitrateSelect = document.getElementById('audioBitrateSelect');
  const customRelayWsInput = document.getElementById('customRelayWs');
  const saveStreamSettingsBtn = document.getElementById('saveStreamSettingsBtn');

  // Info Modal Fields
  const ipListContainer = document.getElementById('ipListContainer');

  // Services
  const captureManager = new MediaCaptureManager();
  const visualizer = new AudioVisualizer(audioMeterCanvas);
  const streamer = new LiveStreamer();

  // State
  let isLive = false;
  let timerInterval = null;
  let liveStartTime = null;
  let isMuted = false;
  let isMirrored = false;
  let isCameraLocked = true;

  // Local Storage Settings
  const STORAGE_KEY = 'typec_streamer_config';
  const LOCKED_CAM_KEY = 'typec_locked_camera_id';

  const loadSavedSettings = () => {
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
      if (saved.primaryRtmpUrl) primaryRtmpUrlInput.value = saved.primaryRtmpUrl;
      if (saved.primaryStreamKey) primaryStreamKeyInput.value = saved.primaryStreamKey;
      if (saved.enableSecondary) enableSecondaryStreamCheckbox.checked = saved.enableSecondary;
      if (saved.secondaryRtmpUrl) secondaryRtmpUrlInput.value = saved.secondaryRtmpUrl;
      if (saved.secondaryStreamKey) secondaryStreamKeyInput.value = saved.secondaryStreamKey;
      if (saved.videoBitrate) videoBitrateSelect.value = saved.videoBitrate;
      if (saved.audioBitrate) audioBitrateSelect.value = saved.audioBitrate;
      if (saved.customRelayWs) customRelayWsInput.value = saved.customRelayWs;
      if (saved.resolution) resolutionSelect.value = saved.resolution;
      if (saved.fps) fpsSelect.value = saved.fps;
      if (saved.maintainCameraLock !== undefined) {
        isCameraLocked = saved.maintainCameraLock;
        if (maintainCameraLockCheckbox) maintainCameraLockCheckbox.checked = isCameraLocked;
      }
      if (saved.compatMode !== undefined) {
        captureManager.isCompatibilityMode = saved.compatMode;
        if (compatModeToggleCheckbox) compatModeToggleCheckbox.checked = saved.compatMode;
      }

      if (enableSecondaryStreamCheckbox.checked) {
        secondaryStreamSection.style.display = 'flex';
      }

      updateLockBtnUI();
    } catch (e) {}
  };

  const saveSettings = () => {
    const config = {
      primaryRtmpUrl: primaryRtmpUrlInput.value.trim(),
      primaryStreamKey: primaryStreamKeyInput.value.trim(),
      enableSecondary: enableSecondaryStreamCheckbox.checked,
      secondaryRtmpUrl: secondaryRtmpUrlInput.value.trim(),
      secondaryStreamKey: secondaryStreamKeyInput.value.trim(),
      videoBitrate: videoBitrateSelect.value,
      audioBitrate: audioBitrateSelect.value,
      customRelayWs: customRelayWsInput.value.trim(),
      resolution: resolutionSelect.value,
      fps: fpsSelect.value,
      maintainCameraLock: isCameraLocked,
      compatMode: captureManager.isCompatibilityMode
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(config));
  };

  const updateLockBtnUI = () => {
    if (!toggleLockCamBtn) return;
    if (isCameraLocked) {
      toggleLockCamBtn.classList.add('locked');
      toggleLockCamBtn.title = 'Camera Locked (Maintained ON)';
    } else {
      toggleLockCamBtn.classList.remove('locked');
      toggleLockCamBtn.title = 'Camera Unlocked (Auto Mode)';
    }
  };

  if (toggleLockCamBtn) {
    toggleLockCamBtn.addEventListener('click', () => {
      isCameraLocked = !isCameraLocked;
      if (maintainCameraLockCheckbox) maintainCameraLockCheckbox.checked = isCameraLocked;
      updateLockBtnUI();
      saveSettings();
      if (isCameraLocked) {
        if (captureManager.selectedVideoDeviceId) {
          localStorage.setItem(LOCKED_CAM_KEY, captureManager.selectedVideoDeviceId);
        }
        showToast('🔒 Camera Locked: Current camera will be maintained ON!', 'success');
      } else {
        localStorage.removeItem(LOCKED_CAM_KEY);
        showToast('🔓 Camera Unlocked: Auto-selection active.', 'info');
      }
    });
  }

  // Toast Helper
  const showToast = (message, type = 'info') => {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.innerHTML = `<span>${message}</span>`;
    container.appendChild(toast);
    setTimeout(() => {
      toast.style.opacity = '0';
      setTimeout(() => toast.remove(), 300);
    }, 4500);
  };

  // Render Dynamic Camera Grid with ON / OFF Badges
  const renderCameraGrid = (videoDevices, categorized) => {
    if (!cameraTabsGrid) return;
    cameraTabsGrid.innerHTML = '';

    const { front, back, external, others } = categorized;
    const activeDeviceId = captureManager.selectedVideoDeviceId;

    // List all unique cameras to render
    const allCams = [];
    if (front) allCams.push({ device: front, type: 'front', icon: '🤳', title: 'Camera 1', sub: 'Internal Front' });
    if (back) allCams.push({ device: back, type: 'back', icon: '📸', title: 'Camera 2', sub: 'Internal Rear' });
    if (external) {
      allCams.push({ device: external, type: 'external', icon: '🔌', title: 'External Input', sub: external.label || 'Type-C Capture' });
    } else {
      // Placeholder for External if not detected
      allCams.push({ device: null, type: 'external', icon: '🔌', title: 'External Input', sub: 'Type-C (Not Plugged)' });
    }

    if (others && others.length > 0) {
      others.forEach((o, i) => {
        allCams.push({ device: o, type: 'other', icon: '📹', title: `Camera ${3 + i}`, sub: o.label || 'Auxiliary Input' });
      });
    }

    allCams.forEach(item => {
      const btn = document.createElement('button');
      btn.className = 'cam-tab-btn';
      
      const isCurrentActive = item.device && (item.device.deviceId === activeDeviceId);
      if (isCurrentActive) {
        btn.classList.add('active');
      }

      btn.innerHTML = `
        <span class="cam-tab-icon">${item.icon}</span>
        <div class="cam-tab-info">
          <span class="cam-tab-title">${item.title}</span>
          <span class="cam-tab-sub">${item.sub}</span>
        </div>
        <span class="cam-switch-badge ${isCurrentActive ? 'on' : 'off'}">
          <span class="badge-dot"></span>${isCurrentActive ? 'ON' : 'OFF'}
        </span>
      `;

      btn.addEventListener('click', async () => {
        if (!item.device) {
          showToast('⚠️ Type-C capture card not detected! Check USB-C connection and enable OTG in Phone Settings.', 'error');
          return;
        }

        try {
          showToast(`Switching ON: ${item.title}...`, 'info');
          const stream = await captureManager.startStream(item.device.deviceId);
          onStreamUpdated(stream);

          if (isCameraLocked) {
            localStorage.setItem(LOCKED_CAM_KEY, item.device.deviceId);
          }

          showToast(`✅ ${item.title} is now ON & Maintained!`, 'success');
        } catch (err) {
          showToast(`Failed to switch to ${item.title}: ${err.message}`, 'error');
        }
      });

      cameraTabsGrid.appendChild(btn);
    });
  };

  const onStreamUpdated = async (stream) => {
    videoPreview.srcObject = stream;
    visualizer.attachStream(stream);
    await refreshDeviceSelectors();
  };

  // Populate Device Pickers & Diagnostics
  const refreshDeviceSelectors = async () => {
    try {
      const { videoDevices, audioDevices, categorized } = await captureManager.enumerateDevices();
      const { front, back, external } = categorized;

      renderCameraGrid(videoDevices, categorized);

      videoDeviceSelect.innerHTML = '';
      if (videoDevices.length === 0) {
        videoDeviceSelect.innerHTML = '<option value="">No cameras detected</option>';
      } else {
        videoDevices.forEach((device, index) => {
          const opt = document.createElement('option');
          opt.value = device.deviceId;
          let label = device.label || `Camera ${index + 1}`;
          
          if (external && device.deviceId === external.deviceId) {
            opt.textContent = `🔌 ${label} [EXTERNAL CAPTURE CARD]`;
          } else if (front && device.deviceId === front.deviceId) {
            opt.textContent = `🤳 ${label} [CAMERA 1 - FRONT]`;
          } else if (back && device.deviceId === back.deviceId) {
            opt.textContent = `📸 ${label} [CAMERA 2 - REAR]`;
          } else {
            opt.textContent = `📹 ${label}`;
          }

          if (device.deviceId === captureManager.selectedVideoDeviceId) {
            opt.selected = true;
          }
          videoDeviceSelect.appendChild(opt);
        });
      }

      audioDeviceSelect.innerHTML = '';
      if (audioDevices.length === 0) {
        audioDeviceSelect.innerHTML = '<option value="">No audio inputs detected</option>';
      } else {
        audioDevices.forEach((device, index) => {
          const opt = document.createElement('option');
          opt.value = device.deviceId;
          const labelLower = (device.label || '').toLowerCase();
          const isCardAudio = labelLower.match(/capture|cam link|usb|hdmi|digital/);
          opt.textContent = `${device.label || `Audio Input ${index + 1}`} ${isCardAudio ? '🔊 (HDMI In)' : '🎙️ (Microphone)'}`;
          if (device.deviceId === captureManager.selectedAudioDeviceId) {
            opt.selected = true;
          }
          audioDeviceSelect.appendChild(opt);
        });
      }

      // Populate Raw Diagnostics Box
      const rawDevicesBox = document.getElementById('rawDevicesBox');
      if (rawDevicesBox) {
        rawDevicesBox.innerHTML = '';
        if (videoDevices.length === 0) {
          rawDevicesBox.innerHTML = '<span style="color:#ef4444;">No camera devices detected.</span>';
        } else {
          videoDevices.forEach((d, idx) => {
            const item = document.createElement('div');
            item.style.padding = '4px 0';
            item.style.borderBottom = '1px solid rgba(255,255,255,0.05)';
            item.style.cursor = 'pointer';
            const isSel = d.deviceId === captureManager.selectedVideoDeviceId;
            item.innerHTML = `
              <span style="color:${isSel ? '#10b981' : '#38bdf8'}; font-weight:${isSel ? 'bold' : 'normal'};">
                ${isSel ? '▶ [ON] ' : '[OFF] '}#${idx + 1}: ${d.label || `Camera ${idx + 1} (Unlabeled)`}
              </span>
              <div style="font-size:0.65rem; color:#64748b;">ID: ${d.deviceId.slice(0, 16)}... (tap to turn ON)</div>
            `;
            item.addEventListener('click', async () => {
              showToast(`Turning ON device: ${d.label || `#${idx + 1}`}`, 'info');
              const s = await captureManager.startStream(d.deviceId);
              onStreamUpdated(s);
              if (isCameraLocked) {
                localStorage.setItem(LOCKED_CAM_KEY, d.deviceId);
              }
            });
            rawDevicesBox.appendChild(item);
          });
        }
      }
    } catch (e) {
      console.warn('Could not enumerate devices:', e);
    }
  };

  // Diagnostics & WebUSB Handlers
  const refreshDevicesBtn = document.getElementById('refreshDevicesBtn');
  if (refreshDevicesBtn) {
    refreshDevicesBtn.addEventListener('click', async () => {
      showToast('Refreshing camera & USB list...', 'info');
      await refreshDeviceSelectors();
    });
  }

  const scanWebUsbBtn = document.getElementById('scanWebUsbBtn');
  if (scanWebUsbBtn) {
    scanWebUsbBtn.addEventListener('click', async () => {
      if (!navigator.usb) {
        showToast('WebUSB is not supported in this browser. Please use Chrome on Android.', 'error');
        return;
      }
      try {
        showToast('Opening USB device picker...', 'info');
        const device = await navigator.usb.requestDevice({ filters: [] });
        const devName = device.productName || device.manufacturerName || `USB Device (ID: ${device.vendorId})`;
        showToast(`🔌 USB Device Paired: ${devName}!`, 'success');
        await refreshDeviceSelectors();
      } catch (err) {
        if (err.name !== 'NotFoundError') {
          showToast(`USB Scan: ${err.message}`, 'error');
        }
      }
    });
  }

  // Initialize Video Stream
  const initCapture = async (requestedVideoId = null, requestedAudioId = null) => {
    try {
      captureManager.resolution = resolutionSelect.value;
      captureManager.fps = parseInt(fpsSelect.value, 10);

      await captureManager.enumerateDevices();

      // Check if user locked a specific camera previously! ("Maintain this")
      if (!requestedVideoId && isCameraLocked) {
        const lockedId = localStorage.getItem(LOCKED_CAM_KEY);
        if (lockedId) {
          const exists = captureManager.videoDevices.find(d => d.deviceId === lockedId);
          if (exists) {
            console.log('[MediaCapture] Restoring maintained/locked camera:', exists.label);
            requestedVideoId = lockedId;
          }
        }
      }

      // If no locked ID, check if external capture card is plugged in
      if (!requestedVideoId) {
        const captureCard = captureManager.findLikelyCaptureCard();
        if (captureCard) {
          requestedVideoId = captureCard.deviceId;
          const captureAudio = captureManager.findLikelyCaptureAudio();
          if (captureAudio) requestedAudioId = captureAudio.deviceId;
        }
      }

      const stream = await captureManager.startStream(requestedVideoId, requestedAudioId);
      videoPreview.srcObject = stream;
      placeholderView.style.display = 'none';
      videoPreview.style.display = 'block';

      visualizer.attachStream(stream);
      await refreshDeviceSelectors();

      const activeTrack = stream.getVideoTracks()[0];
      const activeLabel = activeTrack ? activeTrack.label : 'Camera';
      showToast(`Active: ${activeLabel}`, 'success');

      updateStatusBadge('standby', 'READY');
    } catch (err) {
      console.error('Camera init error:', err);
      if (!window.isSecureContext) {
        showToast('⚠️ Mobile browser blocks camera over HTTP! Use HTTPS link.', 'error');
      } else {
        showToast(`Camera error: ${err.message}`, 'error');
      }
    }
  };

  // One-tap Switch Camera button
  if (switchCameraBtn) {
    switchCameraBtn.addEventListener('click', async () => {
      try {
        const stream = await captureManager.cycleNextVideoDevice();
        if (stream) {
          onStreamUpdated(stream);
          if (isCameraLocked && captureManager.selectedVideoDeviceId) {
            localStorage.setItem(LOCKED_CAM_KEY, captureManager.selectedVideoDeviceId);
          }
          const activeTrack = stream.getVideoTracks()[0];
          showToast(`Switched to: ${activeTrack ? activeTrack.label : 'Next Camera'}`, 'info');
        } else {
          showToast('Only 1 camera found.', 'info');
        }
      } catch (err) {
        showToast(`Switch failed: ${err.message}`, 'error');
      }
    });
  }

  // Handle USB plug/unplug event dynamically
  captureManager.onDevicesChanged = async () => {
    await refreshDeviceSelectors();
    // Only auto-switch if camera is not locked
    if (!isCameraLocked) {
      const card = captureManager.findLikelyCaptureCard();
      if (card && card.deviceId !== captureManager.selectedVideoDeviceId) {
        showToast(`🔌 Type-C Capture Card detected: ${card.label}!`, 'success');
        await initCapture(card.deviceId);
      }
    }
  };

  // Modal Triggers
  const openModal = (modal) => modal.classList.add('open');
  const closeModal = (modal) => modal.classList.remove('open');

  document.querySelectorAll('.close-modal-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const modal = e.target.closest('.modal-overlay');
      if (modal) closeModal(modal);
    });
  });

  openDeviceModalBtn.addEventListener('click', async () => {
    await refreshDeviceSelectors();
    openModal(deviceModal);
  });

  openStreamModalBtn.addEventListener('click', () => {
    openModal(streamModal);
  });

  openInfoModalBtn.addEventListener('click', async () => {
    openModal(infoModal);
    try {
      const res = await fetch('/api/info');
      const data = await res.json();
      ipListContainer.innerHTML = '';
      if (data.localIps && data.localIps.length > 0) {
        data.localIps.forEach(item => {
          const httpsPort = data.httpsPort || 3443;
          const badge = document.createElement('div');
          badge.className = 'ip-badge';
          badge.style.flexDirection = 'column';
          badge.style.alignItems = 'flex-start';
          badge.style.gap = '6px';
          badge.innerHTML = `
            <div style="display:flex; justify-content:space-between; width:100%; align-items:center;">
              <span style="font-weight:bold; color:#10b981;">🔒 HTTPS (Mobile Camera)</span>
              <button class="copy-btn" data-url="https://${item.address}:${httpsPort}">Copy</button>
            </div>
            <a href="https://${item.address}:${httpsPort}" style="color:#38bdf8; word-break:break-all; text-decoration:none;">https://${item.address}:${httpsPort}</a>
            <span style="font-size:0.7rem; color:#94a3b8;">Accept the certificate warning in your phone browser to enable camera.</span>
          `;
          ipListContainer.appendChild(badge);
        });

        ipListContainer.querySelectorAll('.copy-btn').forEach(btn => {
          btn.addEventListener('click', () => {
            navigator.clipboard.writeText(btn.dataset.url);
            showToast('URL copied to clipboard!', 'success');
          });
        });
      } else {
        ipListContainer.innerHTML = '<div class="hint">No external LAN interfaces found.</div>';
      }
    } catch (e) {
      ipListContainer.innerHTML = '<div class="hint">Could not fetch server LAN IP.</div>';
    }
  });

  applyDeviceSettingsBtn.addEventListener('click', async () => {
    const vId = videoDeviceSelect.value;
    const aId = audioDeviceSelect.value;
    captureManager.resolution = resolutionSelect.value;
    captureManager.fps = parseInt(fpsSelect.value, 10);
    captureManager.isCompatibilityMode = compatModeToggleCheckbox ? compatModeToggleCheckbox.checked : false;
    isCameraLocked = maintainCameraLockCheckbox ? maintainCameraLockCheckbox.checked : true;

    closeModal(deviceModal);

    try {
      const stream = await captureManager.startStream(vId, aId);
      onStreamUpdated(stream);

      if (isCameraLocked && vId) {
        localStorage.setItem(LOCKED_CAM_KEY, vId);
      } else {
        localStorage.removeItem(LOCKED_CAM_KEY);
      }

      saveSettings();
      updateLockBtnUI();
      const activeTrack = stream.getVideoTracks()[0];
      showToast(`Selected & Maintained: ${activeTrack ? activeTrack.label : 'Camera'}`, 'success');
    } catch (err) {
      showToast(`Failed to switch device: ${err.message}`, 'error');
    }
  });

  saveStreamSettingsBtn.addEventListener('click', () => {
    saveSettings();
    closeModal(streamModal);
    showToast('Streaming settings saved', 'success');
  });

  streamPresetSelect.addEventListener('change', () => {
    const val = streamPresetSelect.value;
    if (val === 'youtube') {
      primaryRtmpUrlInput.value = 'rtmp://a.rtmp.youtube.com/live2';
    } else if (val === 'twitch') {
      primaryRtmpUrlInput.value = 'rtmp://live.twitch.tv/app';
    } else if (val === 'facebook') {
      primaryRtmpUrlInput.value = 'rtmps://live-api-s.facebook.com:443/rtmp';
    }
  });

  toggleKeyVisibilityBtn.addEventListener('click', () => {
    if (primaryStreamKeyInput.type === 'password') {
      primaryStreamKeyInput.type = 'text';
      toggleKeyVisibilityBtn.textContent = 'Hide';
    } else {
      primaryStreamKeyInput.type = 'password';
      toggleKeyVisibilityBtn.textContent = 'Show';
    }
  });

  enableSecondaryStreamCheckbox.addEventListener('change', () => {
    secondaryStreamSection.style.display = enableSecondaryStreamCheckbox.checked ? 'flex' : 'none';
  });

  mirrorBtn.addEventListener('click', () => {
    isMirrored = !isMirrored;
    videoPreview.classList.toggle('mirrored', isMirrored);
    mirrorBtn.classList.toggle('active', isMirrored);
  });

  muteBtn.addEventListener('click', () => {
    if (!captureManager.currentStream) return;
    isMuted = !isMuted;
    captureManager.currentStream.getAudioTracks().forEach(t => t.enabled = !isMuted);
    muteBtn.classList.toggle('active', isMuted);
    showToast(isMuted ? 'Audio muted' : 'Audio unmuted', 'info');
  });

  fullscreenBtn.addEventListener('click', () => {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().catch(() => {});
    } else {
      document.exitFullscreen().catch(() => {});
    }
  });

  startPreviewBtn.addEventListener('click', () => {
    initCapture();
  });

  const updateStatusBadge = (state, text) => {
    statusBadge.className = `status-badge ${state}`;
    statusText.textContent = text;
  };

  const startTimer = () => {
    liveStartTime = Date.now();
    liveTimerChip.style.display = 'flex';
    timerInterval = setInterval(() => {
      const elapsed = Math.floor((Date.now() - liveStartTime) / 1000);
      const hrs = String(Math.floor(elapsed / 3600)).padStart(2, '0');
      const mins = String(Math.floor((elapsed % 3600) / 60)).padStart(2, '0');
      const secs = String(elapsed % 60).padStart(2, '0');
      liveTimer.textContent = `${hrs}:${mins}:${secs}`;
    }, 1000);
  };

  const stopTimer = () => {
    if (timerInterval) {
      clearInterval(timerInterval);
      timerInterval = null;
    }
    liveTimerChip.style.display = 'none';
    liveTimer.textContent = '00:00:00';
  };

  broadcastBtn.addEventListener('click', async () => {
    if (isLive) {
      if (confirm('Are you sure you want to stop the live stream?')) {
        streamer.stopBroadcast();
      }
      return;
    }

    if (!captureManager.currentStream) {
      await initCapture();
      if (!captureManager.currentStream) {
        showToast('Please enable camera input first.', 'error');
        return;
      }
    }

    const rtmpServer = primaryRtmpUrlInput.value.trim();
    const streamKey = primaryStreamKeyInput.value.trim();

    if (!rtmpServer || !streamKey) {
      showToast('Please enter your RTMP Server and Stream Key in Settings.', 'error');
      openModal(streamModal);
      return;
    }

    const fullPrimaryUrl = rtmpServer.endsWith('/') ? `${rtmpServer}${streamKey}` : `${rtmpServer}/${streamKey}`;

    let fullSecondaryUrl = null;
    if (enableSecondaryStreamCheckbox.checked) {
      const sServer = secondaryRtmpUrlInput.value.trim();
      const sKey = secondaryStreamKeyInput.value.trim();
      if (sServer && sKey) {
        fullSecondaryUrl = sServer.endsWith('/') ? `${sServer}${sKey}` : `${sServer}/${sKey}`;
      }
    }

    broadcastBtn.disabled = true;
    broadcastBtnText.textContent = 'Connecting...';

    const streamConfig = {
      relayWsUrl: customRelayWsInput.value.trim() || null,
      rtmpUrl: fullPrimaryUrl,
      secondaryRtmpUrl: fullSecondaryUrl,
      videoBitrate: videoBitrateSelect.value,
      audioBitrate: audioBitrateSelect.value,
      fps: parseInt(fpsSelect.value, 10)
    };

    try {
      await streamer.startBroadcast(captureManager.currentStream, streamConfig);
      saveSettings();
    } catch (err) {
      broadcastBtn.disabled = false;
      broadcastBtnText.textContent = 'GO LIVE';
      showToast(`Streaming failed: ${err.message}`, 'error');
    }
  });

  streamer.onStatusChange = (status) => {
    broadcastBtn.disabled = false;
    if (status === 'live') {
      isLive = true;
      broadcastBtn.classList.add('is-live');
      broadcastBtnText.textContent = 'STOP STREAM';
      updateStatusBadge('live', 'LIVE');
      startTimer();
      requestWakeLock();
      showToast('🔴 You are now streaming LIVE to RTMP / YouTube!', 'success');
    } else {
      isLive = false;
      broadcastBtn.classList.remove('is-live');
      broadcastBtnText.textContent = 'GO LIVE';
      updateStatusBadge('standby', 'READY');
      stopTimer();
      releaseWakeLock();
      statsFps.textContent = '0';
      statsBitrate.textContent = '0 kbps';
      showToast('Stream finished', 'info');
    }
  };

  // Screen WakeLock
  let wakeLock = null;
  const requestWakeLock = async () => {
    if ('wakeLock' in navigator) {
      try {
        wakeLock = await navigator.wakeLock.request('screen');
      } catch (e) {}
    }
  };
  const releaseWakeLock = () => {
    if (wakeLock) {
      wakeLock.release().catch(() => {});
      wakeLock = null;
    }
  };

  // Battery & Charging Power Monitor
  const batteryChip = document.getElementById('batteryChip');
  const batteryIcon = document.getElementById('batteryIcon');
  const batteryPercent = document.getElementById('batteryPercent');

  const initBatteryMonitor = async () => {
    if ('getBattery' in navigator && batteryChip) {
      try {
        const battery = await navigator.getBattery();
        const updateBatteryUI = () => {
          batteryChip.style.display = 'inline-flex';
          const pct = Math.round(battery.level * 100);
          batteryPercent.textContent = `${pct}%`;
          if (battery.charging) {
            batteryIcon.textContent = '⚡';
            batteryChip.className = 'battery-chip charging';
            batteryChip.title = 'Phone is Charging via Type-C';
          } else {
            batteryIcon.textContent = '🔋';
            batteryChip.className = pct <= 20 ? 'battery-chip low-battery' : 'battery-chip';
            batteryChip.title = 'Running on Battery (Not Charging)';
            if (pct <= 20 && isLive) {
              showToast(`⚠️ Low Battery (${pct}%)! Connect a Type-C PD charger splitter.`, 'error');
            }
          }
        };

        battery.addEventListener('levelchange', updateBatteryUI);
        battery.addEventListener('chargingchange', updateBatteryUI);
        updateBatteryUI();
      } catch (e) {}
    }
  };
  initBatteryMonitor();

  streamer.onStats = (stats) => {
    if (stats.fps) statsFps.textContent = Math.round(stats.fps);
    if (stats.bitrate) statsBitrate.textContent = stats.bitrate;
  };

  streamer.onError = (err) => {
    showToast(`Streaming error: ${err}`, 'error');
  };

  captureManager.onDeviceDisconnected = (kind) => {
    showToast(`⚠️ ${kind === 'video' ? 'Capture card' : 'Audio input'} was disconnected!`, 'error');
    if (isLive) {
      streamer.stopBroadcast();
    }
    initCapture();
  };

  loadSavedSettings();
  initCapture();

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('./sw.js').catch(err => {
        console.log('[SW] Registration failed:', err);
      });
    });
  }
});
