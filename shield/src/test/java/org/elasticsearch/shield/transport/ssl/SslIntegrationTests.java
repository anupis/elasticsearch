/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport.ssl;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.node.Node;
import org.elasticsearch.shield.ssl.ClientSSLService;
import org.elasticsearch.shield.transport.netty.ShieldNettyHttpServerTransport;
import org.elasticsearch.test.ShieldIntegTestCase;
import org.elasticsearch.test.ShieldSettingsSource;
import org.elasticsearch.transport.Transport;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Locale;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.hamcrest.Matchers.*;

public class SslIntegrationTests extends ShieldIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return settingsBuilder().put(super.nodeSettings(nodeOrdinal))
                .put(Node.HTTP_ENABLED, true)
                .put(ShieldNettyHttpServerTransport.HTTP_SSL_SETTING, true).build();
    }

    @Override
    protected boolean sslTransportEnabled() {
        return true;
    }

    // no SSL exception as this is the exception is returned when connecting
    @Test(expected = NoNodeAvailableException.class)
    public void testThatUnconfiguredCiphersAreRejected() {
        try(TransportClient transportClient = TransportClient.builder().settings(settingsBuilder()
                .put(transportClientSettings())
                .put("name", "programmatic_transport_client")
                .put("cluster.name", internalCluster().getClusterName())
                .putArray("shield.ssl.ciphers", new String[]{"TLS_ECDH_anon_WITH_RC4_128_SHA", "SSL_RSA_WITH_3DES_EDE_CBC_SHA"})
                .build()).build()) {

            TransportAddress transportAddress = internalCluster().getInstance(Transport.class).boundAddress().boundAddress();
            transportClient.addTransportAddress(transportAddress);

            transportClient.admin().cluster().prepareHealth().get();
        }
    }

    // no SSL exception as this is the exception is returned when connecting
    @Test(expected = NoNodeAvailableException.class)
    public void testThatTransportClientUsingSSLv3ProtocolIsRejected() {
        try(TransportClient transportClient = TransportClient.builder().settings(settingsBuilder()
                .put(transportClientSettings())
                .put("name", "programmatic_transport_client")
                .put("cluster.name", internalCluster().getClusterName())
                .putArray("shield.ssl.supported_protocols", new String[]{"SSLv3"})
                .build()).build()) {

            TransportAddress transportAddress = internalCluster().getInstance(Transport.class).boundAddress().boundAddress();
            transportClient.addTransportAddress(transportAddress);

            transportClient.admin().cluster().prepareHealth().get();
        }
    }

    @Test
    public void testThatConnectionToHTTPWorks() throws Exception {
        Settings settings = ShieldSettingsSource.getSSLSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testclient.jks", "testclient");
        ClientSSLService service = new ClientSSLService(settings);

        CredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(nodeClientUsername(), new String(nodeClientPassword().internalChars())));
        try (CloseableHttpClient client = HttpClients.custom().setSslcontext(service.sslContext()).setDefaultCredentialsProvider(provider).build();
            CloseableHttpResponse response = client.execute(new HttpGet(getNodeUrl()))) {
            assertThat(response.getStatusLine().getStatusCode(), is(200));
            String data = Streams.copyToString(new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
            assertThat(data, containsString("You Know, for Search"));
        }
    }

    @Test
    public void testThatHttpUsingSSLv3IsRejected() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("SSL");
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);

        sslContext.init(null, factory.getTrustManagers(), new SecureRandom());
        SSLConnectionSocketFactory sf = new SSLConnectionSocketFactory(sslContext, new String[]{ "SSLv3" }, null, SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
        try (CloseableHttpClient client = HttpClients.custom().setSSLSocketFactory(sf).build()) {
            client.execute(new HttpGet(getNodeUrl()));
            fail("Expected a connection error due to SSLv3 not being supported by default");
        } catch (Exception e) {
            assertThat(e, is(instanceOf(SSLHandshakeException.class)));
        }
    }

    private String getNodeUrl() {
        TransportAddress transportAddress = internalCluster().getInstance(HttpServerTransport.class).boundAddress().boundAddress();
        assertThat(transportAddress, is(instanceOf(InetSocketTransportAddress.class)));
        InetSocketTransportAddress inetSocketTransportAddress = (InetSocketTransportAddress) transportAddress;
        return String.format(Locale.ROOT, "https://%s:%s/", "localhost", inetSocketTransportAddress.address().getPort());
    }
}
