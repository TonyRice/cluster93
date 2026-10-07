package automately.tls;

import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS between core nodes (D-066): each side presents its node certificate, trusts only the cluster CA, and refuses
 * revoked nodes. {@link #sslContext()} **requires** a certificate from the connecting side on every server socket and
 * engine it makes, whatever the library using it asks for, so a peer without a cluster certificate fails the handshake.
 */
public final class ClusterTls {

    public static final String PROTOCOL = "TLSv1.3";

    private final NodeIdentity identity;
    private final X509ExtendedKeyManager keyManager;
    private final X509ExtendedTrustManager trustManager;

    public ClusterTls(NodeIdentity identity, Revocations revocations) {
        this.identity = identity;
        try {
            char[] password = new char[0];
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(null, null);
            keys.setKeyEntry("node", identity.key(), password, new X509Certificate[] {identity.certificate(), identity.ca()});
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, password);
            this.keyManager = (X509ExtendedKeyManager) kmf.getKeyManagers()[0];

            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            trust.setCertificateEntry("cluster-ca", identity.ca());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            this.trustManager = new RevocationChecking((X509ExtendedTrustManager) tmf.getTrustManagers()[0], revocations);
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("Can't set up cluster TLS", e);
        }
    }

    public NodeIdentity identity() {
        return identity;
    }

    public X509ExtendedKeyManager keyManager() {
        return keyManager;
    }

    /** Trusts only certificates signed by the cluster CA whose node isn't revoked. */
    public X509ExtendedTrustManager trustManager() {
        return trustManager;
    }

    /** A TLS 1.3 context with this node's identity that requires the peer's certificate on every server side. */
    public SSLContext sslContext() {
        try {
            SSLContext base = SSLContext.getInstance(PROTOCOL);
            base.init(new KeyManager[] {keyManager}, new TrustManager[] {trustManager}, new SecureRandom());
            return new RequiringClientAuth(base);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Wraps a context so every server-side socket and engine requires the client's certificate. */
    private static final class RequiringClientAuth extends SSLContext {
        RequiringClientAuth(SSLContext base) {
            super(new Spi(base), base.getProvider(), base.getProtocol());
        }
    }

    private static final class Spi extends SSLContextSpi {
        private final SSLContext base;

        Spi(SSLContext base) {
            this.base = base;
        }

        @Override
        protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom random) {
            throw new UnsupportedOperationException("Already initialized");
        }

        @Override
        protected SSLSocketFactory engineGetSocketFactory() {
            return base.getSocketFactory();
        }

        @Override
        protected SSLServerSocketFactory engineGetServerSocketFactory() {
            return new RequiringServerSocketFactory(base.getServerSocketFactory());
        }

        @Override
        protected SSLEngine engineCreateSSLEngine() {
            return requiring(base.createSSLEngine());
        }

        @Override
        protected SSLEngine engineCreateSSLEngine(String host, int port) {
            return requiring(base.createSSLEngine(host, port));
        }

        @Override
        protected SSLSessionContext engineGetServerSessionContext() {
            return base.getServerSessionContext();
        }

        @Override
        protected SSLSessionContext engineGetClientSessionContext() {
            return base.getClientSessionContext();
        }

        @Override
        protected SSLParameters engineGetDefaultSSLParameters() {
            SSLParameters parameters = base.getDefaultSSLParameters();
            parameters.setNeedClientAuth(true);
            return parameters;
        }

        @Override
        protected SSLParameters engineGetSupportedSSLParameters() {
            return base.getSupportedSSLParameters();
        }

        /** Requiring a client certificate only applies to server mode; a client-mode engine ignores it. */
        private static SSLEngine requiring(SSLEngine engine) {
            engine.setNeedClientAuth(true);
            return new RequiringEngine(engine);
        }
    }

    /** Keeps "client certificate required" even if the library switches the engine to server mode later. */
    private static final class RequiringEngine extends javax.net.ssl.SSLEngine {
        private final SSLEngine engine;

        RequiringEngine(SSLEngine engine) {
            super(engine.getPeerHost(), engine.getPeerPort());
            this.engine = engine;
        }

        @Override public javax.net.ssl.SSLEngineResult wrap(java.nio.ByteBuffer[] src, int off, int len, java.nio.ByteBuffer dst) throws javax.net.ssl.SSLException { return engine.wrap(src, off, len, dst); }
        @Override public javax.net.ssl.SSLEngineResult unwrap(java.nio.ByteBuffer src, java.nio.ByteBuffer[] dsts, int off, int len) throws javax.net.ssl.SSLException { return engine.unwrap(src, dsts, off, len); }
        @Override public Runnable getDelegatedTask() { return engine.getDelegatedTask(); }
        @Override public void closeInbound() throws javax.net.ssl.SSLException { engine.closeInbound(); }
        @Override public boolean isInboundDone() { return engine.isInboundDone(); }
        @Override public void closeOutbound() { engine.closeOutbound(); }
        @Override public boolean isOutboundDone() { return engine.isOutboundDone(); }
        @Override public String[] getSupportedCipherSuites() { return engine.getSupportedCipherSuites(); }
        @Override public String[] getEnabledCipherSuites() { return engine.getEnabledCipherSuites(); }
        @Override public void setEnabledCipherSuites(String[] suites) { engine.setEnabledCipherSuites(suites); }
        @Override public String[] getSupportedProtocols() { return engine.getSupportedProtocols(); }
        @Override public String[] getEnabledProtocols() { return engine.getEnabledProtocols(); }
        @Override public void setEnabledProtocols(String[] protocols) { engine.setEnabledProtocols(protocols); }
        @Override public javax.net.ssl.SSLSession getSession() { return engine.getSession(); }
        @Override public javax.net.ssl.SSLSession getHandshakeSession() { return engine.getHandshakeSession(); }
        @Override public void beginHandshake() throws javax.net.ssl.SSLException { engine.beginHandshake(); }
        @Override public javax.net.ssl.SSLEngineResult.HandshakeStatus getHandshakeStatus() { return engine.getHandshakeStatus(); }
        @Override public void setUseClientMode(boolean mode) { engine.setUseClientMode(mode); engine.setNeedClientAuth(true); }
        @Override public boolean getUseClientMode() { return engine.getUseClientMode(); }
        @Override public void setNeedClientAuth(boolean need) { engine.setNeedClientAuth(true); }
        @Override public boolean getNeedClientAuth() { return engine.getNeedClientAuth(); }
        @Override public void setWantClientAuth(boolean want) { engine.setNeedClientAuth(true); }
        @Override public boolean getWantClientAuth() { return engine.getWantClientAuth(); }
        @Override public void setEnableSessionCreation(boolean flag) { engine.setEnableSessionCreation(flag); }
        @Override public boolean getEnableSessionCreation() { return engine.getEnableSessionCreation(); }
        @Override public SSLParameters getSSLParameters() { return engine.getSSLParameters(); }
        @Override public void setSSLParameters(SSLParameters parameters) { parameters.setNeedClientAuth(true); engine.setSSLParameters(parameters); }
        @Override public String getApplicationProtocol() { return engine.getApplicationProtocol(); }
        @Override public String getHandshakeApplicationProtocol() { return engine.getHandshakeApplicationProtocol(); }
        @Override public void setHandshakeApplicationProtocolSelector(java.util.function.BiFunction<SSLEngine, java.util.List<String>, String> selector) { engine.setHandshakeApplicationProtocolSelector(selector); }
        @Override public java.util.function.BiFunction<SSLEngine, java.util.List<String>, String> getHandshakeApplicationProtocolSelector() { return engine.getHandshakeApplicationProtocolSelector(); }
    }

    private static final class RequiringServerSocketFactory extends SSLServerSocketFactory {
        private final SSLServerSocketFactory factory;

        RequiringServerSocketFactory(SSLServerSocketFactory factory) {
            this.factory = factory;
        }

        private static java.net.ServerSocket requiring(java.net.ServerSocket socket) {
            ((SSLServerSocket) socket).setNeedClientAuth(true);
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return factory.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return factory.getSupportedCipherSuites(); }
        @Override public java.net.ServerSocket createServerSocket() throws java.io.IOException { return requiring(factory.createServerSocket()); }
        @Override public java.net.ServerSocket createServerSocket(int port) throws java.io.IOException { return requiring(factory.createServerSocket(port)); }
        @Override public java.net.ServerSocket createServerSocket(int port, int backlog) throws java.io.IOException { return requiring(factory.createServerSocket(port, backlog)); }
        @Override public java.net.ServerSocket createServerSocket(int port, int backlog, InetAddress address) throws java.io.IOException { return requiring(factory.createServerSocket(port, backlog, address)); }
    }

    /** The CA check, then refusing revoked nodes. Host names aren't checked: any node signed by the CA is a member. */
    private static final class RevocationChecking extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager ca;
        private final Revocations revocations;

        RevocationChecking(X509ExtendedTrustManager ca, Revocations revocations) {
            this.ca = ca;
            this.revocations = revocations;
        }

        private void notRevoked(X509Certificate[] chain) throws CertificateException {
            if (chain.length > 0 && revocations.isRevoked(chain[0])) {
                throw new CertificateException("Node " + NodeIdentity.commonName(chain[0]) + " has been revoked");
            }
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { ca.checkClientTrusted(chain, authType); notRevoked(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException { ca.checkServerTrusted(chain, authType); notRevoked(chain); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException { ca.checkClientTrusted(chain, authType); notRevoked(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException { ca.checkServerTrusted(chain, authType); notRevoked(chain); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException { ca.checkClientTrusted(chain, authType); notRevoked(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException { ca.checkServerTrusted(chain, authType); notRevoked(chain); }
        @Override public X509Certificate[] getAcceptedIssuers() { return ca.getAcceptedIssuers(); }
    }

    /** For callers that want the plain X.509 interface. */
    public X509TrustManager plainTrustManager() {
        return trustManager;
    }
}
