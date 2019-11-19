/**
 * Copyright 2015 Netflix, Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.metric;

import rx.Observable;

/**
 * Base interface for a stream of {@link com.netflix.hystrix.HystrixEventType}s.  Allows consumption by individual
 * {@link com.netflix.hystrix.HystrixEventType} or by time-based bucketing of events
 *
 * HystrixEventStream专门用于处理command级别的数据，它有如下几个子类：
 *      HystrixCommandCompletionStream
 *      HystrixCommandStartStream
 *      HystrixThreadPoolCompletionStream
 *      HystrixThreadPoolStartStream
 *      HystrixCollapserEventStream
 *
 *      这几个子类都是用来处理特定类型事件的工具，以 HystrixCommandCompletionStream 为例子，这些子类的结构都很类似，可以接收数据，并将数据提供给其他消费者。
 */
public interface HystrixEventStream<E extends HystrixEvent> {

    Observable<E> observe();
}
