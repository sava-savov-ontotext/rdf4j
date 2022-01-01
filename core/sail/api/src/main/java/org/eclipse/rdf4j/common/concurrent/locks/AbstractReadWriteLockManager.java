/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/

package org.eclipse.rdf4j.common.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.StampedLock;

/**
 * An abstract base implementation of a read/write-lock manager.
 *
 * @author Arjohn Kampman
 * @author James Leigh
 * @author Håvard M. Ottestad
 */
public abstract class AbstractReadWriteLockManager implements ReadWriteLockManager {

	private final boolean trackLocks;

	// StampedLock for handling writers.
	private final StampedLock lock = new StampedLock();

	// LongAdder for handling readers. When the count is equal then there are no active readers.
	private final LongAdder readersLocked = new LongAdder();
	private final LongAdder readersUnlocked = new LongAdder();

	/**
	 * When acquiring a write-lock, the thread will acquire the write-lock and then spin & yield while waiting for
	 * readers to unlock their locks. A deadlock is possible if someone already holding a read-lock acquires another
	 * read-lock at the same time that another thread is waiting for a write-lock. To stop this from happening we can
	 * set READ_PREFERENCE to a number higher than zero. READ_PREFERENCE of 1 means that the thread acquiring a
	 * write-lock will release the write-lock if there are any readers. A READ_PREFERENCE of 100 means that the thread
	 * acquiring a write-lock will spin & yield 100 times before it attempts to release the write-lock.
	 */
	int READ_PREFERENCE = 0;

	/*
	 * -------------- Constructors --------------
	 */

	/**
	 * Creates a MultiReadSingleWriteLockManager.
	 */
	public AbstractReadWriteLockManager() {
		this(false);
	}

	/**
	 * Creates a new MultiReadSingleWriteLockManager, optionally with lock tracking enabled.
	 *
	 * @param trackLocks Controls whether the lock manager will keep track of active locks. Enabling lock tracking will
	 *                   add some overhead, but can be very useful for debugging.
	 */
	public AbstractReadWriteLockManager(boolean trackLocks) {
		this.trackLocks = trackLocks || Properties.lockTrackingEnabled();
	}

	/*
	 * --------- Methods ---------
	 */

	/**
	 * If a writer is active
	 */
	protected boolean isWriterActive() {
		return lock.isWriteLocked();
	}

	/**
	 * If one or more readers are active
	 */
	protected boolean isReaderActive() {
		long unlockedSum = readersUnlocked.sum();
		long lockedSum = readersLocked.sum();
		return unlockedSum != lockedSum;
	}

	/**
	 * Blocks current thread until after the writer lock is released (if active).
	 *
	 * @throws InterruptedException
	 */
	protected void waitForActiveWriter() throws InterruptedException {
		while (lock.isWriteLocked()) {
			Thread.onSpinWait();
		}
	}

	/**
	 * Blocks current thread until there are no reader locks active.
	 *
	 * @throws InterruptedException
	 */
	protected void waitForActiveReaders() throws InterruptedException {
		while (isReaderActive()) {
			Thread.onSpinWait();
		}
	}

	/**
	 * Creates a new Lock for reading and increments counter for active readers. The lock is tracked if lock tracking is
	 * enabled. This method is not thread safe itself, the calling method is expected to handle synchronization issues.
	 *
	 * @return a read-lock.
	 */
	protected Lock createReadLock() {
		while (true) {
			readersLocked.increment();
			if (!lock.isWriteLocked()) {
				// Everything is good! We have acquired a read-lock and there are no active writers.
				break;
			} else {
				// Release our read lock so we don't block any writers.
				readersUnlocked.increment();

				try {
					waitForActiveWriter();
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			}
		}

		return new ReadLock(readersUnlocked);
	}

	/**
	 * Creates a new Lock for writing. The lock is tracked if lock tracking is enabled. This method is not thread safe
	 * itself for performance reasons, the calling method is expected to handle synchronization issues.
	 *
	 * @return a write-lock.
	 */
	protected Lock createWriteLock() {

		// Acquire a write-lock.
		long writeStamp = lock.writeLock();

		int attempts = 0;

		// Wait for active readers to finish.
		while (true) {

			// The order is important here.
			long unlockedSum = readersUnlocked.sum();
			long lockedSum = readersLocked.sum();
			if (unlockedSum == lockedSum) {
				// No active readers.
				break;
			}

			// If a thread is allowed to acquire more than one read-lock then we could deadlock if we keep holding the
			// write-lock while we wait for all readers to finish. This is because no read-locks can be acquired while
			// the write-lock is locked.
			if (READ_PREFERENCE > 0 && ++attempts % READ_PREFERENCE == 0) {
				lock.unlockWrite(writeStamp);
				Thread.yield();
				writeStamp = lock.writeLock();
			} else {
				Thread.onSpinWait();
			}
		}

		return new WriteLock(lock, writeStamp);
	}

	static class WriteLock implements Lock {

		private final StampedLock lock;
		private long stamp;

		public WriteLock(StampedLock lock, long stamp) {
			this.lock = lock;
			this.stamp = stamp;
		}

		@Override
		public boolean isActive() {
			return stamp != 0;
		}

		@Override
		public void release() {
			if (isActive()) {
				lock.unlockWrite(stamp);
				stamp = 0;
			}
		}
	}

	static class ReadLock implements Lock {

		private final LongAdder readersUnlocked;
		private boolean locked = true;

		public ReadLock(LongAdder readersUnlocked) {
			this.readersUnlocked = readersUnlocked;
		}

		@Override
		public boolean isActive() {
			return locked;
		}

		@Override
		public void release() {
			if (isActive()) {
				readersUnlocked.increment();
				locked = false;
			}
		}
	}
}
