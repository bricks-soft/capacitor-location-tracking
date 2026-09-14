const assert = require('node:assert/strict');
const { LocationTracking } = require('../dist/plugin.cjs.js');
const methods = ['ready', 'configure', 'start', 'stop', 'getState', 'getCurrentPosition', 'sync', 'getCount',
  'destroyLocations', 'requestPermissions', 'getProviderState', 'setAuthorization', 'clearAuthorization',
  'setConfig', 'log', 'getDiagnostics', 'clearDiagnostics', 'addListener', 'removeAllListeners'];
(async () => {
  for (const method of methods) await assert.rejects(() => LocationTracking[method]({}, () => {}), { code: 'UNIMPLEMENTED' });
  console.log(`PASS: ${methods.length} web methods reject UNIMPLEMENTED through the published CJS bridge`);
})().catch(error => { console.error(error); process.exitCode = 1; });
