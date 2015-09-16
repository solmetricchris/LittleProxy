package org.littleshoot.proxy;

import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import io.netty.handler.codec.http.HttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Queue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Base class for tests that test a proxy chained to an upstream proxy. In
 * addition to the usual assertions, this also asserts that every request sent
 * by the downstream proxy was received by the upstream proxy.
 */
public abstract class BaseChainedProxyTest extends BaseProxyTest {
    private final AtomicLong REQUESTS_SENT_BY_DOWNSTREAM = new AtomicLong(
            0l);
    private final AtomicLong REQUESTS_RECEIVED_BY_UPSTREAM = new AtomicLong(
            0l);
    private final ConcurrentSkipListSet<TransportProtocol> TRANSPORTS_USED = new ConcurrentSkipListSet<TransportProtocol>();

    private final ActivityTracker DOWNSTREAM_TRACKER = new ActivityTrackerAdapter() {
        @Override
        public void requestSentToServer(FullFlowContext flowContext,
                io.netty.handler.codec.http.HttpRequest httpRequest) {
            REQUESTS_SENT_BY_DOWNSTREAM.incrementAndGet();
            TRANSPORTS_USED.add(flowContext.getChainedProxy()
                    .getTransportProtocol());
        }
    };

    private final ActivityTracker UPSTREAM_TRACKER = new ActivityTrackerAdapter() {
        @Override
        public void requestReceivedFromClient(FlowContext flowContext,
                HttpRequest httpRequest) {
            REQUESTS_RECEIVED_BY_UPSTREAM.incrementAndGet();
        };
    };

    private HttpProxyServer upstreamProxy;

    @Override
    protected void setUp() {
        REQUESTS_SENT_BY_DOWNSTREAM.set(0);
        REQUESTS_RECEIVED_BY_UPSTREAM.set(0);
        TRANSPORTS_USED.clear();
        this.upstreamProxy = upstreamProxy().build().start();
        this.proxyServer = bootstrapProxy()
                .withName("Downstream")
                .withPort(0)
                .withChainProxyManager(chainedProxyManager())
                .plusActivityTracker(DOWNSTREAM_TRACKER).build().start();
    }

    protected HttpProxyServerBootstrap upstreamProxy() {
        return DefaultHttpProxyServer.bootstrap()
                .withName("Upstream")
                .withPort(0)
                .plusActivityTracker(UPSTREAM_TRACKER);
    }
    
    protected ChainedProxyManager chainedProxyManager() {
        return new ChainedProxyManager() {
            @Override
            public void lookupChainedProxies(HttpRequest httpRequest,
                    Queue<ChainedProxy> chainedProxies) {
                chainedProxies.add(newChainedProxy());
            }
        };
    }

    protected ChainedProxy newChainedProxy() {
        return new BaseChainedProxy();
    }

    @Override
    protected void tearDown() throws Exception {
        this.upstreamProxy.stop();
    }

    @Override
    public void testSimplePostRequest() throws Exception {
        super.testSimplePostRequest();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertThatUpstreamProxyReceivedSentRequests();
        }
    }

    @Override
    public void testSimpleGetRequest() throws Exception {
        super.testSimpleGetRequest();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertThatUpstreamProxyReceivedSentRequests();
        }
    }

    @Override
    public void testProxyWithBadAddress() throws Exception {
        super.testProxyWithBadAddress();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertThatUpstreamProxyReceivedSentRequests();
        }
    }

    @Override
    protected boolean isChained() {
        return true;
    }

    private void assertThatUpstreamProxyReceivedSentRequests() {
        assertEquals(
                "Upstream proxy should have seen every request sent by downstream proxy",
                REQUESTS_SENT_BY_DOWNSTREAM.get(),
                REQUESTS_RECEIVED_BY_UPSTREAM.get());
        assertEquals(
                "1 and only 1 transport protocol should have been used to upstream proxy",
                1, TRANSPORTS_USED.size());
        assertThat("Correct transport should have been used",
                newChainedProxy().getTransportProtocol(), is(in(TRANSPORTS_USED)));
    }

    protected class BaseChainedProxy extends ChainedProxyAdapter {
        @Override
        public InetSocketAddress getChainedProxyAddress() {
            try {
                return new InetSocketAddress(InetAddress
                        .getByName("127.0.0.1"),
                        upstreamProxy.getListenAddress().getPort());
            } catch (UnknownHostException uhe) {
                throw new RuntimeException(
                        "Unable to resolve 127.0.0.1?!");
            }
        }
    }
}
