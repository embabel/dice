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
package com.embabel.dice;

import com.embabel.dice.operations.consolidation.ConsolidationPass;
import com.embabel.dice.operations.consolidation.ConsolidationPassResult;
import com.embabel.dice.projection.memory.DefaultDreamLoopOrchestrator;
import com.embabel.dice.projection.memory.DreamLoopReport;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that a Java developer can build a consolidation pass from a lambda and run it
 * with a plain string context id.
 */
class ConsolidationPassJavaInteropTest {

    @Test
    void ofBuildsPassWithGivenName() {
        ConsolidationPass pass = ConsolidationPass.of(
                "tidy",
                (ctx, props) -> new ConsolidationPassResult.NoOp("tidy", ctx));

        assertEquals("tidy", pass.getName());
    }

    @Test
    void runWithStringContextIdHandsTheStringToTheLambda() {
        ConsolidationPass pass = ConsolidationPass.of(
                "tidy",
                (ctx, props) -> new ConsolidationPassResult.NoOp("tidy", ctx));

        ConsolidationPassResult result = pass.run("acme", List.of());

        assertInstanceOf(ConsolidationPassResult.NoOp.class, result);
        assertEquals("acme", ((ConsolidationPassResult.NoOp) result).getReason());
    }

    @Test
    void orchestratorRunsLambdaPassWithStringContextId() {
        ConsolidationPass pass = ConsolidationPass.of(
                "tidy",
                (ctx, props) -> new ConsolidationPassResult.NoOp("tidy", ctx));

        DreamLoopReport report = DefaultDreamLoopOrchestrator
                .withRepository(new InMemoryPropositionRepository())
                .withPass(pass)
                .consolidateNow("acme");

        assertEquals(1, report.getPassResults().size());
        ConsolidationPassResult result = report.getPassResults().get(0);
        assertInstanceOf(ConsolidationPassResult.NoOp.class, result);
        assertEquals("acme", ((ConsolidationPassResult.NoOp) result).getReason());
    }
}
