import type { Metadata, Viewport } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'Cluster93 Console',
  description:
    'Management console for the Cluster93 clustered sandboxed runtime — cluster, jobs, services, apps, and storage.',
  applicationName: 'Cluster93',
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  themeColor: '#090b10',
  width: 'device-width',
  initialScale: 1,
};

/**
 * Root shell. The console is a single-page app rendered entirely on the client;
 * the layout only establishes the dark theme, typography, and the full-height
 * flex container the dashboard fills.
 */
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" className="h-full">
      <body className="min-h-full font-sans">
        <div className="flex min-h-screen flex-col">{children}</div>
      </body>
    </html>
  );
}
