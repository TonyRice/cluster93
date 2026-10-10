CPU during the run: package 49°C median, 60°C max; mean clock 2293 MHz median, 727 MHz lowest (369 samples, every 5 s).

## Start-up (native-image worker, first script answered)

| engine | ms to first script (median of 3) | private MB | RSS MB |
|---|---|---|---|
| javet | 746 | 40 | 170 |
| graaljs | 700 | 17 | 113 |
| graaljs-in-process | 686 | 15 | 88 |

## Memory with idle services

| engine | 0 idle: private / RSS MB | 20 idle | 50 idle | private MB per idle service |
|---|---|---|---|---|
| javet | 3 / 64 | 104 / 233 | 193 / 323 | 3.0 |
| graaljs | 3 / 64 | 289 / 439 | 441 / 593 | 5.1 |
| graaljs-in-process | 2 / 47 | 276 / 402 | 336 / 462 | 2.0 |

## Warmed-up iteration time (median ms; lower is better)

| workload | javet median ms | graaljs median ms | graaljs-in-process median ms |
|---|---|---|---|
| buffer-codec | 72.668 | 73.959 (1.02x) | 99.717 (1.37x) |
| crypto-hash | 71.339 | 93.919 (1.32x) | 66.644 (0.93x) |
| deltablue | 21.484 | 108.707 (5.06x) | 108.278 (5.04x) |
| events | 98.229 | 67.558 (0.69x) | 68.109 (0.69x) |
| json-roundtrip | 38.041 | 326.349 (8.58x) | 326.711 (8.59x) |
| kv-loop | 38.648 | 79.361 (2.05x) | 37.058 (0.96x) |
| map-set-churn | 55.521 | 84.259 (1.52x) | 98.827 (1.78x) |
| promise-timers | 57.133 | 59.586 (1.04x) | 55.618 (0.97x) |
| regex-tokenize | 30.44 | 9.445 (0.31x) | 9.384 (0.31x) |
| richards | 29.655 | 60.752 (2.05x) | 61.532 (2.07x) |
| service-router | 18.327 | 108.41 (5.92x) | 107.072 (5.84x) |
| sha256-js | 74.682 | 77.688 (1.04x) | 77.77 (1.04x) |
| sort | 60.898 | 249.94 (4.10x) | 253.254 (4.16x) |
| stream-pipeline | 162.207 | 314.98 (1.94x) | 837.309 (5.16x) |
| string-build | 24.526 | 38.528 (1.57x) | 151.435 (6.17x) |
| text-codec | 25.717 | 99.768 (3.88x) | 263.872 (10.26x) |
| url-parse | 36.097 | 38.01 (1.05x) | 93.243 (2.58x) |
| zlib-roundtrip | 45.321 | 120.266 (2.65x) | 138.262 (3.05x) |

Ratios are against javet (V8): above 1 is slower than V8.

## Tail latency once warm (p99 ms)

| workload | javet | graaljs | graaljs-in-process |
|---|---|---|---|
| buffer-codec | 123.183 | 373.273 | 315.103 |
| crypto-hash | 72.444 | 96.006 | 68.359 |
| deltablue | 34.281 | 110.446 | 109.834 |
| events | 100.2 | 83.992 | 80.346 |
| json-roundtrip | 51.572 | 333.152 | 345.327 |
| kv-loop | 47.939 | 97.497 | 42.755 |
| map-set-churn | 65.897 | 251.826 | 174.484 |
| promise-timers | 58.724 | 120.927 | 56.991 |
| regex-tokenize | 30.937 | 12.088 | 9.622 |
| richards | 29.926 | 61.752 | 68.407 |
| service-router | 18.758 | 109.973 | 121.409 |
| sha256-js | 75.901 | 78.421 | 79.291 |
| sort | 72.17 | 263.858 | 258.334 |
| stream-pipeline | 177.83 | 369.623 | 1018.933 |
| string-build | 33.912 | 192.463 | 210.792 |
| text-codec | 35.249 | 147.387 | 417.51 |
| url-parse | 37.47 | 42.206 | 100.557 |
| zlib-roundtrip | 51.498 | 142.592 | 219.123 |

## First iteration, cold (ms; for information only)

| workload | javet | graaljs | graaljs-in-process |
|---|---|---|---|
| buffer-codec | 96.144 | 630.102 | 634.083 |
| crypto-hash | 82.019 | 161.962 | 120.085 |
| deltablue | 44.088 | 564.086 | 656.319 |
| events | 51.289 | 244.326 | 244.358 |
| json-roundtrip | 45.536 | 351.141 | 367.392 |
| kv-loop | 50.43 | 139.275 | 63.456 |
| map-set-churn | 74.132 | 247.532 | 266.943 |
| promise-timers | 58.125 | 83.138 | 85.081 |
| regex-tokenize | 34.321 | 293.137 | 326.477 |
| richards | 40.669 | 291.3 | 291.852 |
| service-router | 23.911 | 232.801 | 236.889 |
| sha256-js | 79.24 | 859.593 | 771.733 |
| sort | 73.969 | 380.37 | 358.979 |
| stream-pipeline | 182.973 | 825.889 | 829.255 |
| string-build | 40.204 | 155.095 | 437.839 |
| text-codec | 44.157 | 795.916 | 2059.301 |
| url-parse | 51.402 | 365.577 | 962.036 |
| zlib-roundtrip | 47.939 | 173.496 | 789.735 |

## Memory after the workload ran (private MB / peak RSS MB)

| workload | javet | graaljs | graaljs-in-process |
|---|---|---|---|
| buffer-codec | 45 / 283 | 355 / 1053 | 291 / 481 |
| crypto-hash | 57 / 230 | 169 / 409 | 114 / 272 |
| deltablue | 50 / 223 | 153 / 313 | 142 / 283 |
| events | 43 / 188 | 80 / 253 | 75 / 227 |
| json-roundtrip | 44 / 263 | 90 / 239 | 81 / 207 |
| kv-loop | 61 / 226 | 205 / 449 | 151 / 283 |
| map-set-churn | 46 / 258 | 178 / 408 | 135 / 267 |
| promise-timers | 71 / 218 | 279 / 480 | 135 / 317 |
| regex-tokenize | 42 / 188 | 169 / 324 | 170 / 295 |
| richards | 43 / 187 | 122 / 273 | 116 / 240 |
| service-router | 43 / 188 | 211 / 384 | 91 / 346 |
| sha256-js | 43 / 249 | 94 / 267 | 82 / 240 |
| sort | 46 / 251 | 91 / 273 | 89 / 243 |
| stream-pipeline | 45 / 228 | 152 / 401 | 137 / 369 |
| string-build | 45 / 256 | 323 / 542 | 171 / 314 |
| text-codec | 44 / 276 | 160 / 371 | 203 / 411 |
| url-parse | 44 / 193 | 137 / 327 | 106 / 282 |
| zlib-roundtrip | 66 / 429 | 173 / 447 | 83 / 349 |

## Hostile memory tests (each worker under a kernel memory cap)

| attack | javet | graaljs |
|---|---|---|
| heap-bomb | stopped: Terminated: memory limit exceeded (peak RSS 250 MB) | stopped: Terminated: memory limit exceeded (peak RSS 486 MB) |
| buffer-8gb | KILLED by the kernel at the cap | stopped: RangeError: Invalid typed array length: 8589934592 (peak RSS 122 MB) |
| buffers-1gb | KILLED by the kernel at the cap | stopped: Terminated: memory limit exceeded (peak RSS 238 MB) |
| resizable-1gb | KILLED by the kernel at the cap | stopped: RangeError: Array buffer allocation failed (peak RSS 121 MB) |
| growable-sab-1gb | KILLED by the kernel at the cap | stopped: RangeError: Array buffer allocation failed (peak RSS 122 MB) |
| tolocale-churn | KILLED by the kernel at the cap | ran to the end: done (peak RSS 220 MB) |
| intl-held | KILLED by the kernel at the cap | stopped: Terminated: memory limit exceeded (peak RSS 340 MB) |
| wasm-1gb | ran to the end: 1 (peak RSS 173 MB) | ran to the end: no WebAssembly (peak RSS 114 MB) |
| host-copy-1gb | KILLED by the kernel at the cap | stopped: Terminated: memory limit exceeded (peak RSS 132 MB) |
