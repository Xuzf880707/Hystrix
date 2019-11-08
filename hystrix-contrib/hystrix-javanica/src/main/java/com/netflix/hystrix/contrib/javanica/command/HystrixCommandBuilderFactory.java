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

import com.netflix.hystrix.HystrixCollapser;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.utils.FallbackMethod;
import com.netflix.hystrix.contrib.javanica.utils.MethodProvider;
import org.apache.commons.lang3.Validate;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;

import static com.netflix.hystrix.contrib.javanica.cache.CacheInvocationContextFactory.createCacheRemoveInvocationContext;
import static com.netflix.hystrix.contrib.javanica.cache.CacheInvocationContextFactory.createCacheResultInvocationContext;
import static com.netflix.hystrix.contrib.javanica.utils.EnvUtils.isCompileWeaving;
import static com.netflix.hystrix.contrib.javanica.utils.ajc.AjcUtils.getAjcMethodAroundAdvice;

/**
 * Created by dmgcodevil.
 */
public class HystrixCommandBuilderFactory {

    // todo Add Cache

    private static final HystrixCommandBuilderFactory INSTANCE = new HystrixCommandBuilderFactory();

    public static HystrixCommandBuilderFactory getInstance() {
        return INSTANCE;
    }

    private HystrixCommandBuilderFactory() {

    }

    /****
     * 根据HytrixCommand配置，构建一个HystrixCommandBuilder
     * @param metaHolder
     * @return
     */
    public HystrixCommandBuilder create(MetaHolder metaHolder) {
        return create(metaHolder, Collections.<HystrixCollapser.CollapsedRequest<Object, Object>>emptyList());
    }

    /***
     * 创建一个HystrixCommand的构建器
     * @param metaHolder
     * @param collapsedRequests
     * @param <ResponseType>
     * @return
     */
    public <ResponseType> HystrixCommandBuilder create(MetaHolder metaHolder, Collection<HystrixCollapser.CollapsedRequest<ResponseType, Object>> collapsedRequests) {
        validateMetaHolder(metaHolder);

        return HystrixCommandBuilder.builder()
                .setterBuilder(createGenericSetterBuilder(metaHolder))//创建一个构建器GenericSetterBuilder，并初始化注解的配置
                .commandActions(createCommandActions(metaHolder))//调用createCommandActions创建CommandActions
                .collapsedRequests(collapsedRequests)//创建合并的请求
                .cacheResultInvocationContext(createCacheResultInvocationContext(metaHolder))//创建绑定添加缓存结果的上下文的对象
                .cacheRemoveInvocationContext(createCacheRemoveInvocationContext(metaHolder))//创建绑定移除缓存结果的上下文的对象
                .ignoreExceptions(metaHolder.getCommandIgnoreExceptions())//初始化护忽视的异常列表
                .executionType(metaHolder.getExecutionType())//设置执行类型。默认是同步的
                .build();
    }

    private void validateMetaHolder(MetaHolder metaHolder) {
        Validate.notNull(metaHolder, "metaHolder is required parameter and cannot be null");
        Validate.isTrue(metaHolder.isCommandAnnotationPresent(), "hystrixCommand annotation is absent");
    }

    /***
     * 构建一个 GenericSetterBuilder 用于包装创建CommandActions,并调用方法执行
     * @param metaHolder
     * @return
     */
    private GenericSetterBuilder createGenericSetterBuilder(MetaHolder metaHolder) {
        GenericSetterBuilder.Builder setterBuilder = GenericSetterBuilder.builder()
                .groupKey(metaHolder.getCommandGroupKey())//设置groupKey
                .threadPoolKey(metaHolder.getThreadPoolKey())//设置ThreadPoolKey
                .commandKey(metaHolder.getCommandKey())//设置CommandKey
                .collapserKey(metaHolder.getCollapserKey())//设置CollapserKey
                .commandProperties(metaHolder.getCommandProperties())
                .threadPoolProperties(metaHolder.getThreadPoolProperties())//设置线程池属性
                .collapserProperties(metaHolder.getCollapserProperties());
        //如果配置了HystrixCollapser注解的话，则scope默认是REQUEST，并根据配置具体配置设值。
        //如果没有配置HystrixCollapser注解的话，则scope默认是null
        if (metaHolder.isCollapserAnnotationPresent()) {
            setterBuilder.scope(metaHolder.getHystrixCollapser().scope());
        }
        //构建一个GenericSetterBuilder
        return setterBuilder.build();
    }

    private CommandActions createCommandActions(MetaHolder metaHolder) {
        //根据metaHolder创建一个CommandAction，这个metaHolder包含我们所有的注解配置
        CommandAction commandAction = createCommandAction(metaHolder);
        //创建一个commandAction对应的fallbackAction
        CommandAction fallbackAction = createFallbackAction(metaHolder);
        //利用CommandActions.builder创建一个CommandActions，并绑定commandAction和fallbackAction
        return CommandActions.builder().commandAction(commandAction)
                .fallbackAction(fallbackAction).build();
    }

    /***
     * 创建一个CommandAction对象，封装了command的行为
     * @param metaHolder
     * @return
     */
    private CommandAction createCommandAction(MetaHolder metaHolder) {
        /***
         * metaHolder.getObj()： 添加了HystrixCommand注解的实体类对象
         * metaHolder.getMethod()：添加了HystrixCommand注解的实体类对象的方法
         * metaHolder.getArgs()：添加了HystrixCommand注解的实体类对象的方法的参数
         *
         */
        return new MethodExecutionAction(metaHolder.getObj(), metaHolder.getMethod(), metaHolder.getArgs(), metaHolder);
    }

    private CommandAction createFallbackAction(MetaHolder metaHolder) {
        //在HystrixCommand中查找相应的fallback方法，没有定义的话，返回默认的
        FallbackMethod fallbackMethod = MethodProvider.getInstance().getFallbackMethod(metaHolder.getObj().getClass(),
                metaHolder.getMethod(), metaHolder.isExtendedFallback());
        //校验fallback方法参数和真正的调用方法的参数的返回类型
        ////检查fallback方法和command注解的方法的返回值是否匹配
        fallbackMethod.validateReturnType(metaHolder.getMethod());
        CommandAction fallbackAction = null;
        if (fallbackMethod.isPresent()) {
            //获得fallback方法对象
            Method fMethod = fallbackMethod.getMethod();
            //判断这个fallbackMethod是否是默认的降级方法，如果是是的，就是无参数组，不然返回实际的参数数组
            Object[] args = fallbackMethod.isDefault() ? new Object[0] : metaHolder.getArgs();
            if (fallbackMethod.isCommand()) {//判断这个fallback自己本身是否是一个HystrixCommand方法
                fMethod.setAccessible(true);
                HystrixCommand hystrixCommand = fMethod.getAnnotation(HystrixCommand.class);
                MetaHolder fmMetaHolder = MetaHolder.builder()
                        .obj(metaHolder.getObj())
                        .method(fMethod)
                        .ajcMethod(getAjcMethod(metaHolder.getObj(), fMethod))
                        .args(args)
                        .fallback(true)
                        .defaultFallback(fallbackMethod.isDefault())
                        .defaultCollapserKey(metaHolder.getDefaultCollapserKey())
                        .fallbackMethod(fMethod)
                        .extendedFallback(fallbackMethod.isExtended())
                        .fallbackExecutionType(fallbackMethod.getExecutionType())
                        .extendedParentFallback(metaHolder.isExtendedFallback())
                        .observable(ExecutionType.OBSERVABLE == fallbackMethod.getExecutionType())
                        .defaultCommandKey(fMethod.getName())
                        .defaultGroupKey(metaHolder.getDefaultGroupKey())
                        .defaultThreadPoolKey(metaHolder.getDefaultThreadPoolKey())
                        .defaultProperties(metaHolder.getDefaultProperties().orNull())
                        .hystrixCollapser(metaHolder.getHystrixCollapser())
                        .observableExecutionMode(hystrixCommand.observableExecutionMode())
                        .hystrixCommand(hystrixCommand).build();
                fallbackAction = new LazyCommandExecutionAction(fmMetaHolder);
            } else {
                //创建一个MetaHolder
                MetaHolder fmMetaHolder = MetaHolder.builder()
                        .obj(metaHolder.getObj())//实际被降级的方法对象
                        .defaultFallback(fallbackMethod.isDefault())//是否是默认降级方法
                        .method(fMethod)//降级方法
                        .fallbackExecutionType(ExecutionType.SYNCHRONOUS)//降级方法的执行类型，默认是同步的
                        .extendedFallback(fallbackMethod.isExtended())//默认是true
                        .extendedParentFallback(metaHolder.isExtendedFallback())//默认是false
                        .ajcMethod(null) // if fallback method isn't annotated with command annotation then we don't need to get ajc method for this
                        .args(args)//方法参数
                        .build();//根据GenericSetterBuilder初始化其他对应的参数

                //返回一个MethodExecutionAction，和上面的
                fallbackAction = new MethodExecutionAction(fmMetaHolder.getObj(), fMethod, fmMetaHolder.getArgs(), fmMetaHolder);
            }

        }
        return fallbackAction;
    }

    private Method getAjcMethod(Object target, Method fallback) {
        if (isCompileWeaving()) {
            return getAjcMethodAroundAdvice(target.getClass(), fallback);
        }
        return null;
    }

}
