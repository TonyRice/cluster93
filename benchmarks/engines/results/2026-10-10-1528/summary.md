**GraalVM Environment:** Oracle GraalVM (native-image 25.0.4.1.1 2026-08-18)

CPU during the run: package 55°C median, 66°C max; mean clock 2352 MHz median, 835 MHz lowest (164 samples, every 5 s).

## Start-up (native-image worker, first script answered)

| engine | ms to first script (median of 3) | private MB | RSS MB |
|---|---|---|---|
| graaljs | 698 | 17 | 118 |

## Memory with idle services

| engine | 0 idle: private / RSS MB | 20 idle | 50 idle | private MB per idle service |
|---|---|---|---|---|
| graaljs | 3 / 67 | 442 / 597 | 424 / 581 | -0.6 |

## Warmed-up iteration time (median ms; lower is better)

| workload | graaljs median ms | javet median ms |
|---|---|---|
| buffer-codec | 92.17 | - |
| crypto-hash | 114.027 | - |
| deltablue | 109.791 | - |
| events | 68.413 | - |
| json-roundtrip | 834.307 | - |
| kv-loop | 189.283 | - |
| map-set-churn | 261.961 | - |
| promise-timers | 66.152 | - |
| regex-tokenize | 26.646 | - |
| richards | 61.077 | - |
| service-router | 112.375 | - |
| sha256-js | 78.073 | - |
| sort | 263.57 | - |
| stream-pipeline | 330.341 | - |
| string-build | 39.2 | - |
| text-codec | 98.935 | - |
| url-parse | 38.006 | - |
| zlib-roundtrip | 134.158 | - |

Ratios are against javet (V8): above 1 is slower than V8.

## Tail latency once warm (p99 ms)

| workload | graaljs | javet |
|---|---|---|
| buffer-codec | 514.888 | - |
| crypto-hash | 127.487 | - |
| deltablue | 129.737 | - |
| events | 79.641 | - |
| json-roundtrip | 889.593 | - |
| kv-loop | 250.774 | - |
| map-set-churn | 497.986 | - |
| promise-timers | 201.31 | - |
| regex-tokenize | 35.236 | - |
| richards | 63.959 | - |
| service-router | 126.298 | - |
| sha256-js | 82.388 | - |
| sort | 319.301 | - |
| stream-pipeline | 387.863 | - |
| string-build | 194.89 | - |
| text-codec | 152.185 | - |
| url-parse | 62.393 | - |
| zlib-roundtrip | 142.498 | - |

## First iteration, cold (ms; for information only)

| workload | graaljs | javet |
|---|---|---|
| buffer-codec | 695.382 | - |
| crypto-hash | 238.528 | - |
| deltablue | 677.565 | - |
| events | 242.638 | - |
| json-roundtrip | 361.014 | - |
| kv-loop | 368.929 | - |
| map-set-churn | 613.089 | - |
| promise-timers | 134.756 | - |
| regex-tokenize | 1070.211 | - |
| richards | 946.667 | - |
| service-router | 264.787 | - |
| sha256-js | 886.196 | - |
| sort | 416.49 | - |
| stream-pipeline | 975.873 | - |
| string-build | 184.435 | - |
| text-codec | 798.372 | - |
| url-parse | 488.754 | - |
| zlib-roundtrip | 196.869 | - |

## Memory after the workload ran (private MB / peak RSS MB)

| workload | graaljs | javet |
|---|---|---|
| buffer-codec | 556 / 1020 | - |
| crypto-hash | 116 / 457 | - |
| deltablue | 84 / 320 | - |
| events | 79 / 258 | - |
| json-roundtrip | 87 / 246 | - |
| kv-loop | 153 / 513 | - |
| map-set-churn | 210 / 400 | - |
| promise-timers | 251 / 521 | - |
| regex-tokenize | 122 / 280 | - |
| richards | 125 / 280 | - |
| service-router | 203 / 366 | - |
| sha256-js | 94 / 273 | - |
| sort | 94 / 276 | - |
| stream-pipeline | 205 / 404 | - |
| string-build | 333 / 596 | - |
| text-codec | 187 / 376 | - |
| url-parse | 124 / 329 | - |
| zlib-roundtrip | 226 / 539 | - |

## Hostile memory tests (each worker under a kernel memory cap)

| attack | graaljs | javet |
|---|---|---|
| heap-bomb | stopped: Terminated: memory limit exceeded (peak RSS 493 MB) | stopped: Terminated: memory limit exceeded (peak RSS 249 MB) |
| buffer-8gb | stopped: RangeError: Invalid typed array length: 8589934592 (peak RSS 127 MB) | KILLED by the kernel at the cap |
| buffers-1gb | stopped: Terminated: memory limit exceeded (peak RSS 259 MB) | KILLED by the kernel at the cap |
| resizable-1gb | stopped: RangeError: Array buffer allocation failed (peak RSS 127 MB) | KILLED by the kernel at the cap |
| growable-sab-1gb | stopped: RangeError: Array buffer allocation failed (peak RSS 127 MB) | KILLED by the kernel at the cap |
| tolocale-churn | ran to the end: done (peak RSS 226 MB) | KILLED by the kernel at the cap |
| intl-held | stopped: Terminated: memory limit exceeded (peak RSS 347 MB) | KILLED by the kernel at the cap |
| wasm-1gb | ran to the end: no WebAssembly (peak RSS 119 MB) | ran to the end: 1 (peak RSS 173 MB) |
| host-copy-1gb | stopped: Terminated: memory limit exceeded (peak RSS 138 MB) | KILLED by the kernel at the cap |
