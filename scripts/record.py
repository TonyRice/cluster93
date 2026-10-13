#!/usr/bin/env python3
"""Records a command's output as an asciicast v2 file (asciinema's format), without needing asciinema installed.

    python3 scripts/record.py docs/demo.cast scripts/demo.sh

Play it with `asciinema play docs/demo.cast`, or upload it to asciinema.org. Pauses longer than 2 s (the build, the
leases running out) are shortened on playback by the header's idle_time_limit. API tokens are redacted.
"""
import json
import os
import re
import subprocess
import sys
import time

out, command = sys.argv[1], sys.argv[2:]
start = time.monotonic()
with open(out, "w") as cast:
    header = {"version": 2, "width": 120, "height": 40, "timestamp": int(time.time()), "idle_time_limit": 2,
              "title": "Cluster93: an agent deploys a queue consumer, a node is killed, nothing is lost",
              "env": {"TERM": "xterm-256color", "SHELL": "/bin/bash"}}
    cast.write(json.dumps(header) + "\n")
    cast.write(json.dumps([0.0, "o", "$ " + " ".join(command) + "\r\n"]) + "\n")
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    for line in iter(process.stdout.readline, b""):
        # Tokens never go into a recording, even a throwaway cluster's: atly_ tokens are made to trip secret scanners.
        text = re.sub(r"atly_[A-Za-z0-9]+", "atly_…", line.decode("utf-8", "replace")).replace("\n", "\r\n")
        cast.write(json.dumps([round(time.monotonic() - start, 3), "o", text]) + "\n")
        sys.stdout.write(line.decode("utf-8", "replace"))
        sys.stdout.flush()
    code = process.wait()
sys.exit(code)
