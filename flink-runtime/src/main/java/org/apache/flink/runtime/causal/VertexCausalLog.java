/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.runtime.causal;

import org.apache.flink.runtime.state.CheckpointListener;

/**
 * Used to log a single upstream (or the current task's) task's determinants.
 * Is responsible for garbage collection of determinants which have been checkpointed or sent to all downstream tasks.
 * It is responsible for remembering what determinants it has sent to which downstream tasks.
 */
public interface VertexCausalLog extends CheckpointListener {

	byte[] getDeterminants();


	void appendDeterminants(byte[] determinants);

	/**
	 * This may be used both for transmitting actual deltas as for providing a bootstrap of lost determinants for a recovering operator.
	 * The latter has the issue that we wont know how may grow() operations to apply. Is this an issue? As more appends
	 * are made in the upstream, it may write over the downstreams limit, or circle around. The downstream will be making those writes as well, to the offsets given.
	 * If the offsets are above the size, we know we must grow and inplace copy? If they wrap around to zero
	 * @param vertexCausalLogDelta
	 */
	void processUpstreamVertexCausalLogDelta(VertexCausalLogDelta vertexCausalLogDelta);

	VertexCausalLogDelta getNextDeterminantsForDownstream(int channel);

	void notifyCheckpointBarrier(long checkpointId);

	void notifyDownstreamFailure(int channel);

}
