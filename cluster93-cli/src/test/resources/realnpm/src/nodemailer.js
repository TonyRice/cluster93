// nodemailer against a real SMTP server (Mailpit, D-116): plain, and upgraded with STARTTLS. TEST_SMTP is its port;
// it's not one of the mail ports the cluster refuses by default. Runs the same under Node.
import nodemailer from 'nodemailer';

const send = async (options) => {
  const transport = nodemailer.createTransport({ host: '127.0.0.1', port: Number(process.env.TEST_SMTP), ...options });
  try {
    const info = await transport.sendMail({
      from: 'Cluster93 <robot@example.com>', to: ['ada@example.com', 'grace@example.com'], subject: 'Hello',
      text: 'Plain text', html: '<b>Bold</b>', attachments: [{ filename: 'note.txt', content: 'attached' }],
    });
    return { accepted: info.accepted, rejected: info.rejected, envelope: info.envelope, response: info.response.slice(0, 6) };
  } catch (e) {
    return { error: e.code, command: e.command };
  } finally {
    transport.close();
  }
};

export async function run() {
  return {
    plain: await send({ secure: false, ignoreTLS: true }),
    starttls: await send({ secure: false, requireTLS: true, tls: { rejectUnauthorized: false } }),
    verified: await send({ secure: false, requireTLS: true }),
  };
}
