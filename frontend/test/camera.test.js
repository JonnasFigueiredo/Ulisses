import { afterEach, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { captureChallengeMotion } from '../src/camera.js';

const originalFetch = globalThis.fetch;
const video = { videoWidth: 640, videoHeight: 480 };
const messages = { noFace: 'rosto', center: 'centro', move: 'mova', hold: 'segure', timeout: 'tempo esgotado' };

beforeEach(() => {
  globalThis.localStorage = { getItem: () => null };
  globalThis.window = new EventTarget();
  globalThis.document = {
    createElement: () => ({
      getContext: () => ({ drawImage() {} }),
      toBlob: (callback) => callback(new Blob(['frame'], { type: 'image/jpeg' })),
    }),
  };
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  delete globalThis.document;
  delete globalThis.window;
  delete globalThis.localStorage;
});

function servePoses(deviations) {
  let calls = 0;
  globalThis.fetch = async () => Response.json({
    faceDetected: true, multipleFaces: false, faceHeight: 0.5, centerX: 0.5, centerY: 0.5,
    noseRatio: deviations[Math.min(calls++, deviations.length - 1)], noseVertical: 0.5,
  });
  return () => calls;
}

const options = { direction: 'LEFT', baselines: { h: 0, v: 0.5 }, messages, delayMs: 0, timeoutMs: 2000 };

test('esperar no centro por mais de 16 observacoes nao encerra a captura', async () => {
  const calls = servePoses([...Array(20).fill(0), 0.08, 0.15, 0.16, 0.17]);
  const result = await captureChallengeMotion(video, options);
  assert.equal(calls(), 24);
  assert.equal(result.frames.length, 6);
});

test('trajetoria longa reinicia no centro e preserva limite de frames', async () => {
  const calls = servePoses([0, ...Array(16).fill(0.08), 0, 0, 0.08, 0.15, 0.16, 0.17]);
  const result = await captureChallengeMotion(video, options);
  assert.ok(calls() > 16);
  assert.equal(result.frames.length, 6);
});

test('pose ja virada nunca substitui o inicio neutro', async () => {
  servePoses([0.2]);
  await assert.rejects(captureChallengeMotion(video, { ...options, timeoutMs: 25 }), /tempo esgotado/);
});
