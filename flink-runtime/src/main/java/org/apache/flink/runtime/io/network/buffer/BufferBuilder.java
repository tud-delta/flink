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

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Not thread safe class for filling in the content of the {@link MemorySegment}. To access written data please use
 * {@link BufferConsumer} which allows to build {@link Buffer} instances from the written data.
 */
@NotThreadSafe
public class BufferBuilder {
	private static final Logger LOG = LoggerFactory.getLogger(BufferBuilder.class);

	private final MemorySegment memorySegment;

	private final BufferRecycler recycler;

	private final SettablePositionMarker positionMarker = new SettablePositionMarker();

	private boolean bufferConsumerCreated = false;

	public BufferBuilder(MemorySegment memorySegment, BufferRecycler recycler) {
		this.memorySegment = checkNotNull(memorySegment);
		this.recycler = checkNotNull(recycler);
	}

	public BufferBuilder(BufferBuilder bufferBuilder, MemorySegment memorySegment, BufferRecycler recycler) {
		bufferBuilder.memorySegment.copyTo(0, memorySegment, 0, bufferBuilder.getWrittenBytes());
		this.memorySegment = memorySegment;
		this.recycler = recycler;
		this.positionMarker.move(bufferBuilder.getWrittenBytes());
		LOG.debug("Created {} from {}. Copied {} to {}.", this, bufferBuilder, bufferBuilder.memorySegment, memorySegment);
	}

	public BufferConsumer createBufferConsumer() {
		return createBufferConsumer(0);
	}
	/**
	 * @return created matching instance of {@link BufferConsumer} to this {@link BufferBuilder}. There can exist only
	 * one {@link BufferConsumer} per each {@link BufferBuilder} and vice versa.
	 */
	public BufferConsumer createBufferConsumer(long epochID) {
		checkState(!bufferConsumerCreated, "There can not exists two BufferConsumer for one BufferBuilder");
		bufferConsumerCreated = true;
		LOG.debug("New BufferConsumer wrapping memory segment {} (hash: {}) with positionMarker {}", memorySegment, System.identityHashCode(memorySegment), positionMarker.getCached());
		return new BufferConsumer(
			memorySegment,
			recycler,
			positionMarker, epochID);
	}

	/**
	 * Same as {@link #append(ByteBuffer)} but additionally {@link #commit()} the appending.
	 */
	public int appendAndCommit(ByteBuffer source) {
		int writtenBytes = append(source);
		commit();
		return writtenBytes;
	}

	/**
	 * Append as many data as possible from {@code source}. Not everything might be copied if there is not enough
	 * space in the underlying {@link MemorySegment}
	 *
	 * @return number of copied bytes
	 */
	public int append(ByteBuffer source) {
		checkState(!isFinished());

		int needed = source.remaining();
		int available = getMaxCapacity() - positionMarker.getCached();
		int toCopy = Math.min(needed, available);

		LOG.debug("append to memorySegment (hash: {}): positionMarker: {}, needed: {}, available: {}, toCopy: {}", System.identityHashCode(memorySegment), positionMarker.getCached(), needed, available, toCopy);

		memorySegment.put(positionMarker.getCached(), source, toCopy);
		positionMarker.move(toCopy);
		return toCopy;
	}

	/**
	 * Make the change visible to the readers. This is costly operation (volatile access) thus in case of bulk writes
	 * it's better to commit them all together instead one by one.
	 */
	public void commit() {
		positionMarker.commit();
	}

	/**
	 * Mark this {@link BufferBuilder} and associated {@link BufferConsumer} as finished - no new data writes will be
	 * allowed.
	 *
	 * <p>This method should be idempotent to handle failures and task interruptions. Check FLINK-8948 for more details.
	 *
	 * @return number of written bytes.
	 */
	public int finish() {
		int writtenBytes = positionMarker.markFinished();
		commit();
		return writtenBytes;
	}

	public boolean isFinished() {
		return positionMarker.isFinished();
	}

	public boolean isFull() {
		checkState(positionMarker.getCached() <= getMaxCapacity());
		return positionMarker.getCached() == getMaxCapacity();
	}

	public int getMaxCapacity() {
		return memorySegment.size();
	}

	public int getMemorySegmentHash() {
		return System.identityHashCode(memorySegment);
	}

	public MemorySegment getMemorySegment() {
		return memorySegment;
	}

	private int getWrittenBytes() {
		return positionMarker.getCached();
	}

	@Override
	public String toString() {
		return String.format("BufferBuilder [isFinished: %b, isFull: %b, max capacity: %d, memory segment hash: %d, written bytes: %d]", isFinished(), isFull(),  getMaxCapacity(), getMemorySegmentHash(), getWrittenBytes());
	}

	/**
	 * Holds a reference to the current writer position. Negative values indicate that writer ({@link BufferBuilder}
	 * has finished. Value {@code Integer.MIN_VALUE} represents finished empty buffer.
	 */
	@ThreadSafe
	interface PositionMarker {
		int FINISHED_EMPTY = Integer.MIN_VALUE;

		int get();

		static boolean isFinished(int position) {
			return position < 0;
		}

		static int getAbsolute(int position) {
			if (position == FINISHED_EMPTY) {
				return 0;
			}
			return Math.abs(position);
		}
	}

	/**
	 * Cached writing implementation of {@link PositionMarker}.
	 *
	 * <p>Writer ({@link BufferBuilder}) and reader ({@link BufferConsumer}) caches must be implemented independently
	 * of one another - so that the cached values can not accidentally leak from one to another.
	 *
	 * <p>Remember to commit the {@link SettablePositionMarker} to make the changes visible.
	 */
	private static class SettablePositionMarker implements PositionMarker {
		private volatile int position = 0;

		/**
		 * Locally cached value of volatile {@code position} to avoid unnecessary volatile accesses.
		 */
		private int cachedPosition = 0;

		@Override
		public int get() {
			return position;
		}

		public boolean isFinished() {
			return PositionMarker.isFinished(cachedPosition);
		}

		public int getCached() {
			return PositionMarker.getAbsolute(cachedPosition);
		}

		/**
		 * Marks this position as finished and returns the current position.
		 *
		 * @return current position as of {@link #getCached()}
		 */
		public int markFinished() {
			int currentPosition = getCached();
			int newValue = -currentPosition;
			if (newValue == 0) {
				newValue = FINISHED_EMPTY;
			}
			set(newValue);
			return currentPosition;
		}

		public void move(int offset) {
			set(cachedPosition + offset);
		}

		public void set(int value) {
			cachedPosition = value;
		}

		public void commit() {
			position = cachedPosition;
		}
	}
}
