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
package com.netflix.hystrix.contrib.javanica.command;

import com.netflix.hystrix.HystrixInvokable;
import com.netflix.hystrix.contrib.javanica.collapser.CommandCollapser;

/**
 * Created by dmgcodevil.
 */
public class HystrixCommandFactory {

    private static final HystrixCommandFactory INSTANCE = new HystrixCommandFactory();

    private HystrixCommandFactory() {

    }

    public static HystrixCommandFactory getInstance() {
        return INSTANCE;
    }

    public HystrixInvokable create(MetaHolder metaHolder) {
        HystrixInvokable executable;

        if (metaHolder.isCollapserAnnotationPresent()) {//是否配置hystrixCollapser注解
            executable = new CommandCollapser(metaHolder);
        } else if (metaHolder.isObservable()) {//是否是Objservable
            executable = new GenericObservableCommand(HystrixCommandBuilderFactory.getInstance().create(metaHolder));
        } else {//默认是一个HystrixCommand对象，继承了AbstractCommand
            executable = new GenericCommand(HystrixCommandBuilderFactory.getInstance()//调用HystrixCommandBuilderFactory实例来创建
                    .create(metaHolder));
        }
        return executable;
    }

    public HystrixInvokable createDelayed(MetaHolder metaHolder) {
        HystrixInvokable executable;
        if (metaHolder.isObservable()) {
            executable = new GenericObservableCommand(HystrixCommandBuilderFactory.getInstance().create(metaHolder));
        } else {
            executable = new GenericCommand(HystrixCommandBuilderFactory.getInstance().create(metaHolder));
        }
        return executable;
    }
}
