/**
 * Copyright 2015 Netflix, Inc.
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
package com.netflix.hystrix.metric;

import com.netflix.hystrix.ExecutionResult;
import com.netflix.hystrix.HystrixCollapserKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixEventType;
import com.netflix.hystrix.HystrixThreadPool;
import com.netflix.hystrix.HystrixThreadPoolKey;
import rx.functions.Action1;
import rx.observers.Subscribers;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

/**
 * Per-thread event stream.  No synchronization required when writing to it since it's single-threaded.
 *
 * Some threads will be dedicated to a single HystrixCommandKey (a member of a thread-isolated {@link HystrixThreadPool}.
 * However, many situations arise where a single thread may serve many different commands.  Examples include:
 * * Application caller threads (semaphore-isolated commands, or thread-pool-rejections)
 * * Timer threads (timeouts or collapsers)
 * <p>
 * I don't think that a thread-level view is an interesting one to consume (I could be wrong), so at the moment there
 * is no public way to consume it.  I can always add it later, if desired.
 * <p>
 * Instead, this stream writes to the following streams, which have more meaning to metrics consumers:
 * <ul>
 *     <li>{@link HystrixCommandCompletionStream}</li>
 *     <li>{@link HystrixCommandStartStream}</li>
 *     <li>{@link HystrixThreadPoolCompletionStream}</li>
 *     <li>{@link HystrixThreadPoolStartStream}</li>
 *     <li>{@link HystrixCollapserEventStream}</li>
 * </ul>
 *
 * Also note that any observers of this stream do so on the thread that writes the metric.  This is the command caller
 * thread in the SEMAPHORE-isolated case, and the Hystrix thread in the THREAD-isolated case. I determined this to
 * be more efficient CPU-wise than immediately hopping off-thread and doing all the metric calculations in the
 * RxComputationThreadPool.
 */

/***
 * 这是一个事件处理者
 * 包含了很多的 Subject<事件,事件>
 */
public class HystrixThreadEventStream {
    private final long threadId;
    private final String threadName;

    private final Subject<HystrixCommandExecutionStarted, HystrixCommandExecutionStarted> writeOnlyCommandStartSubject;
    // 是用来接收和发射HystrixCommandCompletion事件的Subject
    private final Subject<HystrixCommandCompletion, HystrixCommandCompletion> writeOnlyCommandCompletionSubject;

    private final Subject<HystrixCollapserEvent, HystrixCollapserEvent> writeOnlyCollapserSubject;

    private static final ThreadLocal<HystrixThreadEventStream> threadLocalStreams = new ThreadLocal<HystrixThreadEventStream>() {
        @Override
        protected HystrixThreadEventStream initialValue() {
            return new HystrixThreadEventStream(Thread.currentThread());
        }
    };

    private static final Action1<HystrixCommandExecutionStarted> writeCommandStartsToShardedStreams = new Action1<HystrixCommandExecutionStarted>() {
        @Override
        public void call(HystrixCommandExecutionStarted event) {
            HystrixCommandStartStream commandStartStream = HystrixCommandStartStream.getInstance(event.getCommandKey());
            commandStartStream.write(event);

            if (event.isExecutedInThread()) {
                HystrixThreadPoolStartStream threadPoolStartStream = HystrixThreadPoolStartStream.getInstance(event.getThreadPoolKey());
                threadPoolStartStream.write(event);
            }
        }
    };
    //它是一个可执行的实体,没有返回值,可以传入一个参数; 和 Runnable很像
    //当HystrixThreadEventStream 接受到对应commandKey写入完成时，writeOnlyCommandCompletionSubject 会收到写入完成的通知，
    //writeOnlyCommandCompletionSubject收到通知后，就会把接收到的发射数据(写入完成的事件)交给writeCommandCompletionsToShardedStreams处理
    private static final Action1<HystrixCommandCompletion> writeCommandCompletionsToShardedStreams = new Action1<HystrixCommandCompletion>() {
        // 当接收到数据时, 又将数据发送给了command级别的处理者
        @Override
        public void call(HystrixCommandCompletion commandCompletion) {
           //根据CommandKey根据写入完成的事件所属的commandKey找到对应的HystrixCommandCompletionStream
            HystrixCommandCompletionStream commandStream = HystrixCommandCompletionStream.getInstance(commandCompletion.getCommandKey());
            //往HystrixCommandCompletionStream写入数据,交给对应的 HystrixCommandCompletionStream处理了
            commandStream.write(commandCompletion);

            if (commandCompletion.isExecutedInThread() || commandCompletion.isResponseThreadPoolRejected()) {
                HystrixThreadPoolCompletionStream threadPoolStream = HystrixThreadPoolCompletionStream.getInstance(commandCompletion.getThreadPoolKey());
                threadPoolStream.write(commandCompletion);
            }
        }
    };

    private static final Action1<HystrixCollapserEvent> writeCollapserExecutionsToShardedStreams = new Action1<HystrixCollapserEvent>() {
        @Override
        public void call(HystrixCollapserEvent collapserEvent) {
            HystrixCollapserEventStream collapserStream = HystrixCollapserEventStream.getInstance(collapserEvent.getCollapserKey());
            collapserStream.write(collapserEvent);
        }
    };

    /* package */ HystrixThreadEventStream(Thread thread) {
        this.threadId = thread.getId();
        this.threadName = thread.getName();
        writeOnlyCommandStartSubject = PublishSubject.create();
        writeOnlyCommandStartSubject
                .onBackpressureBuffer()
                .doOnNext(writeCommandStartsToShardedStreams)
                .unsafeSubscribe(Subscribers.empty());
        // 创建一个数据发射器
        writeOnlyCommandCompletionSubject = PublishSubject.create();
        writeOnlyCommandCompletionSubject
                .onBackpressureBuffer()
                // 绑定发射数据时的处理者，也就是接收到写入完成的事件后，发射数据的处理者
                .doOnNext(writeCommandCompletionsToShardedStreams)
                .unsafeSubscribe(Subscribers.empty());

        writeOnlyCollapserSubject = PublishSubject.create();
        writeOnlyCollapserSubject
                .onBackpressureBuffer()
                .doOnNext(writeCollapserExecutionsToShardedStreams)
                .unsafeSubscribe(Subscribers.empty());
    }

    public static HystrixThreadEventStream getInstance() {
        return threadLocalStreams.get();
    }

    public void shutdown() {
        writeOnlyCommandStartSubject.onCompleted();
        writeOnlyCommandCompletionSubject.onCompleted();
        writeOnlyCollapserSubject.onCompleted();
    }

    public void commandExecutionStarted(HystrixCommandKey commandKey, HystrixThreadPoolKey threadPoolKey,
                                        HystrixCommandProperties.ExecutionIsolationStrategy isolationStrategy, int currentConcurrency) {
        HystrixCommandExecutionStarted event = new HystrixCommandExecutionStarted(commandKey, threadPoolKey, isolationStrategy, currentConcurrency);
        writeOnlyCommandStartSubject.onNext(event);
    }

    /***
     * 由于writeOnlyCommandCompletionSubject绑定了数据处理者
     * (上面的writeCommandCompletionsToShardedStreams这个Action1)。它会利用command级别的工具来发射数据。
     * @param executionResult
     * @param commandKey
     * @param threadPoolKey
     */
    public void executionDone(ExecutionResult executionResult,
                              HystrixCommandKey commandKey,
                              HystrixThreadPoolKey threadPoolKey) {
        // 构建命令结束的数据对象
        HystrixCommandCompletion event = HystrixCommandCompletion.from(executionResult, commandKey, threadPoolKey);
        // 利用上面的Subject发射数据, onNext()就是发射一条数据。
        writeOnlyCommandCompletionSubject.onNext(event);
    }

    public void collapserResponseFromCache(HystrixCollapserKey collapserKey) {
        HystrixCollapserEvent collapserEvent = HystrixCollapserEvent.from(collapserKey, HystrixEventType.Collapser.RESPONSE_FROM_CACHE, 1);
        writeOnlyCollapserSubject.onNext(collapserEvent);
    }

    public void collapserBatchExecuted(HystrixCollapserKey collapserKey, int batchSize) {
        HystrixCollapserEvent batchExecution = HystrixCollapserEvent.from(collapserKey, HystrixEventType.Collapser.BATCH_EXECUTED, 1);
        HystrixCollapserEvent batchAdditions = HystrixCollapserEvent.from(collapserKey, HystrixEventType.Collapser.ADDED_TO_BATCH, batchSize);
        writeOnlyCollapserSubject.onNext(batchExecution);
        writeOnlyCollapserSubject.onNext(batchAdditions);
    }

    @Override
    public String toString() {
        return "HystrixThreadEventStream (" + threadId + " - " + threadName + ")";
    }
}
