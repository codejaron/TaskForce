const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('taskforceDesktop', {
  isDesktop: true,
  isAvailable: () => ipcRenderer.invoke('desktop:is-available'),
  pickProjectDirectory: (sessionId) =>
    ipcRenderer.invoke('desktop:pick-project-directory', { sessionId }),
  getSelectedProjectDirectory: (sessionId) =>
    ipcRenderer.invoke('desktop:get-selected-project-directory', { sessionId })
});
