**GraalVM Environment:** Oracle GraalVM (native-image 25.0.4.1.1 2026-08-18)

CPU during the run: package 54°C median, 65°C max; mean clock 2482 MHz median, 892 MHz lowest (255 samples, every 5 s).

## Start-up (native-image worker, first script answered)

| engine | ms to first script (median of 3) | private MB | RSS MB |
|---|---|---|---|
| javet | 749 | 40 | 170 |
| graaljs | 698 | 17 | 118 |

## Memory with idle services

| engine | 0 idle: private / RSS MB | 20 idle | 50 idle | private MB per idle service |
|---|---|---|---|---|
| javet | 3 / 64 | 101 / 231 | 189 / 318 | 2.9 |
| graaljs | 3 / 67 | 349 / 503 | 387 / 542 | 1.3 |

## Warmed-up iteration time (median ms; lower is better)

| workload | javet median ms | graaljs median ms |
|---|---|---|
| buffer-codec | 74.011 | 101.776 (1.38x) |
| crypto-hash | 75.8 | 113.917 (1.50x) |
| deltablue | 23.28 | 111.422 (4.79x) |
| events | 92.92 | 67.864 (0.73x) |
| json-roundtrip | 39.986 | 341.625 (8.54x) |
| kv-loop | 40.596 | 76.413 (1.88x) |
| map-set-churn | 60.694 | 95.101 (1.57x) |
| promise-timers | 56.84 | 59.048 (1.04x) |
| regex-tokenize | 30.298 | 9.495 (0.31x) |
| richards | 29.626 | 61.847 (2.09x) |
| service-router | 19.946 | 112.747 (5.65x) |
| sha256-js | 74.777 | 78.541 (1.05x) |
| sort | 62.904 | 250.416 (3.98x) |
| stream-pipeline | 167.168 | 856.511 (5.12x) |
| string-build | 23.787 | 125.839 (5.29x) |
| text-codec | 27.346 | 269.912 (9.87x) |
| url-parse | 36.867 | 93.623 (2.54x) |
| zlib-roundtrip | 45.841 | 330.22 (7.20x) |

Ratios are against javet (V8): above 1 is slower than V8.

## Tail latency once warm (p99 ms)

| workload | javet | graaljs |
|---|---|---|
| buffer-codec | 141.959 | 818.413 |
| crypto-hash | 99.68 | 126.902 |
| deltablue | 29.193 | 128.745 |
| events | 97.937 | 69.87 |
| json-roundtrip | 43.941 | 395.272 |
| kv-loop | 49.803 | 125.088 |
| map-set-churn | 86.103 | 217.126 |
| promise-timers | 57.75 | 127.828 |
| regex-tokenize | 33.687 | 10.193 |
| richards | 33.005 | 91.96 |
| service-router | 22.224 | 152.59 |
| sha256-js | 86.824 | 85.076 |
| sort | 77.542 | 268.975 |
| stream-pipeline | 202.469 | 1078.66 |
| string-build | 33.42 | 357.992 |
| text-codec | 41.282 | 411.677 |
| url-parse | 42.826 | 97.936 |
| zlib-roundtrip | 65.322 | 391.793 |

## First iteration, cold (ms; for information only)

| workload | javet | graaljs |
|---|---|---|
| buffer-codec | 105.061 | 652.813 |
| crypto-hash | 91.624 | 234.419 |
| deltablue | 51.502 | 605.826 |
| events | 55.738 | 250.04 |
| json-roundtrip | 47.92 | 358.839 |
| kv-loop | 46.992 | 144.154 |
| map-set-churn | 82.603 | 268.183 |
| promise-timers | 58.327 | 93.181 |
| regex-tokenize | 39.96 | 315.378 |
| richards | 41.699 | 311.856 |
| service-router | 25.32 | 267.687 |
| sha256-js | 80.641 | 951.014 |
| sort | 76.221 | 424.725 |
| stream-pipeline | 194.198 | 845.608 |
| string-build | 40.445 | 421.163 |
| text-codec | 46.019 | 2133.182 |
| url-parse | 61.902 | 1067.811 |
| zlib-roundtrip | 47.219 | 446.824 |

## Memory after the workload ran (private MB / peak RSS MB)

| workload | javet | graaljs |
|---|---|---|
| buffer-codec | 45 / 283 | 416 / 1043 |
| crypto-hash | 57 / 231 | 121 / 463 |
| deltablue | 50 / 223 | 145 / 326 |
| events | 43 / 188 | 80 / 257 |
| json-roundtrip | 44 / 263 | 86 / 246 |
| kv-loop | 61 / 231 | 154 / 502 |
| map-set-churn | 46 / 258 | 166 / 354 |
| promise-timers | 71 / 218 | 217 / 523 |
| regex-tokenize | 42 / 189 | 161 / 324 |
| richards | 43 / 187 | 120 / 277 |
| service-router | 43 / 188 | 188 / 351 |
| sha256-js | 42 / 249 | 94 / 272 |
| sort | 46 / 251 | 92 / 277 |
| stream-pipeline | 45 / 229 | 158 / 383 |
| string-build | 45 / 256 | 371 / 547 |
| text-codec | 43 / 276 | 181 / 434 |
| url-parse | 44 / 193 | 154 / 328 |
| zlib-roundtrip | 69 / 436 | 185 / 529 |

## Hostile memory tests (each worker under a kernel memory cap)

| attack | javet | graaljs |
|---|---|---|
| heap-bomb | stopped: Terminated: memory limit exceeded (peak RSS 251 MB) | stopped: Terminated: memory limit exceeded (peak RSS 490 MB) |
| buffer-8gb | KILLED by the kernel at the cap | stopped: RangeError: Invalid typed array length: 8589934592 (peak RSS 127 MB) |
| buffers-1gb | KILLED by the kernel at the cap | stopped: Terminated: memory limit exceeded (peak RSS 244 MB) |
| resizable-1gb | KILLED by the kernel at the cap | stopped: RangeError: Array buffer allocation failed (peak RSS 127 MB) |
| growable-sab-1gb | KILLED by the kernel at the cap | stopped: RangeError: Array buffer allocation failed (peak RSS 127 MB) |
| tolocale-churn | KILLED by the kernel at the cap | ran to the end: done (peak RSS 225 MB) |
| intl-held | KILLED by the kernel at the cap | stopped: Terminated: memory limit exceeded (peak RSS 384 MB) |
| wasm-1gb | ran to the end: 1 (peak RSS 173 MB) | ran to the end: no WebAssembly (peak RSS 119 MB) |
| host-copy-1gb | KILLED by the kernel at the cap | stopped: Terminated: memory limit exceeded (peak RSS 138 MB) |
