/** @type {import('next').NextConfig} */
const nextConfig = {
  // The console ships as a self-contained static bundle served by the ingress
  // from the tenant VFS, so there is no Node server in production.
  output: 'export',
  // Emit the exported site straight into `out/`, matching cluster93.json.
  distDir: 'out',
  images: {
    unoptimized: true,
  },
  trailingSlash: true,
  reactStrictMode: true,
};

export default nextConfig;
