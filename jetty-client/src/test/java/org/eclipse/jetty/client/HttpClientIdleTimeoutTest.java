//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientIdleTimeoutTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void start(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
        if (client != null)
            client.stop();
    }

    @Test
    public void testRequestIsRetriedWhenSentDuringIdleTimeout() throws Exception
    {
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                Cookie[] cookies = request.getCookies();
                if (cookies == null || cookies.length == 0)
                {
                    // Send a cookie in the first response.
                    response.addCookie(new Cookie("name", "value"));
                }
                else
                {
                    // Verify that there is only one cookie, i.e.
                    // that the request has not been normalized twice.
                    assertEquals(1, cookies.length);
                }
            }
        });

        CountDownLatch idleTimeoutLatch = new CountDownLatch(1);
        CountDownLatch requestLatch = new CountDownLatch(1);
        CountDownLatch retryLatch = new CountDownLatch(1);
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(new HttpClientTransportOverHTTP(1)
        {
            @Override
            public HttpDestination newHttpDestination(Origin origin)
            {
                return new DuplexHttpDestination(getHttpClient(), origin)
                {
                    @Override
                    protected SendFailure send(IConnection connection, HttpExchange exchange)
                    {
                        SendFailure result = super.send(connection, exchange);
                        if (result != null && result.retry)
                            retryLatch.countDown();
                        return result;
                    }
                };
            }

            @Override
            public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
            {
                return new HttpConnectionOverHTTP(endPoint, context)
                {
                    @Override
                    protected boolean onIdleTimeout(long idleTimeout)
                    {
                        boolean result = super.onIdleTimeout(idleTimeout);
                        if (result)
                            idleTimeoutLatch.countDown();
                        assertTrue(await(requestLatch));
                        return result;
                    }
                };
            }
        });
        client.setExecutor(clientThreads);
        client.start();

        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        // Create one connection.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort()).send();
        assertEquals(response.getStatus(), HttpStatus.OK_200);

        assertTrue(idleTimeoutLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Send a request exactly while the connection is idle timing out.
        CountDownLatch responseLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort()).send(result ->
        {
            assertTrue(result.isSucceeded());
            assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
            responseLatch.countDown();
        });
        assertTrue(retryLatch.await(5, TimeUnit.SECONDS));
        requestLatch.countDown();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    private boolean await(CountDownLatch latch)
    {
        try
        {
            return latch.await(15, TimeUnit.SECONDS);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }
}
