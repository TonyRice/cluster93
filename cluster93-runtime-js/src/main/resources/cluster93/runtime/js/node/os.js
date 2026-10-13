// A stand-in for Node's `os` (D-103), describing the job rather than the machine: never the host's name, users,
// network or load. The temporary folder is the job's /tmp, there's one CPU (an isolate runs on one thread), and the
// memory is the isolate's heap limit, which the runtime puts here in place of the marker below.

const HEAP_BYTES = /* c93:heap */ 0;
const started = Date.now();

export const EOL = '\n';
export const devNull = '/dev/null';
export const arch = () => 'x64';
export const machine = () => 'x86_64';
export const platform = () => 'linux';
export const type = () => 'Linux';
export const release = () => '';
export const version = () => '';
export const endianness = () => 'LE';
export const hostname = () => 'localhost';
export const homedir = () => '/tmp';
export const tmpdir = () => '/tmp';
export const totalmem = () => HEAP_BYTES;
export const freemem = () => HEAP_BYTES;
export const uptime = () => Math.floor((Date.now() - started) / 1000);
export const loadavg = () => [0, 0, 0];
export const availableParallelism = () => 1;
export const cpus = () => [{ model: 'Cluster93 isolate', speed: 0, times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 } }];
export const networkInterfaces = () => ({});
export const userInfo = () => ({ uid: -1, gid: -1, username: 'c93', homedir: '/tmp', shell: null });
export const getPriority = () => 0;
export const setPriority = () => {
  throw Object.assign(new Error('setPriority is not available in the isolate'), { code: 'ERR_FEATURE_UNAVAILABLE_ON_PLATFORM' });
};
export const constants = Object.freeze({
  signals: Object.freeze({ SIGHUP: 1, SIGINT: 2, SIGQUIT: 3, SIGKILL: 9, SIGTERM: 15 }),
  errno: Object.freeze({}),
  priority: Object.freeze({ PRIORITY_LOW: 19, PRIORITY_BELOW_NORMAL: 10, PRIORITY_NORMAL: 0, PRIORITY_ABOVE_NORMAL: -7, PRIORITY_HIGH: -14,
    PRIORITY_HIGHEST: -20 }),
});

export default {
  EOL, devNull, arch, machine, platform, type, release, version, endianness, hostname, homedir, tmpdir, totalmem, freemem, uptime,
  loadavg, availableParallelism, cpus, networkInterfaces, userInfo, getPriority, setPriority, constants,
};
