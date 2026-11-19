/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package example.dice.decisions;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the package structure with Spring Modulith. The example is one package, so this
 * mostly guards against a sub-package that reaches into another's internals later on.
 */
class ModularityTests {

    ApplicationModules modules = ApplicationModules.of(DecisionRevisionApplication.class);

    @Test
    void verifiesModuleStructure() {

        modules.verify();
    }
}
