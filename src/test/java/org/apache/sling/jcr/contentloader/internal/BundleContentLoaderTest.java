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
import javax.jcr.Workspace;
import javax.jcr.nodetype.NodeType;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import java.lang.annotation.Annotation;
import java.net.URL;
import java.security.Principal;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.api.JackrabbitWorkspace;
import org.apache.jackrabbit.api.security.authorization.PrivilegeManager;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.jackrabbit.oak.spi.security.principal.EveryonePrincipal;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.jcr.contentloader.ContentReader;
import org.apache.sling.jcr.contentloader.internal.readers.JsonReader;
import org.apache.sling.jcr.contentloader.internal.readers.OrderedJsonReader;
import org.apache.sling.jcr.contentloader.internal.readers.XmlReader;
import org.apache.sling.jcr.contentloader.internal.readers.ZipReader;
import org.apache.sling.testing.mock.osgi.MockBundle;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.osgi.framework.Bundle;

import static java.util.Collections.singletonMap;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SlingContextExtension.class)
public class BundleContentLoaderTest {

    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private BundleContentLoaderListener bundleHelper;

    private ContentReaderWhiteboard whiteboard;

    private static final String CUSTOM_PRIVILEGE_NAME = "customPrivilege";

    @BeforeEach
    void prepareContentLoader() {
        // prepare content readers
        context.registerInjectActivateService(JsonReader.class);
        context.registerInjectActivateService(OrderedJsonReader.class);
        context.registerInjectActivateService(XmlReader.class);
        context.registerInjectActivateService(ZipReader.class);

        // whiteboard which holds readers
        context.registerInjectActivateService(new ContentReaderWhiteboard());

        // register the content loader service
        bundleHelper = context.registerInjectActivateService(new BundleContentLoaderListener());

        whiteboard = context.getService(ContentReaderWhiteboard.class);
    }

    private static Privilege getOrRegisterCustomPrivilege(Session session) throws RepositoryException {
        // register custom privilege
        Workspace wsp = session.getWorkspace();
        if (!(wsp instanceof JackrabbitWorkspace)) {
            throw new RepositoryException("Unable to register privileges. No JackrabbitWorkspace.");
        }
        PrivilegeManager mgr = ((JackrabbitWorkspace) wsp).getPrivilegeManager();
        try {
            return mgr.getPrivilege(CUSTOM_PRIVILEGE_NAME);
        } catch (AccessControlException e) {
            return mgr.registerPrivilege(CUSTOM_PRIVILEGE_NAME, false, new String[0]);
        }
    }

    private AccessControlEntry[] createFolderNodeAndACL(String path) throws RepositoryException {
        // use JCR API to add some node and acl
        Session session = context.resourceResolver().adaptTo(Session.class);
        Privilege customPrivilege = getOrRegisterCustomPrivilege(session);
        Privilege[] customPrivilegeSingleItemArray = new Privilege[] {customPrivilege};

        JcrUtils.getOrCreateByPath(path, NodeType.NT_FOLDER, session);

        AccessControlManager acMgr = session.getAccessControlManager();
        AccessControlList acl =
                (AccessControlList) acMgr.getApplicablePolicies(path).nextAccessControlPolicy();
        Principal everyone = EveryonePrincipal.getInstance();
        assertTrue(acl.addAccessControlEntry(everyone, customPrivilegeSingleItemArray));
        AccessControlEntry[] expectedAces = acl.getAccessControlEntries();
        acMgr.setPolicy(path, acl);

        session.save();
        return expectedAces;
    }

    private void assertFolderNodeAndACL(String path, AccessControlEntry[] expectedAces) throws RepositoryException {
        Session session = context.resourceResolver().adaptTo(Session.class);
        session.refresh(false);
        assertTrue(session.nodeExists(path));
        assertEquals(
                JcrConstants.NT_FOLDER,
                session.getNode(path).getPrimaryNodeType().getName());
        AccessControlManager acMgr = session.getAccessControlManager();
        AccessControlList acl = (AccessControlList) acMgr.getPolicies(path)[0];
        AccessControlEntry[] aces = acl.getAccessControlEntries();
        assertThat(aces, Matchers.arrayContaining(expectedAces));
    }

    @Test
    void loadContentOverwriteWithoutPath() throws Exception {
        AccessControlEntry[] expectedAces = createFolderNodeAndACL("/apps/child");
        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);
        Bundle mockBundle = newBundleWithInitialContent(context, "SLING-INF2;overwrite:=true");
        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);
        Resource imported = context.resourceResolver().getResource("/apps/sling/validation/content");
        assertNull(imported, "Resource was unexpectedly imported");
        assertFolderNodeAndACL("/apps/child", expectedAces);
    }

    @Test
    void loadContentOverwriteWithRootPath() throws Exception {
        AccessControlEntry[] expectedAces = createFolderNodeAndACL("/apps/child");
        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);
        Bundle mockBundle = newBundleWithInitialContent(context, "SLING-INF2;overwrite:=true;path:=/");
        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);
        Resource imported = context.resourceResolver().getResource("/apps/sling/validation/content");
        assertNull(imported, "Resource was unexpectedly imported");
        assertFolderNodeAndACL("/apps/child", expectedAces);
    }

    @Test
    void loadContentOverwriteWith2ndLevelPath() throws Exception {
        /*AccessControlEntry[] expectedAces =*/ createFolderNodeAndACL("/apps/child");
        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);
        Bundle mockBundle = newBundleWithInitialContent(context, "SLING-INF2/apps;overwrite:=true;path:=/apps");
        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);
        Resource imported = context.resourceResolver().getResource("/apps/sling/validation/content");

        assertThat("Resource was not imported", imported, notNullValue());
        assertThat("sling:resourceType was not properly set", imported.getResourceType(), equalTo("sling:Folder"));
        assertNull(context.resourceResolver().getResource("/apps/child"));
    }

    @Test
    void loadContentWithSpecificPath() {

        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);

        Bundle mockBundle = newBundleWithInitialContent(context, "SLING-INF/libs/app;path:=/libs/app");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        Resource imported = context.resourceResolver().getResource("/libs/app");

        assertThat("Resource was not imported", imported, notNullValue());
        assertThat("sling:resourceType was not properly set", imported.getResourceType(), equalTo("sling:Folder"));
    }

    @Test
    void loadContentFromFilePathEntry() {

        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);

        Bundle mockBundle = newBundleWithInitialContent(context, "initial-content/i18n/en.json;path:=/apps/i18n/en");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        Resource imported = context.resourceResolver().getResource("/apps/i18n/en");

        assertThat("Resource was not imported", imported, notNullValue());
        assertEquals("i18n-message", imported.getValueMap().get("i18n-key"));
        assertThat("sling:resourceType was not properly set", imported.getResourceType(), equalTo("sling:Folder"));
    }

    @Test
    void loadContentWithExcludes() {

        BundleContentLoader contentLoader =
                new BundleContentLoader(bundleHelper, whiteboard, new BundleContentLoaderConfiguration() {
                    @Override
                    public Class<? extends Annotation> annotationType() {
                        return null;
                    }

                    @Override
                    public String[] includedTargets() {
                        return new String[] {"^/.*$"};
                    }

                    @Override
                    public String[] excludedTargets() {
                        return new String[] {"^/libs.*$"};
                    }
                });

        Bundle mockBundle = newBundleWithInitialContent(
                context, "SLING-INF/libs/app;path:=/libs/app,SLING-INF/content/app;path:=/content/app");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        assertThat("Excluded resource imported", context.resourceResolver().getResource("/libs/app"), nullValue());
    }

    @Test
    void loadContentWithNullValue() {

        BundleContentLoader contentLoader =
                new BundleContentLoader(bundleHelper, whiteboard, new BundleContentLoaderConfiguration() {
                    @Override
                    public Class<? extends Annotation> annotationType() {
                        return null;
                    }

                    @Override
                    public String[] includedTargets() {
                        return new String[] {"^/.*$"};
                    }

                    @Override
                    public String[] excludedTargets() {
                        return null;
                    }
                });

        Bundle mockBundle = newBundleWithInitialContent(
                context, "SLING-INF/libs/app;path:=/libs/app,SLING-INF/content/app;path:=/content/app");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        assertThat("Excluded resource imported", context.resourceResolver().getResource("/libs/app"), notNullValue());
    }

    @Test
    void loadContentWithIncludes() {

        BundleContentLoader contentLoader =
                new BundleContentLoader(bundleHelper, whiteboard, new BundleContentLoaderConfiguration() {
                    @Override
                    public Class<? extends Annotation> annotationType() {
                        return null;
                    }

                    @Override
                    public String[] includedTargets() {
                        return new String[] {"^/.*$"};
                    }

                    @Override
                    public String[] excludedTargets() {
                        return new String[] {"^/app.*$"};
                    }
                });

        Bundle mockBundle = newBundleWithInitialContent(context, "SLING-INF/libs/app;path:=/libs/app");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        assertThat(
                "Included resource not imported", context.resourceResolver().getResource("/libs/app"), notNullValue());
    }

    @Test
    void loadContentWithRootPath() {

        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);

        Bundle mockBundle = newBundleWithInitialContent(context, "SLING-INF/");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        Resource imported = context.resourceResolver().getResource("/libs/app");

        assertThat("Resource was not imported", imported, notNullValue());
        assertThat("sling:resourceType was not properly set", imported.getResourceType(), equalTo("sling:Folder"));
    }

    @Test
    @Disabled("TODO - unregister or somehow ignore the XmlReader component for this test")
    void loadXmlAsIs() {

        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);

        dumpRepo("/", 2);

        Bundle mockBundle =
                newBundleWithInitialContent(context, "SLING-INF/libs/app;path:=/libs/app;ignoreImportProviders:=xml");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        Resource imported = context.resourceResolver().getResource("/libs/app");

        assertThat("Resource was not imported", imported, notNullValue());
        assertThat("sling:resourceType was not properly set", imported.getResourceType(), equalTo("sling:Folder"));

        Resource xmlFile = context.resourceResolver().getResource("/libs/app.xml");

        dumpRepo("/", 2);

        assertThat("XML file was was not imported", xmlFile, notNullValue());
    }

    @Test
    void testDescriptorGetSetUrl() {
        BundleContentLoader.Descriptor desc = new BundleContentLoader.Descriptor();

        assertNull(desc.getUrl());
        URL mockUrl = Mockito.mock(URL.class);
        desc.setUrl(mockUrl);
        assertEquals(mockUrl, desc.getUrl());
    }

    @Test
    void testDescriptorGetSetContentReader() {
        BundleContentLoader.Descriptor desc = new BundleContentLoader.Descriptor();

        assertNull(desc.getContentReader());
        ContentReader mockReader = Mockito.mock(ContentReader.class);
        desc.setContentReader(mockReader);
        assertEquals(mockReader, desc.getContentReader());
    }

    public static MockBundle newBundleWithInitialContent(SlingContext context, String initialContentHeader) {
        MockBundle mockBundle = new MockBundle(context.bundleContext());
        mockBundle.setHeaders(singletonMap("Sling-Initial-Content", initialContentHeader));
        return mockBundle;
    }

    private void dumpRepo(String startPath, int maxDepth) {

        dumpRepo0(startPath, maxDepth, 0);
    }

    private void dumpRepo0(String startPath, int maxDepth, int currentDepth) {
        Resource resource = context.resourceResolver().getResource(startPath);
        StringBuilder format = new StringBuilder();
        for (int i = 0; i < currentDepth; i++) {
            format.append("  ");
        }
        format.append("%s [%s]%n");
        String name = resource.getName().isEmpty() ? "/" : resource.getName();
        System.out.format(format.toString(), name, resource.getResourceType());
        currentDepth++;
        if (currentDepth > maxDepth) {
            return;
        }
        for (Resource child : resource.getChildren()) {
            dumpRepo0(child.getPath(), maxDepth, currentDepth);
        }
    }
}
