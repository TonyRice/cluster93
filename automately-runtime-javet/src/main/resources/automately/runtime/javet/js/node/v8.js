// A stand-in for Node's `v8` (D-123): what libraries read when they load (Koa asks whether a startup snapshot is being
// built). The isolate doesn't expose V8's heap or its serializer: statistics are zeros, as process.memoryUsage()'s,
// and serialize, deserialize, heap snapshots and flags throw ERR_NOT_SUPPORTED.
function unsupported(what) {
  const err = new Error(`${what} isn't available in the isolate`);
  err.code = 'ERR_NOT_SUPPORTED';
  return err;
}
const notBuilding = () => {
  const err = new Error('Operation cannot be invoked when not building startup snapshot');
  err.code = 'ERR_NOT_BUILDING_SNAPSHOT';
  return err;
};

export const startupSnapshot = Object.freeze({
  isBuildingSnapshot: () => false,
  addSerializeCallback() { throw notBuilding(); },
  addDeserializeCallback() { throw notBuilding(); },
  setDeserializeMainFunction() { throw notBuilding(); },
});

export function getHeapStatistics() {
  return { total_heap_size: 0, total_heap_size_executable: 0, total_physical_size: 0, total_available_size: 0, used_heap_size: 0,
    heap_size_limit: 0, malloced_memory: 0, peak_malloced_memory: 0, does_zap_garbage: 0, number_of_native_contexts: 1,
    number_of_detached_contexts: 0, total_global_handles_size: 0, used_global_handles_size: 0, external_memory: 0 };
}
export function getHeapSpaceStatistics() { return []; }
export function getHeapCodeStatistics() { return { code_and_metadata_size: 0, bytecode_and_metadata_size: 0, external_script_source_size: 0, cpu_profiler_metadata_size: 0 }; }
export function cachedDataVersionTag() { return 0; }
export function isStringOneByteRepresentation(content) { return !/[^\u0000-ÿ]/.test(String(content)); }
export function setFlagsFromString() { throw unsupported('v8.setFlagsFromString()'); }
export function serialize() { throw unsupported('v8.serialize()'); }
export function deserialize() { throw unsupported('v8.deserialize()'); }
export function getHeapSnapshot() { throw unsupported('v8.getHeapSnapshot()'); }
export function writeHeapSnapshot() { throw unsupported('v8.writeHeapSnapshot()'); }
export function takeCoverage() {}
export function stopCoverage() {}
export class Serializer { constructor() { throw unsupported('v8.Serializer'); } }
export class Deserializer { constructor() { throw unsupported('v8.Deserializer'); } }

export default { startupSnapshot, getHeapStatistics, getHeapSpaceStatistics, getHeapCodeStatistics, cachedDataVersionTag,
  isStringOneByteRepresentation, setFlagsFromString, serialize, deserialize, getHeapSnapshot, writeHeapSnapshot, takeCoverage,
  stopCoverage, Serializer, Deserializer };
