'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {permissionOptions,classifyLimits,normalizeContext}=require('../../app/src/main/assets/task-notifications/remote-options.cjs');
test('permissions are closed and full access is never the default',()=>{
 assert.equal(permissionOptions('').resume.sandbox,'workspace-write');
 assert.equal(permissionOptions('readonly').turn.sandboxPolicy.type,'readOnly');
 assert.equal(permissionOptions('review').turn.approvalsReviewer,'auto_review');
 assert.equal(permissionOptions('full').turn.sandboxPolicy.type,'dangerFullAccess');
 assert.equal(permissionOptions('full').turn.approvalPolicy,'never');
 assert.throws(()=>permissionOptions('arbitrary'),/INVALID_PERMISSION/);
});
test('window classification follows duration, rejects duplicates and model buckets',()=>{
 const weekly={usedPercent:90,windowDurationMins:10080,resetsAt:12345};
 const five={usedPercent:20,windowDurationMins:300,resetsAt:45678};
 assert.deepEqual(classifyLimits({rateLimits:{primary:weekly,secondary:five}}),{five_hour:{used_percent:20,duration_mins:300,reset_at:45678},weekly:{used_percent:90,duration_mins:10080,reset_at:12345}});
 assert.deepEqual(classifyLimits({rateLimits:{primary:weekly,secondary:weekly}}),{five_hour:null,weekly:null});
 assert.deepEqual(classifyLimits({rateLimits:{limitId:'model-only',primary:five},rateLimitsByLimitId:{'gpt-reserve':{primary:weekly}}}),{five_hour:null,weekly:null});
 assert.equal(classifyLimits({rateLimits:{primary:{...five,usedPercent:NaN}}}).five_hour,null);
});
test('context reads the last request, not cumulative conversation tokens',()=>{
 assert.deepEqual(normalizeContext({last:{totalTokens:60000},total:{totalTokens:800000},modelContextWindow:260000}),{used_tokens:60000,window_tokens:260000});
 assert.equal(normalizeContext({last:{totalTokens:-1},modelContextWindow:260000}),null);
});
