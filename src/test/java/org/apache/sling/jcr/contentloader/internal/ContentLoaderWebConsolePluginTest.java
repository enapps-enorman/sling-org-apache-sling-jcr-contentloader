/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.jcr.contentloader.internal;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import java.io.IOException;
import java.net.URL;
import java.util.Calendar;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;

import org.apache.sling.jcr.contentloader.PathEntry;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.osgi.framework.Bundle;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 *
 */
@ExtendWith(SlingContextExtension.class)
class ContentLoaderWebConsolePluginTest {

    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private ContentLoaderWebConsolePlugin plugin;
    private BundleHelper mockBundleHelper;

    @BeforeEach
    void beforeEach() {
        mockBundleHelper = context.registerService(BundleHelper.class, Mockito.mock(BundleHelper.class));

        plugin = context.registerInjectActivateService(ContentLoaderWebConsolePlugin.class);

        // for link prefix
        context.jakartaRequest().setAttribute("felix.webconsole.appRoot", "/console");
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.internal.ContentLoaderWebConsolePlugin#service(jakarta.servlet.ServletRequest, jakarta.servlet.ServletResponse)}.
     */
    @Test
    void testService() throws IOException, RepositoryException {
        // simulate deployed bundles
        plugin.context = Mockito.spy(plugin.context);
        Bundle mockBundle1 = mockBundle(1, "test.bundle1", Map.of());
        // simulate some content to check for in the output
        String contentHeader2 = "test-initial-content;path:=/test-content/test.bundle2";
        Bundle mockBundle2 = mockBundle(2, "test.bundle2", Map.of(PathEntry.CONTENT_HEADER, contentHeader2));
        String contentHeader3 = "test-initial-content;path:=/test-content/test.bundle3";
        Bundle mockBundle3 = mockBundle(3, "test.bundle3", Map.of(PathEntry.CONTENT_HEADER, contentHeader3));
        Bundle[] bundles = new Bundle[] {mockBundle1, mockBundle2, mockBundle3};
        Mockito.doReturn(bundles).when(plugin.context).getBundles();

        // simulate various values in mockBundleHelper BundleContentInfo for code coverage
        Map<String, Object> contentInfoMap2 = Map.of();
        Mockito.doReturn(contentInfoMap2)
                .when(mockBundleHelper)
                .getBundleContentInfo(any(Session.class), eq(mockBundle2), eq(false));
        Map<String, Object> contentInfoMap3 = Map.of(
                BundleContentLoaderListener.PROPERTY_UNINSTALL_PATHS,
                new String[] {"default:/test-content/test.bundle2"},
                BundleContentLoaderListener.PROPERTY_CONTENT_LOADED_AT,
                Calendar.getInstance(),
                BundleContentLoaderListener.PROPERTY_CONTENT_LOADED_BY,
                "testuser1",
                BundleContentLoaderListener.PROPERTY_CONTENT_LOADED,
                true);
        Mockito.doReturn(contentInfoMap3)
                .when(mockBundleHelper)
                .getBundleContentInfo(any(Session.class), eq(mockBundle3), eq(false));

        final @NotNull MockSlingJakartaHttpServletRequest req = context.jakartaRequest();
        final @NotNull MockSlingJakartaHttpServletResponse resp = context.jakartaResponse();
        plugin.service(req, resp);
        final String outputAsString = resp.getOutputAsString();
        assertNotNull(outputAsString);
        assertTrue(outputAsString.contains("<a href=\"/console/bundles/2\">test.bundle2 (2)</a>"));
    }

    /**
     * Mock a deployed bundle
     */
    private Bundle mockBundle(long bundleId, String symbolicName, Map<String, String> headersMap) {
        Bundle mockBundle = Mockito.mock(Bundle.class);
        Mockito.doReturn(symbolicName).when(mockBundle).getSymbolicName();
        Dictionary<String, String> headers = new Hashtable<>(headersMap);
        Mockito.doReturn(headers).when(mockBundle).getHeaders();
        Mockito.doReturn(bundleId).when(mockBundle).getBundleId();
        return mockBundle;
    }

    @Test
    void testServiceWithRepositoryException() throws IOException, RepositoryException {
        // simulate exception thrown during login
        plugin.repository = Mockito.spy(plugin.repository);
        Mockito.doThrow(RepositoryException.class).when(plugin.repository).loginService(null, null);

        final @NotNull MockSlingJakartaHttpServletRequest req = context.jakartaRequest();
        final @NotNull MockSlingJakartaHttpServletResponse resp = context.jakartaResponse();
        plugin.service(req, resp);
        final String outputAsString = resp.getOutputAsString();
        assertTrue(
                outputAsString.contains("Error accessing the underlying repository"), "Expected logged error message");
    }

    /**
     * Test method for {@link
     * org.apache.sling.jcr.contentloader.internal.ContentLoaderWebConsolePlugin#getResource(java.lang.String)}.
     */
    @Test
    void testGetResource() throws SecurityException, IllegalArgumentException {
        final URL value1 = ReflectionTools.invokeMethodWithReflection(
                plugin, "getResource", new Class[] {String.class}, URL.class, new Object[] {"/invalid"});
        assertNull(value1);

        final URL value2 = ReflectionTools.invokeMethodWithReflection(
                plugin, "getResource", new Class[] {String.class}, URL.class, new Object[] {
                    "/jcr-content-loader/res/ui/main.css"
                });
        assertNotNull(value2);

        final URL value3 = ReflectionTools.invokeMethodWithReflection(
                plugin, "getResource", new Class[] {String.class}, URL.class, new Object[] {
                    "/jcr-content-loader/res/ui/invalid.css"
                });
        assertNull(value3);
    }
}
