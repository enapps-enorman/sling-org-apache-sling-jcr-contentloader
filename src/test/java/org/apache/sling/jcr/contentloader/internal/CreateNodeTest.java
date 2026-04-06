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

import javax.jcr.Node;
import javax.jcr.Session;

import java.util.HashMap;
import java.util.UUID;

import org.apache.sling.jcr.contentloader.ContentReader;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.apache.sling.jcr.contentloader.ImportOptionsFactory.AUTO_CHECKOUT;
import static org.apache.sling.jcr.contentloader.ImportOptionsFactory.OVERWRITE_NODE;
import static org.apache.sling.jcr.contentloader.ImportOptionsFactory.OVERWRITE_PROPERTIES;
import static org.apache.sling.jcr.contentloader.ImportOptionsFactory.createImportOptions;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TODO might need to consolidate this with DefaultContentCreatorTest */
@ExtendWith(SlingContextExtension.class)
public class CreateNodeTest {

    private DefaultContentCreator contentCreator;
    private Session session;
    private Node testRoot;
    private static final String DEFAULT_NAME = "default-name";
    public static final String MIX_VERSIONABLE = "mix:versionable";

    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final String uniqueId() {
        return getClass().getSimpleName() + UUID.randomUUID();
    }

    @BeforeEach
    void setup() throws Exception {
        session = context.resourceResolver().adaptTo(Session.class);
        contentCreator = new DefaultContentCreator(null);
        contentCreator.init(
                createImportOptions(OVERWRITE_NODE | OVERWRITE_PROPERTIES | AUTO_CHECKOUT),
                new HashMap<String, ContentReader>(),
                null,
                null);
        testRoot = session.getRootNode().addNode(getClass().getSimpleName()).addNode(uniqueId());
    }

    @Test
    void testCreateNode() throws Exception {
        contentCreator.prepareParsing(testRoot, DEFAULT_NAME);
        final String name = uniqueId();
        assertFalse(testRoot.hasNode(name), "Expecting " + name + " child node to be absent before test");
        contentCreator.createNode(name, null, null);
        assertTrue(testRoot.hasNode(name), "Expecting " + name + " child node to be created");
    }
}
