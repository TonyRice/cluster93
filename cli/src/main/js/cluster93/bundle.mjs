// The bundler `c93 packages publish` runs (D-083). It runs in the project's own Node, with the project's own
// esbuild, from node_modules/.cluster93/, and turns the project's code and the npm packages it uses into plain ES
// modules a package can hold. It prints one JSON object: what it wrote, and what stops or warns about a publish.
//
// argv[2] is a JSON object: {root, outdir, entries: {"<export>": "<source file>"}, packages: ["<Cluster93 package>"]}.
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { isBuiltin } from 'node:module';
import path from 'node:path';

const options = JSON.parse(process.argv[2]);
const root = options.root;
const out = { files: [], exports: {}, natives: [], builtins: [], warnings: [], errors: [] };
const done = () => process.stdout.write(JSON.stringify(out));

let esbuild;
try {
  esbuild = await import('esbuild');
} catch {
  out.errors.push('esbuild isn\'t installed in this project; add it with: npm i -D esbuild');
  done();
  process.exit(0);
}

// The isolate provides these itself (D-079, and stand-ins for Node's, D-090), so they stay imports.
const PROVIDED = new Set(['fs', 'node:fs', 'fs/promises', 'node:fs/promises', 'path', 'node:path', 'path/posix', 'node:path/posix',
  'events', 'node:events', 'buffer', 'node:buffer', 'url', 'node:url', 'util', 'node:util', 'process', 'node:process', 'crypto', 'node:crypto',
  'stream', 'node:stream', 'stream/promises', 'node:stream/promises', 'string_decoder', 'node:string_decoder',
  'zlib', 'node:zlib', 'assert', 'node:assert', 'assert/strict', 'node:assert/strict', 'querystring', 'node:querystring',
  'timers', 'node:timers', 'timers/promises', 'node:timers/promises', 'os', 'node:os', 'http', 'node:http', 'https', 'node:https',
  'net', 'node:net', 'tls', 'node:tls', 'http2', 'node:http2', 'tty', 'node:tty', 'diagnostics_channel', 'node:diagnostics_channel', 'util/types', 'node:util/types',
  'child_process', 'node:child_process',
  'dns', 'node:dns', 'dns/promises', 'node:dns/promises', 'async_hooks', 'node:async_hooks', 'perf_hooks', 'node:perf_hooks',
  'worker_threads', 'node:worker_threads', 'module', 'node:module', 'v8', 'node:v8', 'stream/web', 'node:stream/web']);
// Native extras some packages use only if they're there (ws uses the first two); left out, with a warning.
const OPTIONAL = new Set(['bufferutil', 'utf-8-validate', 'fsevents']);
// What gives a package away as needing a native build.
const NATIVE_HELPERS = ['node-gyp-build', 'bindings', 'prebuild-install', '@mapbox/node-pre-gyp', 'node-pre-gyp', 'node-addon-api', 'nan'];

// How a required built-in reaches its ES module form inside the bundle; never a real module name.
const REQUIRED_PREFIX = 'cluster93-required-esm:';
const rel = (file) => (file ? path.relative(root, file) || file : 'your code');
const c93Package = (name) => options.packages.some((p) => name === p || name.startsWith(p + '/'));

const plugin = {
  name: 'c93',
  setup(build) {
    build.onResolve({ filter: /.*/ }, (args) => {
      if (args.kind === 'entry-point') {
        return undefined;
      }
      const name = args.path;
      if (name.startsWith(REQUIRED_PREFIX)) {
        return { path: name.slice(REQUIRED_PREFIX.length), namespace: 'cluster93-esm' };
      }
      // CommonJS code's require() of a module the isolate provides: an external require() can't work in an ES module
      // ("Dynamic require of … is not supported"), so it becomes a static import, and require() returns that module's
      // default export, which is what Node's module.exports is (D-093).
      if (args.kind === 'require-call' && (name.startsWith('c93:') || PROVIDED.has(name))) {
        return { path: name, namespace: 'cluster93-require' };
      }
      if (name.startsWith('c93:') || PROVIDED.has(name) || c93Package(name)) {
        return { path: name, external: true };
      }
      if (OPTIONAL.has(name)) {
        out.warnings.push(name + ' (an optional native extra, imported by ' + rel(args.importer) + ') is left out');
        // Required, it throws as a package that isn't installed does in Node, so a try/require falls back (ws, D-125).
        return { path: name, namespace: args.kind === 'require-call' ? 'cluster93-absent' : 'cluster93-stub' };
      }
      if (isBuiltin(name) && args.kind === 'require-call') {
        // CommonJS code requiring a built-in the isolate lacks: often inside try/catch, with a fallback (fetch-blob's
        // stream/web) or for a path never taken (node-domexception's worker_threads). The require() throws when it
        // runs, as a missing module does in Node, rather than stopping the publish (D-109).
        out.warnings.push(name.replace(/^node:/, '') + " (required by " + rel(args.importer) + ") isn't available in the isolate; requiring it throws");
        return { path: name, namespace: 'cluster93-missing' };
      }
      if (isBuiltin(name)) {
        out.builtins.push(name.replace(/^node:/, '') + ' (imported by ' + rel(args.importer) + ')');
        return { path: name, external: true };
      }
      if (name.endsWith('.node')) {
        out.natives.push(rel(args.importer) + ' loads native code (' + name + ')');
        return { path: name, external: true };
      }
      return undefined;
    });
    build.onLoad({ filter: /.*/, namespace: 'cluster93-stub' }, () => ({ contents: 'export default undefined;', loader: 'js' }));
    build.onLoad({ filter: /.*/, namespace: 'cluster93-absent' }, (args) => ({
      contents: 'const e = new Error(' + JSON.stringify("Cannot find module '" + args.path + "'") + '); e.code = "MODULE_NOT_FOUND"; throw e;',
      loader: 'js' }));
    build.onLoad({ filter: /.*/, namespace: 'cluster93-missing' }, (args) => ({
      contents: 'const e = new Error(' + JSON.stringify("Cannot find module '" + args.path + "': Node's \"" + args.path.replace(/^node:/, '')
        + "\" module isn't available in the isolate") + '); e.code = "MODULE_NOT_FOUND"; throw e;', loader: 'js' }));
    build.onLoad({ filter: /.*/, namespace: 'cluster93-require' }, (args) => ({
      contents: 'module.exports = require(' + JSON.stringify(REQUIRED_PREFIX + args.path) + ').default;', loader: 'js' }));
    build.onLoad({ filter: /.*/, namespace: 'cluster93-esm' }, (args) => ({
      contents: 'export { default } from ' + JSON.stringify(args.path) + ';', loader: 'js' }));
  },
};

/** The node_modules package a bundled file belongs to, as its folder; null for the project's own code. */
function packageDir(file) {
  const parts = file.split(/[\\/]/);
  const at = parts.lastIndexOf('node_modules');
  if (at < 0 || at + 1 >= parts.length) {
    return null;
  }
  const length = parts[at + 1].startsWith('@') ? at + 3 : at + 2;
  return parts.slice(0, length).join('/');
}

function needsNativeBuild(dir) {
  try {
    if (existsSync(path.join(root, dir, 'binding.gyp'))) {
      return true;
    }
    const manifest = JSON.parse(readFileSync(path.join(root, dir, 'package.json'), 'utf8'));
    if (manifest.gypfile || manifest.binary) {
      return true;
    }
    const deps = { ...(manifest.dependencies || {}) };
    if (NATIVE_HELPERS.some((helper) => helper in deps)) {
      return true;
    }
    // Prebuilt binaries shipped as optional per-platform packages (sharp's @img/sharp-linux-x64, and the like), loaded
    // through a computed path the bundler can't see: an installed optional dependency holding a .node file.
    return Object.keys(manifest.optionalDependencies || {}).some((dep) => hasNodeFile(installed(dir, dep), 4));
  } catch {
    return false;
  }
}

/** Where a dependency of the package in {@code dir} is installed: beside it, or in the project's node_modules. */
function installed(dir, dep) {
  for (const candidate of [path.join(root, dir, 'node_modules', dep), path.join(root, 'node_modules', dep)]) {
    if (existsSync(candidate)) {
      return candidate;
    }
  }
  return null;
}

function hasNodeFile(dir, depth) {
  if (!dir || depth < 0) {
    return false;
  }
  try {
    for (const name of readdirSync(dir)) {
      const full = path.join(dir, name);
      if (name.endsWith('.node')) {
        return true;
      }
      if (name !== 'node_modules' && statSync(full).isDirectory() && hasNodeFile(full, depth - 1)) {
        return true;
      }
    }
  } catch {
    // Unreadable: not evidence either way.
  }
  return false;
}

function list(dir, prefix = '') {
  const files = [];
  for (const name of readdirSync(dir)) {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) {
      files.push(...list(full, prefix + name + '/'));
    } else {
      files.push(prefix + name);
    }
  }
  return files;
}

const entryPoints = {};
for (const [subpath, source] of Object.entries(options.entries)) {
  entryPoints[subpath === '.' ? 'index' : subpath.slice(2)] = path.resolve(root, source);
}

// Node's globals that npm code uses without importing them (D-093). esbuild turns each use into an import of the
// isolate's stand-in, and only where it's used; the isolate itself gets no new globals.
const globalsShim = path.join(root, 'node_modules', '.cluster93', 'globals.mjs');
writeFileSync(globalsShim, "export { Buffer } from 'node:buffer';\nexport { default as process } from 'node:process';\n");

let result;
try {
  result = await esbuild.build({
    absWorkingDir: root,
    entryPoints,
    outdir: options.outdir,
    bundle: true,
    splitting: true,
    format: 'esm',
    platform: 'node',
    target: 'es2022',
    mainFields: ['module', 'main'],
    // esbuild applies `import` to import statements and `require` to require() calls itself; naming `import` here made
    // require() resolve to a package's ES module too, so require('pg-pool') got a namespace object, not its class (D-116).
    conditions: ['module'],
    sourcemap: 'linked',
    loader: { '.wasm': 'file' },
    inject: [globalsShim],
    // What bundlers do for npm code: the production branch of `if (process.env.NODE_ENV !== 'production')`, and
    // Node's `global` as the standard globalThis.
    define: { 'process.env.NODE_ENV': '"production"', global: 'globalThis' },
    metafile: true,
    logLevel: 'silent',
    plugins: [plugin],
  });
} catch (e) {
  for (const error of e.errors || [{ text: String(e.message || e) }]) {
    out.errors.push(error.text + (error.location ? ' (' + error.location.file + ':' + error.location.line + ')' : ''));
  }
  done();
  process.exit(0);
}

// Packages that need a native build, and which package (or your code) pulled each in.
const inputs = result.metafile.inputs;
const nativeDirs = new Set();
for (const file of Object.keys(inputs)) {
  const dir = packageDir(file);
  if (dir && needsNativeBuild(dir)) {
    nativeDirs.add(dir);
  }
}
for (const dir of nativeDirs) {
  let by = null;
  for (const [file, input] of Object.entries(inputs)) {
    if (packageDir(file) !== dir && input.imports.some((i) => packageDir(i.path) === dir)) {
      by = packageDir(file);
      break;
    }
  }
  const name = dir.split('/').slice(dir.split('/').lastIndexOf('node_modules') + 1).join('/');
  const pulled = by ? by.split('/').slice(by.split('/').lastIndexOf('node_modules') + 1).join('/') : 'your code';
  out.natives.push(name + ' needs native code (pulled in by ' + pulled + ')');
}

for (const [file, output] of Object.entries(result.metafile.outputs)) {
  if (output.entryPoint) {
    const name = path.relative(options.outdir, path.resolve(root, file)).split(path.sep).join('/');
    const subpath = Object.keys(options.entries).find((s) => path.resolve(root, options.entries[s]) === path.resolve(root, output.entryPoint));
    if (subpath) {
      out.exports[subpath] = name;
    }
  }
}
out.files = list(options.outdir);
done();
