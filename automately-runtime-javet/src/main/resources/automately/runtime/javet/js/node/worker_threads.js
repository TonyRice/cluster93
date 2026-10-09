// A stand-in for Node's `worker_threads` (D-123): the main thread's view, so libraries that read it when they load
// (pino, thread-stream, Fastify's logger) load. Scripts start no threads: new Worker() throws ERR_NOT_SUPPORTED.
import { EventEmitter } from 'node:events';

function unsupported(what) {
  const err = new Error(`${what} isn't available in the isolate: a script runs on one thread; split work into jobs instead`);
  err.code = 'ERR_NOT_SUPPORTED';
  return err;
}

export const isMainThread = true;
export const parentPort = null;
export const workerData = null;
export const threadId = 0;
export const resourceLimits = {};
export const SHARE_ENV = Symbol.for('nodejs.worker_threads.SHARE_ENV');
export class Worker extends EventEmitter {
  constructor() { super(); throw unsupported('worker_threads.Worker'); }
}
export const MessageChannel = globalThis.MessageChannel;
export const MessagePort = globalThis.MessagePort;
export const BroadcastChannel = globalThis.BroadcastChannel;
export function markAsUntransferable() {}
export function isMarkedAsUntransferable() { return false; }
export function markAsUncloneable() {}
export function moveMessagePortToContext() { throw unsupported('worker_threads.moveMessagePortToContext'); }
export function receiveMessageOnPort() { return undefined; }
const environment = new Map();
export function getEnvironmentData(key) { return environment.get(key); }
export function setEnvironmentData(key, value) { if (value === undefined) environment.delete(key); else environment.set(key, value); }

export default {
  isMainThread, parentPort, workerData, threadId, resourceLimits, SHARE_ENV, Worker, MessageChannel, MessagePort, BroadcastChannel,
  markAsUntransferable, isMarkedAsUntransferable, markAsUncloneable, moveMessagePortToContext, receiveMessageOnPort,
  getEnvironmentData, setEnvironmentData,
};
