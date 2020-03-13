package org.apache.flink.streaming.runtime.io;

import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.causal.VertexCausalLogDelta;
import org.apache.flink.runtime.causal.determinant.Determinant;
import org.apache.flink.runtime.causal.determinant.OrderDeterminant;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.io.network.api.serialization.RecordDeserializer;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;
import org.apache.flink.runtime.metrics.groups.TaskIOMetricGroup;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.metrics.WatermarkGauge;
import org.apache.flink.streaming.runtime.streamrecord.StreamElement;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusMaintainer;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ForceFeederStreamInputProcessor<IN> extends AbstractStreamInputProcessor<IN> {

	private static final Logger LOG = LoggerFactory.getLogger(ForceFeederStreamInputProcessor.class);

	public ForceFeederStreamInputProcessor(InputGate[] inputGates, TypeSerializer<IN> inputSerializer, StreamTask<?, ?> checkpointedTask, CheckpointingMode checkpointMode, Object lock, IOManager ioManager, Configuration taskManagerConfig, StreamStatusMaintainer streamStatusMaintainer, OneInputStreamOperator<IN, ?> streamOperator, TaskIOMetricGroup metrics, WatermarkGauge watermarkGauge, RecordWriterOutput<?>[] recordWriterOutputs) throws IOException {
		super(inputGates, inputSerializer, checkpointedTask, checkpointMode, lock, ioManager, taskManagerConfig, streamStatusMaintainer, streamOperator, metrics, watermarkGauge, recordWriterOutputs);
	}

	@Override
	public boolean inputLoop() throws Exception {
		//Set current record deserializer according to causal log
		Determinant maybeOrderDeterminant = causalLoggingManager.getNextRecoveryDeterminant();
		if (!maybeOrderDeterminant.isOrderDeterminant())
			throw new RuntimeException("Non-order determinant found in inputLoop.");
		OrderDeterminant o = maybeOrderDeterminant.asOrderDeterminant();
		this.currentChannel = o.getChannel();
		this.currentRecordDeserializer = recordDeserializers[this.currentChannel];

		while (true) {
			if (currentRecordDeserializer != null) {
				LOG.debug("processInput() of task: {} from buffer {}, channel {}", taskName, currentRecordDeserializer.getBuffer(), currentChannel);
				RecordDeserializer.DeserializationResult result = currentRecordDeserializer.getNextRecord(deserializationDelegate);

				if (result.isBufferConsumed()) {
					currentRecordDeserializer.getCurrentBuffer().recycleBuffer();
					currentRecordDeserializer = null;
				}

				if (result.isFullRecord()) {
					StreamElement recordOrMark = deserializationDelegate.getInstance();

					for (VertexCausalLogDelta d : recordOrMark.getLogDeltas())
						this.causalLoggingManager.appendDeterminantsToVertexLog(d.getVertexId(), d.getLogDelta());
					this.causalLoggingManager.addDeterminant(new OrderDeterminant((byte) currentChannel));

					if (recordOrMark.isWatermark()) {
						// handle watermark
						statusWatermarkValve.inputWatermark(recordOrMark.asWatermark(), currentChannel);
					} else if (recordOrMark.isStreamStatus()) {
						// handle stream status
						statusWatermarkValve.inputStreamStatus(recordOrMark.asStreamStatus(), currentChannel);
					} else if (recordOrMark.isLatencyMarker()) {
						// handle latency marker
						synchronized (lock) {
							streamOperator.processLatencyMarker(recordOrMark.asLatencyMarker());
						}
					} else {
						// now we can do the actual processing
						StreamRecord<IN> record = recordOrMark.asRecord();
						synchronized (lock) {
							numRecordsIn.inc();
							streamOperator.setKeyContextElement1(record);
							LOG.debug("{}: Process element no {}: {}.", taskName, numRecordsIn.getCount(), record);
							streamOperator.processElement(record);
						}
						return true;
					}
				}
			}
		}
	}
}