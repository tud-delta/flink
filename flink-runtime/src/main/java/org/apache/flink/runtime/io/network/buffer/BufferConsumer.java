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

package org.apache.flink.runtime.io.network.buffer;

import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.runtime.io.network.buffer.BufferBuilder.PositionMarker;

import javax.annotation.concurrent.NotThreadSafe;

import java.io.Closeable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Not thread safe class for producing {@link Buffer}.
 *
 * <p>It reads data written by {@link BufferBuilder}.
 * Although it is not thread safe and can be used only by one single thread, this thread can be different then the
 * thread using/writing to {@link BufferBuilder}. Pattern here is simple: one thread writes data to
 * {@link BufferBuilder} and there can be a different thread reading from it using {@link BufferConsumer}.
 */
@NotThreadSafe
public class BufferConsumer implements Closeable {
	private static final Logger LOG = LoggerFactory.getLogger(BufferConsumer.class);
	private final Buffer buffer;

	private final CachedPositionMarker writerPosition;

	private final long epochID;

	private int currentReaderPosition;

	public BufferConsumer(
		MemorySegment memorySegment,
		BufferRecycler recycler,
		PositionMarker currentWriterPosition) {
		this(memorySegment, recycler, currentWriterPosition, 0L);
	}
	/**
	 * Constructs {@link BufferConsumer} instance with content that can be changed by {@link BufferBuilder}.
	 */
	public BufferConsumer(
			MemorySegment memorySegment,
			BufferRecycler recycler,
			PositionMarker currentWriterPosition, long epochID) {
		this(
			new NetworkBuffer(checkNotNull(memorySegment), checkNotNull(recycler), true),
			currentWriterPosition,
			0, epochID);
	}

	public BufferConsumer(MemorySegment memorySegment, BufferRecycler recycler, boolean isBuffer) {
		this(memorySegment, recycler, isBuffer,0L);
	}
	/**
	 * Constructs {@link BufferConsumer} instance with static content.
	 */
	public BufferConsumer(MemorySegment memorySegment, BufferRecycler recycler, boolean isBuffer, long epochID) {
		this(new NetworkBuffer(checkNotNull(memorySegment), checkNotNull(recycler), isBuffer),
			() -> -memorySegment.size(),
			0, epochID);
		checkState(memorySegment.size() > 0);
		checkState(isFinished(), "BufferConsumer with static size must be finished after construction!");
	}

	private BufferConsumer(Buffer buffer, BufferBuilder.PositionMarker currentWriterPosition, int currentReaderPosition, long epochID) {
		this.buffer = checkNotNull(buffer);
		this.writerPosition = new CachedPositionMarker(checkNotNull(currentWriterPosition));
		this.currentReaderPosition = currentReaderPosition;
		this.epochID = epochID;
	}

	public long getEpochID() {
		return epochID;
	}

	/**
	 * Checks whether the {@link BufferBuilder} has already been finished.
	 *
	 * <p>BEWARE: this method accesses the cached value of the position marker which is only updated
	 * after calls to {@link #build()}!
	 *
	 * @return <tt>true</tt> if the buffer was finished, <tt>false</tt> otherwise
	 */
	public boolean isFinished() {
		return writerPosition.isFinished();
	}

	public Buffer getBackingBuffer(){
		return buffer;
	}

	/**
	 * @return sliced {@link Buffer} containing the not yet consumed data. Returned {@link Buffer} shares the reference
	 * counter with the parent {@link BufferConsumer} - in order to recycle memory both of them must be recycled/closed.
	 */
	public Buffer build() {
		writerPosition.update();
		int cachedWriterPosition = writerPosition.getCached();
		Buffer slice = buffer.readOnlySlice(currentReaderPosition, cachedWriterPosition - currentReaderPosition);
		currentReaderPosition = cachedWriterPosition;
		return slice.retainBuffer();
	}
	/**
	 * @return sliced {@link Buffer} containing the not yet consumed data of the provided size. Returned {@link Buffer} shares the reference
	 * counter with the parent {@link BufferConsumer} - in order to recycle memory both of them must be recycled/closed.
	 */
	public Buffer build(int size) {
		writerPosition.update();
		checkState(writerPosition.getCached() - currentReaderPosition >= size);
		LOG.debug("Build buffer of size {} with writerPosition: {}, readerPosition: {}", size, writerPosition.getCached(), currentReaderPosition);

		Buffer slice = buffer.readOnlySlice(currentReaderPosition, size);

		currentReaderPosition += size;
		return slice.retainBuffer();
	}

	/**
	 * Returns a retained copy with separate indexes. This allows to read from the same {@link MemorySegment} twice.
	 *
	 * <p>WARNING: the newly returned {@link BufferConsumer} will have its reader index copied from the original buffer.
	 * In other words, data already consumed before copying will not be visible to the returned copies.
	 *
	 * @return a retained copy of self with separate indexes
	 */
	public BufferConsumer copy() {
		LOG.debug("Copy buffer {} (memorySegment hash: {}): writerPosition after: {}, readerPosition after: {}", buffer, System.identityHashCode(buffer.getMemorySegment()), writerPosition.getCached(), currentReaderPosition);
		return new BufferConsumer(buffer.retainBuffer(), writerPosition.positionMarker, currentReaderPosition, epochID);
	}

	public boolean isBuffer() {
		return buffer.isBuffer();
	}

	@Override
	public void close() {
		if (!buffer.isRecycled()) {
			buffer.recycleBuffer();
		}
	}

	public boolean isRecycled() {
		return buffer.isRecycled();
	}

	public int getWrittenBytes() {
		return writerPosition.getCached();
	}

	public int getUnreadBytes() {
		writerPosition.update();
		return writerPosition.getCached() - currentReaderPosition;
	}

	/**
	 * Cached reading wrapper around {@link PositionMarker}.
	 *
	 * <p>Writer ({@link BufferBuilder}) and reader ({@link BufferConsumer}) caches must be implemented independently
	 * of one another - so that the cached values can not accidentally leak from one to another.
	 */
	private static class CachedPositionMarker {
		private final PositionMarker positionMarker;

		/**
		 * Locally cached value of {@link PositionMarker} to avoid unnecessary volatile accesses.
		 */
		private int cachedPosition;

		CachedPositionMarker(PositionMarker positionMarker) {
			this.positionMarker = checkNotNull(positionMarker);
			update();
		}

		public boolean isFinished() {
			return PositionMarker.isFinished(cachedPosition);
		}

		public int getCached() {
			return PositionMarker.getAbsolute(cachedPosition);
		}

		private void update() {
			this.cachedPosition = positionMarker.get();
		}
	}
}
