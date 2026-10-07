// secrets: SecretsBinding (D-068). Read-only, and only for scripts submitted (or services defined) with secrets:use.
define('secrets', Object.freeze({
  get: (name) => host.secrets.get(name),
}));
