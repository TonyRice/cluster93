// A stand-in for Node's `path` (D-089): POSIX paths, following Node's own algorithms. There's no current directory in
// an isolate, so `resolve` resolves against `/`. Windows paths (`path.win32`) aren't provided.

const SLASH = 47;
const DOT = 46;

function check(path, name = 'path') {
  if (typeof path !== 'string') {
    throw new TypeError('The "' + name + '" argument must be of type string. Received ' + (path === null ? 'null' : typeof path));
  }
}

// Resolves . and .. segments; with allowAboveRoot, leading .. segments are kept.
function normalizeString(path, allowAboveRoot) {
  let res = '';
  let lastSegmentLength = 0;
  let lastSlash = -1;
  let dots = 0;
  let code = 0;
  for (let i = 0; i <= path.length; ++i) {
    if (i < path.length) {
      code = path.charCodeAt(i);
    } else if (code === SLASH) {
      break;
    } else {
      code = SLASH;
    }
    if (code === SLASH) {
      if (lastSlash === i - 1 || dots === 1) {
        // An empty or '.' segment.
      } else if (dots === 2) {
        if (res.length < 2 || lastSegmentLength !== 2 || res.charCodeAt(res.length - 1) !== DOT || res.charCodeAt(res.length - 2) !== DOT) {
          if (res.length > 2) {
            const lastSlashIndex = res.lastIndexOf('/');
            if (lastSlashIndex === -1) {
              res = '';
              lastSegmentLength = 0;
            } else {
              res = res.slice(0, lastSlashIndex);
              lastSegmentLength = res.length - 1 - res.lastIndexOf('/');
            }
            lastSlash = i;
            dots = 0;
            continue;
          } else if (res.length !== 0) {
            res = '';
            lastSegmentLength = 0;
            lastSlash = i;
            dots = 0;
            continue;
          }
        }
        if (allowAboveRoot) {
          res += res.length > 0 ? '/..' : '..';
          lastSegmentLength = 2;
        }
      } else {
        res += (res.length > 0 ? '/' : '') + path.slice(lastSlash + 1, i);
        lastSegmentLength = i - lastSlash - 1;
      }
      lastSlash = i;
      dots = 0;
    } else if (code === DOT && dots !== -1) {
      ++dots;
    } else {
      dots = -1;
    }
  }
  return res;
}

export const sep = '/';
export const delimiter = ':';

export function resolve(...paths) {
  let resolved = '';
  let absolute = false;
  for (let i = paths.length - 1; i >= -1 && !absolute; i--) {
    const path = i >= 0 ? paths[i] : '/';
    check(path, 'paths[' + i + ']');
    if (path.length === 0) {
      continue;
    }
    resolved = path + '/' + resolved;
    absolute = path.charCodeAt(0) === SLASH;
  }
  resolved = normalizeString(resolved, !absolute);
  return absolute ? '/' + resolved : resolved.length > 0 ? resolved : '.';
}

export function normalize(path) {
  check(path);
  if (path.length === 0) {
    return '.';
  }
  const absolute = path.charCodeAt(0) === SLASH;
  const trailing = path.charCodeAt(path.length - 1) === SLASH;
  let result = normalizeString(path, !absolute);
  if (result.length === 0) {
    return absolute ? '/' : trailing ? './' : '.';
  }
  if (trailing) {
    result += '/';
  }
  return absolute ? '/' + result : result;
}

export function isAbsolute(path) {
  check(path);
  return path.length > 0 && path.charCodeAt(0) === SLASH;
}

export function join(...paths) {
  let joined;
  for (const path of paths) {
    check(path);
    if (path.length > 0) {
      joined = joined === undefined ? path : joined + '/' + path;
    }
  }
  return joined === undefined ? '.' : normalize(joined);
}

export function relative(from, to) {
  check(from, 'from');
  check(to, 'to');
  if (from === to) {
    return '';
  }
  from = resolve(from);
  to = resolve(to);
  if (from === to) {
    return '';
  }
  const a = from === '/' ? [] : from.slice(1).split('/');
  const b = to === '/' ? [] : to.slice(1).split('/');
  let same = 0;
  while (same < a.length && same < b.length && a[same] === b[same]) {
    same++;
  }
  return [...Array(a.length - same).fill('..'), ...b.slice(same)].join('/');
}

export function dirname(path) {
  check(path);
  if (path.length === 0) {
    return '.';
  }
  const hasRoot = path.charCodeAt(0) === SLASH;
  let end = -1;
  let matchedSlash = true;
  for (let i = path.length - 1; i >= 1; --i) {
    if (path.charCodeAt(i) === SLASH) {
      if (!matchedSlash) {
        end = i;
        break;
      }
    } else {
      matchedSlash = false;
    }
  }
  if (end === -1) {
    return hasRoot ? '/' : '.';
  }
  if (hasRoot && end === 1) {
    return '//';
  }
  return path.slice(0, end);
}

export function basename(path, suffix) {
  check(path);
  if (suffix !== undefined) {
    check(suffix, 'suffix');
  }
  let end = path.length;
  while (end > 0 && path.charCodeAt(end - 1) === SLASH) {
    end--;
  }
  const start = path.lastIndexOf('/', end - 1) + 1;
  const base = path.slice(start, end);
  if (suffix !== undefined && suffix.length > 0 && base !== suffix && base.endsWith(suffix)) {
    return base.slice(0, base.length - suffix.length);
  }
  return base;
}

export function extname(path) {
  check(path);
  const base = basename(path);
  const dot = base.lastIndexOf('.');
  if (dot <= 0 || base === '..') {
    return '';
  }
  return base.slice(dot);
}

export function parse(path) {
  check(path);
  const root = path.charCodeAt(0) === SLASH ? '/' : '';
  if (path.length === 0) {
    return { root, dir: '', base: '', ext: '', name: '' };
  }
  const base = basename(path);
  let trimmed = path;
  while (trimmed.length > 1 && trimmed.endsWith('/')) {
    trimmed = trimmed.slice(0, -1);
  }
  // Node's own quirk: parse('/..') has ext '.', though extname('/..') is ''.
  const ext = base === '..' && trimmed.includes('/') ? '.' : extname(path);
  const dir = trimmed.includes('/') ? dirname(trimmed) : '';
  return { root, dir, base, ext, name: ext ? base.slice(0, base.length - ext.length) : base };
}

export function format(parts) {
  if (parts === null || typeof parts !== 'object') {
    throw new TypeError('The "pathObject" argument must be of type object. Received ' + (parts === null ? 'null' : typeof parts));
  }
  const dir = parts.dir || parts.root;
  const ext = parts.ext ? (parts.ext.startsWith('.') ? parts.ext : '.' + parts.ext) : '';
  const base = parts.base || (parts.name || '') + ext;
  if (!dir) {
    return base;
  }
  return dir === parts.root ? dir + base : dir + '/' + base;
}

export function toNamespacedPath(path) {
  return path;
}

export const posix = { sep, delimiter, resolve, normalize, isAbsolute, join, relative, dirname, basename, extname, parse, format, toNamespacedPath };
posix.posix = posix;

export default posix;
