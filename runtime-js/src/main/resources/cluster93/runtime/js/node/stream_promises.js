// Node's stream/promises, from the vendored stream (D-100).
import stream from 'node:stream';
const promises = stream.promises;
export default promises;
export const { pipeline, finished } = promises;
