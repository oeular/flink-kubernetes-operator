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

package org.apache.flink.kubernetes.operator.controller;

import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.kubernetes.operator.TestUtils;
import org.apache.flink.kubernetes.operator.TestingFlinkService;
import org.apache.flink.kubernetes.operator.config.FlinkOperatorConfiguration;
import org.apache.flink.kubernetes.operator.config.KubernetesOperatorConfigOptions;
import org.apache.flink.kubernetes.operator.crd.FlinkDeployment;
import org.apache.flink.kubernetes.operator.crd.spec.JobState;
import org.apache.flink.kubernetes.operator.crd.spec.UpgradeMode;
import org.apache.flink.kubernetes.operator.crd.status.JobManagerDeploymentStatus;
import org.apache.flink.kubernetes.operator.crd.status.ReconciliationState;
import org.apache.flink.kubernetes.operator.crd.status.Savepoint;
import org.apache.flink.util.function.ThrowingRunnable;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** @link RollBack logic tests */
@EnableKubernetesMockClient(crud = true)
public class RollbackTest {

    private final Context context = TestUtils.createContextWithReadyJobManagerDeployment();
    private final FlinkOperatorConfiguration operatorConfiguration =
            FlinkOperatorConfiguration.fromConfiguration(new Configuration());

    private TestingFlinkService flinkService;
    private FlinkDeploymentController testController;

    private KubernetesClient kubernetesClient;

    @BeforeEach
    public void setup() {
        flinkService = new TestingFlinkService();
        testController =
                TestUtils.createTestController(
                        operatorConfiguration, kubernetesClient, flinkService);
    }

    @Test
    public void testRollbackWithSavepoint() throws Exception {
        var dep = TestUtils.buildApplicationCluster();
        dep.getSpec().getJob().setUpgradeMode(UpgradeMode.SAVEPOINT);
        var flinkConfiguration = dep.getSpec().getFlinkConfiguration();
        flinkConfiguration.put(CheckpointingOptions.SAVEPOINT_DIRECTORY.key(), "sd");
        var jobStatus = dep.getStatus().getJobStatus();
        var reconStatus = dep.getStatus().getReconciliationStatus();

        List<String> savepoints = new ArrayList<>();
        testRollback(
                dep,
                () -> {
                    dep.getSpec().getJob().setParallelism(9999);
                    testController.reconcile(dep, context);
                    savepoints.add(jobStatus.getSavepointInfo().getLastSavepoint().getLocation());
                    assertEquals(
                            JobState.SUSPENDED,
                            reconStatus.deserializeLastReconciledSpec().getJob().getState());
                    testController.reconcile(dep, TestUtils.createEmptyContext());

                    // Trigger rollback by delaying the recovery
                    Thread.sleep(500);
                    testController.reconcile(dep, context);
                },
                () -> {
                    assertEquals("RUNNING", dep.getStatus().getJobStatus().getState());
                    assertEquals(1, flinkService.listJobs().size());
                    // Make sure we rolled back using the savepoint taken during upgrade
                    assertEquals(savepoints.get(0), flinkService.listJobs().get(0).f0);
                    dep.getSpec().setRestartNonce(10L);
                    testController.reconcile(dep, context);
                });
    }

    @Test
    public void testRollbackWithLastState() throws Exception {
        var dep = TestUtils.buildApplicationCluster();
        dep.getSpec().getJob().setUpgradeMode(UpgradeMode.LAST_STATE);
        var reconStatus = dep.getStatus().getReconciliationStatus();

        testRollback(
                dep,
                () -> {
                    dep.getSpec().getJob().setParallelism(9999);
                    testController.reconcile(dep, context);
                    assertEquals(
                            JobState.SUSPENDED,
                            reconStatus.deserializeLastReconciledSpec().getJob().getState());
                    testController.reconcile(dep, TestUtils.createEmptyContext());

                    // Trigger rollback by delaying the recovery
                    Thread.sleep(200);
                    testController.reconcile(dep, context);
                },
                () -> {
                    assertEquals("RUNNING", dep.getStatus().getJobStatus().getState());
                    assertEquals(1, flinkService.listJobs().size());
                    dep.getSpec().setRestartNonce(10L);
                    testController.reconcile(dep, context);
                });
    }

    @Test
    public void testRollbackFailureWithLastState() throws Exception {
        var dep = TestUtils.buildApplicationCluster();
        dep.getSpec().getJob().setUpgradeMode(UpgradeMode.LAST_STATE);
        var reconStatus = dep.getStatus().getReconciliationStatus();

        testRollback(
                dep,
                () -> {
                    dep.getSpec().getJob().setParallelism(9999);
                    testController.reconcile(dep, context);
                    assertEquals(
                            JobState.SUSPENDED,
                            reconStatus.deserializeLastReconciledSpec().getJob().getState());
                    testController.reconcile(dep, TestUtils.createEmptyContext());

                    // Trigger rollback by delaying the recovery
                    Thread.sleep(200);
                    testController.reconcile(dep, context);
                },
                () -> {
                    assertEquals("RUNNING", dep.getStatus().getJobStatus().getState());
                    assertEquals(1, flinkService.listJobs().size());

                    // Remove job to simulate rollback failure
                    flinkService.clear();
                    flinkService.setPortReady(false);

                    dep.getSpec().setRestartNonce(10L);
                    testController.reconcile(dep, context);
                    flinkService.setPortReady(true);
                });
    }

    @Test
    public void testRollbackStateless() throws Exception {
        var dep = TestUtils.buildApplicationCluster();
        dep.getSpec().getJob().setUpgradeMode(UpgradeMode.STATELESS);
        var reconStatus = dep.getStatus().getReconciliationStatus();

        testRollback(
                dep,
                () -> {
                    dep.getSpec().getJob().setParallelism(9999);
                    testController.reconcile(dep, context);
                    assertEquals(
                            JobState.SUSPENDED,
                            reconStatus.deserializeLastReconciledSpec().getJob().getState());
                    testController.reconcile(dep, TestUtils.createEmptyContext());

                    // Trigger rollback by delaying the recovery
                    Thread.sleep(200);
                    dep.getStatus()
                            .getJobStatus()
                            .getSavepointInfo()
                            .updateLastSavepoint(Savepoint.of("test"));
                    testController.reconcile(dep, context);
                },
                () -> {
                    assertEquals("RUNNING", dep.getStatus().getJobStatus().getState());
                    // Make sure we started from empty state even if savepoint was available
                    assertNull(new LinkedList<>(flinkService.listJobs()).getLast().f0);

                    dep.getSpec().setRestartNonce(10L);
                    testController.reconcile(dep, context);
                });
    }

    @Test
    public void testRollbackSession() throws Exception {
        var dep = TestUtils.buildSessionCluster();
        testRollback(
                dep,
                () -> {
                    dep.getSpec().getFlinkConfiguration().put("random", "config");
                    testController.reconcile(dep, context);
                    // Trigger rollback by delaying the recovery
                    Thread.sleep(500);
                    testController.reconcile(dep, context);
                },
                () -> {
                    assertEquals(
                            JobManagerDeploymentStatus.READY,
                            dep.getStatus().getJobManagerDeploymentStatus());
                    dep.getSpec().setRestartNonce(10L);
                });
    }

    public void testRollback(
            FlinkDeployment deployment,
            ThrowingRunnable<Exception> triggerRollback,
            ThrowingRunnable<Exception> validateAndRecover)
            throws Exception {

        var flinkConfiguration = deployment.getSpec().getFlinkConfiguration();
        flinkConfiguration.put(
                KubernetesOperatorConfigOptions.DEPLOYMENT_ROLLBACK_ENABLED.key(), "true");
        flinkConfiguration.put(
                KubernetesOperatorConfigOptions.DEPLOYMENT_READINESS_TIMEOUT.key(), "100");

        testController.reconcile(deployment, TestUtils.createEmptyContext());

        // Validate reconciliation status
        var reconciliationStatus = deployment.getStatus().getReconciliationStatus();

        testController.reconcile(deployment, context);
        testController.reconcile(deployment, context);

        // Validate stable job
        assertTrue(reconciliationStatus.isLastReconciledSpecStable());

        triggerRollback.run();

        assertFalse(reconciliationStatus.isLastReconciledSpecStable());
        assertEquals(ReconciliationState.ROLLING_BACK, reconciliationStatus.getState());
        assertEquals(
                "Deployment is not ready within the configured timeout, rolling back.",
                deployment.getStatus().getError());

        testController.reconcile(deployment, context);
        assertEquals(ReconciliationState.ROLLED_BACK, reconciliationStatus.getState());

        testController.reconcile(deployment, context);
        testController.reconcile(deployment, context);

        assertEquals(ReconciliationState.ROLLED_BACK, reconciliationStatus.getState());
        assertFalse(reconciliationStatus.isLastReconciledSpecStable());

        validateAndRecover.run();
        // Test update
        testController.reconcile(deployment, TestUtils.createEmptyContext());
        assertEquals(deployment.getSpec(), reconciliationStatus.deserializeLastReconciledSpec());
        testController.reconcile(deployment, context);
        testController.reconcile(deployment, context);
        assertTrue(reconciliationStatus.isLastReconciledSpecStable());
        assertEquals(ReconciliationState.DEPLOYED, reconciliationStatus.getState());
        assertNull(deployment.getStatus().getError());

        if (deployment.getSpec().getJob() != null) {
            deployment.getSpec().getJob().setState(JobState.SUSPENDED);
            deployment.getSpec().getJob().setParallelism(1);
            testController.reconcile(deployment, context);
            testController.reconcile(deployment, TestUtils.createEmptyContext());
            assertTrue(reconciliationStatus.isLastReconciledSpecStable());
            assertEquals(ReconciliationState.DEPLOYED, reconciliationStatus.getState());
            assertNull(deployment.getStatus().getError());

            deployment.getSpec().getJob().setState(JobState.RUNNING);
            testController.reconcile(deployment, TestUtils.createEmptyContext());
            // Make sure we do not roll back to suspended state
            Thread.sleep(200);
            testController.reconcile(deployment, context);
            testController.reconcile(deployment, context);
            assertTrue(reconciliationStatus.isLastReconciledSpecStable());
            assertEquals(ReconciliationState.DEPLOYED, reconciliationStatus.getState());
            assertNull(deployment.getStatus().getError());

            // Verify suspending a rolled back job
            triggerRollback.run();
            testController.reconcile(deployment, context);
            assertEquals(ReconciliationState.ROLLED_BACK, reconciliationStatus.getState());
            testController.reconcile(deployment, context);
            testController.reconcile(deployment, context);

            deployment.getSpec().getJob().setState(JobState.SUSPENDED);
            testController.reconcile(deployment, context);
            assertTrue(reconciliationStatus.isLastReconciledSpecStable());
            assertEquals(ReconciliationState.DEPLOYED, reconciliationStatus.getState());
            assertNull(deployment.getStatus().getError());
        }
    }
}
