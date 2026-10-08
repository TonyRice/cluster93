// The bundler `automately packages publish` runs (D-083). It runs in the project's own Node, with the project's own
// esbuild, from node_modules/.automately/, and turns the project's code and the npm packages it uses into plain ES
// modules a package can hold. It prints one JSON object: what it wrote, and what stops or warns about a publish.
//
// argv[2] is a JSON object: {root, outdir, entries: {"<export>": "<source file>"}, packages: ["<Automately package>"]}.
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
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

// The isolate provides these itself (D-079), so they stay imports.
const PROVIDED = new Set(['fs', 'node:fs', 'fs/promises', 'node:fs/promises']);
// Native extras some packages use only if they're there (ws uses the first two); left out, with a warning.
const OPTIONAL = new Set(['bufferutil', 'utf-8-validate', 'fsevents']);
// What gives a package away as needing a native build.
const NATIVE_HELPERS = ['node-gyp-build', 'bindings', 'prebuild-install', '@mapbox/node-pre-gyp', 'node-pre-gyp', 'node-addon-api', 'nan'];

const rel = (file) => (file ? path.relative(root, file) || file : 'your code');
const automatelyPackage = (name) => options.packages.some((p) => name === p || name.startsWith(p + '/'));

const plugin = {
  name: 'automately',
  setup(build) {
    build.onResolve({ filter: /.*/ }, (args) => {
      if (args.kind === 'entry-point') {
        return undefined;
      }
      const name = args.path;
      if (name.startsWith('automately:') || PROVIDED.has(name) || automatelyPackage(name)) {
        return { path: name, external: true };
      }
      if (OPTIONAL.has(name)) {
        out.warnings.push(name + ' (an optional native extra, imported by ' + rel(args.importer) + ') is left out');
        return { path: name, namespace: 'automately-stub' };
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
    build.onLoad({ filter: /.*/, namespace: 'automately-stub' }, () => ({ contents: 'export default undefined;', loader: 'js' }));
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
    return NATIVE_HELPERS.some((helper) => helper in deps);
  } catch {
    return false;
  }
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
    conditions: ['import', 'module'],
    sourcemap: 'linked',
    loader: { '.wasm': 'file' },
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
