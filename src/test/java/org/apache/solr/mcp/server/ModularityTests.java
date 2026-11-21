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
package org.apache.solr.mcp.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies the package structure of the server with Spring Modulith.
 *
 * <p>Each direct sub-package of {@code org.apache.solr.mcp.server} (for example {@code search},
 * {@code indexing}, {@code config}, {@code util}) is an application module. {@link
 * ApplicationModules#verify()} fails the build when:
 *
 * <ul>
 *   <li>modules depend on each other in a cycle, or
 *   <li>a module uses a type from another module's sub-package (for example {@code
 *       indexing.documentcreator}), which is internal to that module unless it is exposed as a
 *       named interface.
 * </ul>
 *
 * <p>If this test fails, fix the dependency (move the type, invert the dependency, or expose it
 * deliberately in the owning module's {@code package-info.java}) rather than weakening the test.
 *
 * <p>This is a pure bytecode analysis (ArchUnit): no Spring context is started and no Solr is
 * needed. It is disabled in the native test binary because classpath scanning does not work under
 * GraalVM's closed world. Spring Modulith is a test-only dependency; nothing from it ships.
 */
@DisabledInNativeImage
class ModularityTests {

  private static final ApplicationModules MODULES = ApplicationModules.of(Main.class);

  @Test
  void verifiesModuleStructure() {
    MODULES.verify();
  }

  /**
   * Writes PlantUML component diagrams and module canvases to {@code build/spring-modulith-docs}.
   * They are build output for reviewers, not committed sources.
   */
  @Test
  void writesDocumentation() {
    new Documenter(
            MODULES, Documenter.Options.defaults().withOutputFolder("build/spring-modulith-docs"))
        .writeModulesAsPlantUml()
        .writeIndividualModulesAsPlantUml()
        .writeModuleCanvases();
  }
}
