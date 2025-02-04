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

package org.apache.flink.kubernetes.operator.crd.status;

import org.apache.flink.annotation.Experimental;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Last observed status of the Flink deployment. */
@Experimental
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlinkDeploymentStatus {
    /** Last observed status of the Flink job on Application deployments. */
    private JobStatus jobStatus = new JobStatus();

    /** Last observed status of the JobManager deployment. */
    private JobManagerDeploymentStatus jobManagerDeploymentStatus =
            JobManagerDeploymentStatus.MISSING;

    /** Status of the last reconcile operation. */
    private ReconciliationStatus reconciliationStatus = new ReconciliationStatus();

    /** Error information about the Flink deployment. */
    private String error;
}
