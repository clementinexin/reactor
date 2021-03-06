/*
 * Copyright (c) 2011-2014 Pivotal Software, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package reactor.rx.action.aggregation;

import org.reactivestreams.Subscription;
import reactor.bus.registry.Registration;
import reactor.core.Dispatcher;
import reactor.core.dispatch.InsufficientCapacityException;
import reactor.core.support.Exceptions;
import reactor.fn.Consumer;
import reactor.fn.timer.Timer;
import reactor.rx.action.Action;
import reactor.rx.subscription.BatchSubscription;
import reactor.rx.subscription.PushSubscription;

import java.util.concurrent.TimeUnit;

/**
 * @author Stephane Maldini
 * @since 1.1
 */
public abstract class BatchAction<T, V> extends Action<T, V> {

	protected final boolean                                next;
	protected final boolean                                flush;
	protected final boolean                                first;
	protected final int                                    batchSize;
	protected final Consumer<Long>                         timeoutTask;
	protected final Registration<? extends Consumer<Long>> timespanRegistration;
	protected final Dispatcher                             dispatcher;
	protected final Consumer<T> flushConsumer = new FlushConsumer();

	protected int index = 0;

	public BatchAction(
			Dispatcher dispatcher, int batchSize, boolean next, boolean first, boolean flush) {
		this(dispatcher, batchSize, next, first, flush, -1l, null, null);
	}

	public BatchAction(final Dispatcher dispatcher, int batchSize, boolean next, boolean first, boolean flush,
	                   long timespan, TimeUnit unit, Timer timer) {
		super(batchSize);
		this.dispatcher = dispatcher;
		if (timespan > 0) {
			this.timeoutTask = new Consumer<Long>() {
				@Override
				public void accept(Long aLong) {
					if (index > 0) {
						try {
							dispatcher.tryDispatch(null, flushConsumer, null);
						} catch (InsufficientCapacityException e) {
							//IGNORE
						}
					}
				}
			};
			TimeUnit targetUnit = unit != null ? unit : TimeUnit.SECONDS;
			timespanRegistration = timer.schedule(timeoutTask, timespan, targetUnit,
					TimeUnit.MILLISECONDS.convert(timespan, targetUnit));
			timespanRegistration.pause();
		} else {
			this.timeoutTask = null;
			this.timespanRegistration = null;
		}
		this.first = first;
		this.flush = flush;
		this.next = next;
		this.batchSize = batchSize;
		//this.capacity = batchSize;
	}

	@Override
	protected PushSubscription<T> createTrackingSubscription(Subscription subscription) {
		return new BatchSubscription<T>(subscription, this, batchSize);
	}

	@Override
	protected void doSubscribe(Subscription subscription) {
		super.doSubscribe(subscription);
		if (timespanRegistration != null) timespanRegistration.resume();
	}

	protected void nextCallback(T event) {
	}

	protected void flushCallback(T event) {
	}

	protected void firstCallback(T event) {
	}

	@Override
	public void accept(T t) {
		try {
			doNext(t);
		} catch (Throwable cause) {
			doError(Exceptions.addValueAsLastCause(cause, t));
		}
	}

	@Override
	protected void doNext(T value) {
		index++;
		if (first && index == 1) {
			firstCallback(value);
		}

		if (next) {
			nextCallback(value);
		}

		if (index % batchSize == 0) {
			index = 0;
			if (flush) {
				flushConsumer.accept(value);
			}
		}
	}

	@Override
	protected void doComplete() {
		flushConsumer.accept(null);
		super.doComplete();
	}

	@Override
	public void cancel() {
		if (timespanRegistration != null) timespanRegistration.cancel();
		super.cancel();
	}

	final private class FlushConsumer implements Consumer<T> {
		@Override
		public void accept(T n) {
			flushCallback(n);
			index = 0;
		}
	}

	@Override
	public String toString() {
		return super.toString() + "{" + (timespanRegistration != null ? "timed!" : "") + " batchSize=" + index + "/" +
				batchSize + " [" + (int) ((((float) index) / ((float) batchSize)) * 100) + "%]";
	}

	@Override
	public final Dispatcher getDispatcher() {
		return dispatcher;
	}
}
