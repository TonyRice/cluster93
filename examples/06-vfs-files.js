// 06 — The virtual file system.
//
// Run it:
//   automately run examples/06-vfs-files.js
//
// files stores bytes in the cluster (or the tenant's own backend); the same files are visible through the
// REST API, the CLI and the console. Paths are relative, like hello.txt (no leading slash). See
// docs/scripting/host-apis.md.
export default async (input, ctx) => {
  await files.write('hello.txt', 'hello vfs', { contentType: 'text/plain' });

  const bytes = await files.read('hello.txt');
  const text = new TextDecoder().decode(bytes);

  const info = await files.stat('hello.txt');
  log.info('Read the file back', { text, size: info?.size });

  return { text, size: info?.size ?? 0 };
};
