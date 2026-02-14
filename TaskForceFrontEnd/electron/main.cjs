const path = require('path');
const fs = require('fs');
const { app, BrowserWindow, dialog, ipcMain } = require('electron');

const DEFAULT_RENDERER_URL = 'http://localhost:5173';

let mainWindow = null;
const workspaceBySession = new Map();

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 1080,
    minHeight: 700,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  loadRenderer(mainWindow).catch((error) => {
    const safeMessage = String(error && error.message ? error.message : error).replace(/</g, '&lt;');
    const html = `
      <html>
        <body style="font-family: -apple-system, sans-serif; padding: 28px; line-height: 1.6;">
          <h2>TaskForce Desktop 启动失败</h2>
          <p>未能加载前端页面。</p>
          <p>建议先运行：</p>
          <pre style="background:#f6f6f6;padding:12px;border-radius:8px;">npm run dev</pre>
          <p>然后重新运行：</p>
          <pre style="background:#f6f6f6;padding:12px;border-radius:8px;">npm run desktop</pre>
          <p style="color:#666;">错误信息：${safeMessage}</p>
        </body>
      </html>
    `;
    mainWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
  });

  if (!app.isPackaged) {
    mainWindow.webContents.openDevTools({ mode: 'detach' });
  }
}

async function loadRenderer(window) {
  const rendererUrl = process.env.TASKFORCE_RENDERER_URL;
  if (rendererUrl) {
    await window.loadURL(rendererUrl);
    return;
  }

  const distIndexPath = path.join(__dirname, '..', 'dist', 'index.html');
  const devServerUp = await isDevServerReachable(DEFAULT_RENDERER_URL);

  if (devServerUp) {
    await window.loadURL(DEFAULT_RENDERER_URL);
    return;
  }

  if (fs.existsSync(distIndexPath)) {
    await window.loadFile(distIndexPath);
    return;
  }

  throw new Error(`Dev server unreachable (${DEFAULT_RENDERER_URL}) and dist build missing`);
}

async function isDevServerReachable(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 1500);
  try {
    const response = await fetch(url, { method: 'GET', signal: controller.signal });
    return response.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

async function pickProjectDirectory(sessionId) {
  const defaultPath = sessionId ? workspaceBySession.get(sessionId) : undefined;
  const result = await dialog.showOpenDialog(mainWindow, {
    title: 'Select Project Directory',
    defaultPath,
    properties: ['openDirectory', 'createDirectory']
  });

  if (result.canceled || result.filePaths.length === 0) {
    return { canceled: true };
  }

  const selectedPath = result.filePaths[0];
  if (sessionId) {
    workspaceBySession.set(sessionId, selectedPath);
  }
  return {
    canceled: false,
    path: selectedPath
  };
}

function registerIpcHandlers() {
  ipcMain.handle('desktop:is-available', () => true);

  ipcMain.handle('desktop:pick-project-directory', async (_event, payload = {}) => {
    const sessionId = typeof payload.sessionId === 'string' ? payload.sessionId : undefined;
    return await pickProjectDirectory(sessionId);
  });

  ipcMain.handle('desktop:get-selected-project-directory', (_event, payload = {}) => {
    const sessionId = typeof payload.sessionId === 'string' ? payload.sessionId : '';
    if (!sessionId) {
      return null;
    }
    return workspaceBySession.get(sessionId) ?? null;
  });
}

app.whenReady().then(() => {
  registerIpcHandlers();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
