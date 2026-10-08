# Vendored streams

Node's own `stream` (from [`readable-stream`](https://github.com/nodejs/readable-stream)) and `string_decoder`, bundled into ES modules for the isolate (D-095, D-100). The output is committed in `automately-runtime-javet/src/main/resources/automately/runtime/javet/js/node/`, so building Automately never needs Node.

To update: change the versions in `package.json`, then
```bash
npm install --package-lock-only --ignore-scripts   # new lockfile, with integrity hashes
npm ci --ignore-scripts                            # installs exactly the lockfile, checking every hash
node build.mjs                                     # writes the modules and THIRD-PARTY-LICENSES.txt
```
Review the diff of the generated files like any other code, then run `./mvnw verify` (with `AUTOMATELY_TEST_REAL_NPM` set, so real stream libraries are tried).
