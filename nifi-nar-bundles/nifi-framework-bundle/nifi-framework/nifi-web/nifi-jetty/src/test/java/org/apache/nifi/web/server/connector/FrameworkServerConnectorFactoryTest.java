/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web.server.connector;

import org.apache.nifi.jetty.configuration.connector.alpn.ALPNServerConnectionFactory;
import org.apache.nifi.security.util.TemporaryKeyStoreBuilder;
import org.apache.nifi.security.util.TlsConfiguration;
import org.apache.nifi.util.NiFiProperties;
import org.apache.nifi.web.server.util.StoreScanner;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameworkServerConnectorFactoryTest {
    private static final String PROPERTIES_FILE_PATH = null;

    private static final int HTTP_PORT = 8080;

    private static final int HTTPS_PORT = 8443;

    private static final String H2_HTTP_1_1_PROTOCOLS = "h2 http/1.1";

    private static final String EXCLUDED_CIPHER_SUITE = "TLS_PSK_WITH_NULL_SHA";

    private static final String INCLUDED_CIPHER_SUITE_PATTERN = ".*AES_256_GCM.*";

    private static TlsConfiguration tlsConfiguration;

    @BeforeAll
    static void setTlsConfiguration() {
        final TemporaryKeyStoreBuilder builder = new TemporaryKeyStoreBuilder();
        tlsConfiguration = builder.build();
    }

    @Test
    void testHttpPortAndHttpsPortNotConfiguredException() {
        final Properties serverProperties = new Properties();
        final NiFiProperties properties = getProperties(serverProperties);

        final Server server = new Server();
        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> new FrameworkServerConnectorFactory(server, properties));
        assertTrue(e.getMessage().contains(NiFiProperties.WEB_HTTP_PORT));
    }

    @Test
    void testHttpPortAndHttpsPortException() {
        final Properties serverProperties = new Properties();
        serverProperties.setProperty(NiFiProperties.WEB_HTTP_PORT, Integer.toString(HTTP_PORT));
        serverProperties.setProperty(NiFiProperties.WEB_HTTPS_PORT, Integer.toString(HTTPS_PORT));
        final NiFiProperties properties = getProperties(serverProperties);

        final Server server = new Server();
        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> new FrameworkServerConnectorFactory(server, properties));
        assertTrue(e.getMessage().contains(NiFiProperties.WEB_HTTP_PORT));
    }

    @Test
    void testGetServerConnector() {
        final Properties serverProperties = new Properties();
        serverProperties.setProperty(NiFiProperties.WEB_HTTP_PORT, Integer.toString(HTTP_PORT));
        final NiFiProperties properties = getProperties(serverProperties);

        final Server server = new Server();
        final FrameworkServerConnectorFactory factory = new FrameworkServerConnectorFactory(server, properties);

        final ServerConnector serverConnector = factory.getServerConnector();

        assertHttpConnectionFactoryFound(serverConnector);
    }

    @Test
    void testGetServerConnectorHttps() {
        final Properties serverProperties = getHttpsProperties();
        serverProperties.setProperty(NiFiProperties.WEB_HTTPS_CIPHERSUITES_EXCLUDE, EXCLUDED_CIPHER_SUITE);
        serverProperties.setProperty(NiFiProperties.WEB_HTTPS_CIPHERSUITES_INCLUDE, INCLUDED_CIPHER_SUITE_PATTERN);
        serverProperties.setProperty(NiFiProperties.SECURITY_AUTO_RELOAD_ENABLED, Boolean.TRUE.toString());
        final FrameworkServerConnectorFactory factory = getHttpsConnectorFactory(serverProperties);

        final ServerConnector serverConnector = factory.getServerConnector();

        assertHttpConnectionFactoryFound(serverConnector);
        final SslConnectionFactory sslConnectionFactory = assertSslConnectionFactoryFound(serverConnector);

        final SslContextFactory.Server sslContextFactory = sslConnectionFactory.getSslContextFactory();
        assertTrue(sslContextFactory.getNeedClientAuth());
        assertFalse(sslContextFactory.getWantClientAuth());

        assertCipherSuitesConfigured(sslContextFactory);
        assertAutoReloadEnabled(serverConnector);

        final HTTP2ServerConnectionFactory http2ServerConnectionFactory = serverConnector.getConnectionFactory(HTTP2ServerConnectionFactory.class);
        assertNotNull(http2ServerConnectionFactory);
    }

    @Test
    void testGetServerConnectorHttpsHttp2AndHttp11() {
        final Properties serverProperties = getHttpsProperties();
        serverProperties.setProperty(NiFiProperties.WEB_HTTPS_APPLICATION_PROTOCOLS, H2_HTTP_1_1_PROTOCOLS);
        final FrameworkServerConnectorFactory factory = getHttpsConnectorFactory(serverProperties);

        final ServerConnector serverConnector = factory.getServerConnector();

        assertHttpConnectionFactoryFound(serverConnector);
        assertSslConnectionFactoryFound(serverConnector);

        final HTTP2ServerConnectionFactory http2ServerConnectionFactory = serverConnector.getConnectionFactory(HTTP2ServerConnectionFactory.class);
        assertNotNull(http2ServerConnectionFactory);

        final ALPNServerConnectionFactory alpnServerConnectionFactory = serverConnector.getConnectionFactory(ALPNServerConnectionFactory.class);
        assertNotNull(alpnServerConnectionFactory);
    }

    private Properties getHttpsProperties() {
        final Properties serverProperties = new Properties();
        serverProperties.setProperty(NiFiProperties.WEB_HTTPS_PORT, Integer.toString(HTTPS_PORT));
        serverProperties.setProperty(NiFiProperties.SECURITY_KEYSTORE, tlsConfiguration.getKeystorePath());
        serverProperties.setProperty(NiFiProperties.SECURITY_KEYSTORE_TYPE, tlsConfiguration.getKeystoreType().getType());
        serverProperties.setProperty(NiFiProperties.SECURITY_KEYSTORE_PASSWD, tlsConfiguration.getKeystorePassword());
        serverProperties.setProperty(NiFiProperties.SECURITY_KEY_PASSWD, tlsConfiguration.getKeyPassword());
        serverProperties.setProperty(NiFiProperties.SECURITY_TRUSTSTORE, tlsConfiguration.getTruststorePath());
        serverProperties.setProperty(NiFiProperties.SECURITY_TRUSTSTORE_TYPE, tlsConfiguration.getTruststoreType().getType());
        serverProperties.setProperty(NiFiProperties.SECURITY_TRUSTSTORE_PASSWD, tlsConfiguration.getTruststorePassword());
        return serverProperties;
    }

    private FrameworkServerConnectorFactory getHttpsConnectorFactory(final Properties serverProperties) {
        final NiFiProperties properties = getProperties(serverProperties);
        final Server server = new Server();
        return new FrameworkServerConnectorFactory(server, properties);
    }

    private SslConnectionFactory assertSslConnectionFactoryFound(final ServerConnector serverConnector) {
        final SslConnectionFactory sslConnectionFactory = serverConnector.getConnectionFactory(SslConnectionFactory.class);
        assertNotNull(sslConnectionFactory);
        return sslConnectionFactory;
    }

    private void assertHttpConnectionFactoryFound(final ServerConnector serverConnector) {
        assertNotNull(serverConnector);
        final HttpConnectionFactory connectionFactory = serverConnector.getConnectionFactory(HttpConnectionFactory.class);
        assertNotNull(connectionFactory);
    }

    private void assertCipherSuitesConfigured(final SslContextFactory sslContextFactory) {
        final String[] excludedCipherSuites = sslContextFactory.getExcludeCipherSuites();
        assertEquals(1, excludedCipherSuites.length);
        assertEquals(EXCLUDED_CIPHER_SUITE, excludedCipherSuites[0]);

        final String[] includedCipherSuites = sslContextFactory.getIncludeCipherSuites();
        assertEquals(1, includedCipherSuites.length);
        assertEquals(INCLUDED_CIPHER_SUITE_PATTERN, includedCipherSuites[0]);
    }

    private void assertAutoReloadEnabled(final ServerConnector serverConnector) {
        final Server server = serverConnector.getServer();
        final Collection<StoreScanner> scanners = server.getBeans(StoreScanner.class);
        assertEquals(2, scanners.size());
    }

    private NiFiProperties getProperties(final Properties serverProperties) {
        return NiFiProperties.createBasicNiFiProperties(PROPERTIES_FILE_PATH, serverProperties);
    }
}
