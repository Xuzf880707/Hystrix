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

import com.netflix.hystrix.HystrixCommandKey;
import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.SerializedSubject;
import rx.subjects.Subject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-Command stream of {@link HystrixCommandCompletion}s.  This gets written to by {@link HystrixThreadEventStream}s.
 * Events are emitted synchronously in the same thread that performs the command execution.
 */
public class HystrixCommandCompletionStream implements HystrixEventStream<HystrixCommandCompletion> {
    private final HystrixCommandKey commandKey;
    // 一个用于接收和发射结束事件的Subject
    private final Subject<HystrixCommandCompletion, HystrixCommandCompletion> writeOnlySubject;
    // 一个Observable，将接收到的数据作为数据源发射给其他消费者
    private final Observable<HystrixCommandCompletion> readOnlyStream;
    // 存储结构
    private static final ConcurrentMap<String, HystrixCommandCompletionStream> streams = new ConcurrentHashMap<String, HystrixCommandCompletionStream>();
    // 单例模式拿到HystrixCommandCompletionStream,以命令的key为索引存储在ConcurrentMap中
    public static HystrixCommandCompletionStream getInstance(HystrixCommandKey commandKey) {
        HystrixCommandCompletionStream initialStream = streams.get(commandKey.name());
        if (initialStream != null) {
            return initialStream;
        } else {
            synchronized (HystrixCommandCompletionStream.class) {
                HystrixCommandCompletionStream existingStream = streams.get(commandKey.name());
                if (existingStream == null) {
                    HystrixCommandCompletionStream newStream = new HystrixCommandCompletionStream(commandKey);
                    streams.putIfAbsent(commandKey.name(), newStream);
                    return newStream;
                } else {
                    return existingStream;
                }
            }
        }
    }

    HystrixCommandCompletionStream(final HystrixCommandKey commandKey) {
        this.commandKey = commandKey;
        // 创建可以发射数据的Subject
        this.writeOnlySubject = new SerializedSubject<HystrixCommandCompletion, HystrixCommandCompletion>(PublishSubject.<HystrixCommandCompletion>create());
        // readOnlyStream是一个Observable, share()方法可以将上面Subject发射的数据全部广播给readOnlyStream,相当于拷贝了一份一模一样的数据
        this.readOnlyStream = writeOnlySubject.share();
    }

    public static void reset() {
        streams.clear();
    }
    // 提供了接收数据的方法,其他工具(如HystrixThreadEventStream)可以将数据写进来
    public void write(HystrixCommandCompletion event) {
        writeOnlySubject.onNext(event);
    }

    // 实现HystrixEventStream的observe(方法), 其他消费者可以利用observe()拿到这个数据源，然后订阅它，处理它发射的所有数据
    @Override
    public Observable<HystrixCommandCompletion> observe() {
        return readOnlyStream;
    }

    @Override
    public String toString() {
        return "HystrixCommandCompletionStream(" + commandKey.name() + ")";
    }
}
