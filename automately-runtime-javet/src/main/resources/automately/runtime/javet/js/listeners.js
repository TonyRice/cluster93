// Listeners (bus subscriptions and handlers, HTTP servers): the handlers stay here under an id; Java delivers events
// to that id. `register(id)` is a binding call that resolves once events can arrive.
const listeners = new Map();
let nextListener = 1;
const listen = (register, handler) => {
  const listenerId = nextListener++;
  listeners.set(listenerId, handler);
  const stop = () => { if (listeners.delete(listenerId)) host.unlisten(listenerId); };
  return register(listenerId).then(() => stop, (e) => { listeners.delete(listenerId); throw e; });
};

// Called by the event loop with an inbound event; `replyId` is 0 unless the sender waits for an answer.
const deliver = (listenerId, payloadJson, replyId) => {
  const handler = listeners.get(listenerId);
  const answer = (ok, value) => { if (replyId) host.reply(replyId, ok, JSON.stringify(value === undefined ? null : value)); };
  const failed = (e) => {
    const message = String(e && e.message ? e.message : e);
    if (replyId) answer(false, { status: 500, message });
    else host.log('error', `Uncaught error in listener: ${errorText(e)}`);
  };
  if (!handler) { if (replyId) answer(false, { status: 503, message: 'No longer listening' }); return; }
  try {
    Promise.resolve(handler(JSON.parse(payloadJson))).then((value) => answer(true, value), failed);
  } catch (e) {
    failed(e);
  }
};
