'use strict';
// Compatibility entry: computers recovered in 2026-10 point the shared-server task here.
// shared-server.cjs now reuses a ready listener itself; keep both task targets working.
if(require.main===module)require('./shared-server.cjs').run(__dirname);
