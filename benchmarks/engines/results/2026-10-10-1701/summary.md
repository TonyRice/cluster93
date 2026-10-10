**GraalVM Environment:** Oracle GraalVM (native-image 25.0.4.1.1 2026-08-18)

CPU during the run: package 55°C median, 65°C max; mean clock 2464 MHz median, 922 MHz lowest (248 samples, every 5 s).

## Start-up (native-image worker, first script answered)

| engine | ms to first script (median of 3) | private MB | RSS MB |
|---|---|---|---|
| javet | 744 | 40 | 170 |
| graaljs | 697 | 17 | 118 |

## Memory with idle services

| engine | 0 idle: private / RSS MB | 20 idle | 50 idle | private MB per idle service |
|---|---|---|---|---|
| javet | 3 / 64 | 101 / 231 | 189 / 318 | 2.9 |
| graaljs | 3 / 67 | 283 / 438 | 442 / 599 | 5.3 |

## Warmed-up iteration time (median ms; lower is better)

| workload | javet median ms | graaljs median ms |
|---|---|---|
| buffer-codec | 72.851 | 79.571 (1.09x) |
| crypto-hash | 72.139 | 113.728 (1.58x) |
| deltablue | 21.111 | 110.744 (5.25x) |
| events | 95.075 | 68.74 (0.72x) |
| json-roundtrip | 39.678 | 337.856 (8.51x) |
| kv-loop | 39.336 | 75.695 (1.92x) |
| map-set-churn | 61.123 | 76.0 (1.24x) |
| promise-timers | 57.04 | 58.699 (1.03x) |
| regex-tokenize | 33.897 | 9.635 (0.28x) |
| richards | 29.927 | 65.266 (2.18x) |
| service-router | 18.103 | 111.749 (6.17x) |
| sha256-js | 194.679 | 78.731 (0.40x) |
| sort | 161.597 | 258.928 (1.60x) |
| stream-pipeline | 414.347 | 328.369 (0.79x) |
| string-build | 58.444 | 42.589 (0.73x) |
| text-codec | 64.148 | 99.334 (1.55x) |
| url-parse | 100.48 | 38.272 (0.38x) |
| zlib-roundtrip | 45.83 | 135.696 (2.96x) |

Ratios are against javet (V8): above 1 is slower than V8.

## Tail latency once warm (p99 ms)

| workload | javet | graaljs |
|---|---|---|
| buffer-codec | 127.281 | 567.262 |
| crypto-hash | 80.059 | 121.043 |
| deltablue | 21.932 | 127.892 |
| events | 102.442 | 73.839 |
| json-roundtrip | 56.034 | 369.004 |
| kv-loop | 49.408 | 132.126 |
| map-set-churn | 72.132 | 333.713 |
| promise-timers | 62.853 | 82.078 |
| regex-tokenize | 41.87 | 10.395 |
| richards | 31.481 | 109.214 |
| service-router | 22.703 | 131.361 |
| sha256-js | 236.772 | 86.931 |
| sort | 227.076 | 299.151 |
| stream-pipeline | 468.784 | 418.506 |
| string-build | 83.961 | 179.973 |
| text-codec | 101.718 | 177.529 |
| url-parse | 122.729 | 45.202 |
| zlib-roundtrip | 51.676 | 188.853 |

## First iteration, cold (ms; for information only)

| workload | javet | graaljs |
|---|---|---|
| buffer-codec | 95.701 | 722.26 |
| crypto-hash | 84.28 | 274.958 |
| deltablue | 44.131 | 581.763 |
| events | 52.275 | 246.372 |
| json-roundtrip | 46.799 | 370.424 |
| kv-loop | 42.595 | 162.244 |
| map-set-churn | 71.467 | 284.312 |
| promise-timers | 58.238 | 92.689 |
| regex-tokenize | 35.766 | 322.467 |
| richards | 40.787 | 300.204 |
| service-router | 24.549 | 262.986 |
| sha256-js | 79.535 | 800.766 |
| sort | 191.192 | 380.66 |
| stream-pipeline | 482.488 | 925.484 |
| string-build | 102.607 | 158.704 |
| text-codec | 110.434 | 839.806 |
| url-parse | 161.357 | 394.73 |
| zlib-roundtrip | 114.757 | 182.021 |

## Memory after the workload ran (private MB / peak RSS MB)

| workload | javet | graaljs |
|---|---|---|
| buffer-codec | 45 / 283 | 593 / 951 |
| crypto-hash | 58 / 231 | 113 / 461 |
| deltablue | 50 / 224 | 104 / 329 |
| events | 43 / 188 | 80 / 258 |
| json-roundtrip | 44 / 263 | 86 / 246 |
| kv-loop | 60 / 231 | 168 / 504 |
| map-set-churn | 47 / 258 | 207 / 435 |
| promise-timers | 70 / 213 | 242 / 513 |
| regex-tokenize | 42 / 188 | 158 / 314 |
| richards | 43 / 186 | 123 / 280 |
| service-router | 43 / 188 | 206 / 370 |
| sha256-js | 43 / 250 | 94 / 274 |
| sort | 46 / 251 | 93 / 276 |
| stream-pipeline | 45 / 229 | 205 / 399 |
| string-build | 45 / 255 | 379 / 575 |
| text-codec | 44 / 277 | 161 / 383 |
| url-parse | 44 / 189 | 106 / 324 |
| zlib-roundtrip | 69 / 429 | 183 / 533 |

## Hostile memory tests (each worker under a kernel memory cap)

| attack | javet | graaljs |
|---|---|---|
| heap-bomb | stopped: Terminated: memory limit exceeded (peak RSS 248 MB) | stopped: Terminated: memory limit exceeded (peak RSS 496 MB) |
| buffer-8gb | KILLED by the kernel at the cap | stopped: RangeError: Invalid typed array length: 8589934592 (peak RSS 127 MB) |
| buffers-1gb | KILLED by the kernel at the cap | stopped: Terminated: memory limit exceeded (peak RSS 244 MB) |
| resizable-1gb | KILLED by the kernel at the cap | stopped: RangeError: Array buffer allocation failed (peak RSS 127 MB) |
| growable-sab-1gb | KILLED by the kernel at the cap | stopped: RangeError: Array buffer allocation failed (peak RSS 127 MB) |
| tolocale-churn | KILLED by the kernel at the cap | ran to the end: done (peak RSS 225 MB) |
| intl-held | KILLED by the kernel at the cap | stopped: Terminated: memory limit exceeded (peak RSS 343 MB) |
| wasm-1gb | ran to the end: 1 (peak RSS 173 MB) | ran to the end: no WebAssembly (peak RSS 119 MB) |
| host-copy-1gb | KILLED by the kernel at the cap | stopped: Terminated: memory limit exceeded (peak RSS 138 MB) |
