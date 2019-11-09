/**
 * Copyright 2012 Netflix, Inc.
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
package com.netflix.hystrix.contrib.javanica.aop.aspectj;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.netflix.hystrix.HystrixInvokable;
import com.netflix.hystrix.contrib.javanica.annotation.DefaultProperties;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCollapser;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixException;
import com.netflix.hystrix.contrib.javanica.command.CommandExecutor;
import com.netflix.hystrix.contrib.javanica.command.ExecutionType;
import com.netflix.hystrix.contrib.javanica.command.HystrixCommandFactory;
import com.netflix.hystrix.contrib.javanica.command.MetaHolder;
import com.netflix.hystrix.contrib.javanica.exception.CommandActionExecutionException;
import com.netflix.hystrix.contrib.javanica.exception.FallbackInvocationException;
import com.netflix.hystrix.contrib.javanica.utils.AopUtils;
import com.netflix.hystrix.contrib.javanica.utils.FallbackMethod;
import com.netflix.hystrix.contrib.javanica.utils.MethodProvider;
import com.netflix.hystrix.exception.HystrixBadRequestException;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import rx.Completable;
import rx.Observable;
import rx.Single;
import rx.functions.Func1;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import static com.netflix.hystrix.contrib.javanica.utils.AopUtils.getDeclaredMethod;
import static com.netflix.hystrix.contrib.javanica.utils.AopUtils.getMethodFromTarget;
import static com.netflix.hystrix.contrib.javanica.utils.AopUtils.getMethodInfo;
import static com.netflix.hystrix.contrib.javanica.utils.EnvUtils.isCompileWeaving;
import static com.netflix.hystrix.contrib.javanica.utils.ajc.AjcUtils.getAjcMethodAroundAdvice;

/**
 * AspectJ aspect to process methods which annotated with {@link HystrixCommand} annotation.
 */
@Aspect
public class HystrixCommandAspect {

    private static final Map<HystrixPointcutType, MetaHolderFactory> META_HOLDER_FACTORY_MAP;

    static {
        META_HOLDER_FACTORY_MAP = ImmutableMap.<HystrixPointcutType, MetaHolderFactory>builder()
                .put(HystrixPointcutType.COMMAND, new CommandMetaHolderFactory())
                .put(HystrixPointcutType.COLLAPSER, new CollapserMetaHolderFactory())
                .build();
    }

    /***
     * 拦截HystrixCommand注解
     */
    @Pointcut("@annotation(com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand)")

    public void hystrixCommandAnnotationPointcut() {
    }
    /***
     * 拦截HystrixCollapser注解
     */
    @Pointcut("@annotation(com.netflix.hystrix.contrib.javanica.annotation.HystrixCollapser)")
    public void hystrixCollapserAnnotationPointcut() {
    }

    @Around("hystrixCommandAnnotationPointcut() || hystrixCollapserAnnotationPointcut()")
    public Object methodsAnnotatedWithHystrixCommand(final ProceedingJoinPoint joinPoint) throws Throwable {
        //获得添加注解的方法(从上面的切面可以看出，用来处理HystrixCommand和HystrixCollapser注解)
        Method method = getMethodFromTarget(joinPoint);
        Validate.notNull(method, "failed to get method from joinPoint: %s", joinPoint);
        //判断是否同时指定了HystrixCommand和HystrixCollapser注解
        if (method.isAnnotationPresent(HystrixCommand.class) && method.isAnnotationPresent(HystrixCollapser.class)) {
            throw new IllegalStateException("method cannot be annotated with HystrixCommand and HystrixCollapser " +
                    "annotations at the same time");
        }
        //根据注解类型，检查本地内存里是否已存在 MetaHolderFactory。内置两种: COMMAND和COLLAPSER，在初始化类的时候添加的
        MetaHolderFactory metaHolderFactory = META_HOLDER_FACTORY_MAP.get(HystrixPointcutType.of(method));
        //根据MetaHolderFactory解析切面属性并返回注解元信息。返回的MetaHolder包含了HystrixCommand方法HystrixCommandFactory的所有元信息
        MetaHolder metaHolder = metaHolderFactory.create(joinPoint);
        //根据MetaHolder创建一个HystrixCommand实例
        HystrixInvokable invokable = HystrixCommandFactory.getInstance().create(metaHolder);
        //如果配置了Collapser，则获得Collapser的ExecutionType,不然就获得HystrixCommand的executionType
        ExecutionType executionType = metaHolder.isCollapserAnnotationPresent() ?
                metaHolder.getCollapserExecutionType() : metaHolder.getExecutionType();

        Object result;
        try {
            if (!metaHolder.isObservable()) {//如果不是Observable的HystrixCommand
                //开始执行HytrixCommand
                result = CommandExecutor.execute(invokable, executionType, metaHolder);
            } else {
                result = executeObservable(invokable, executionType, metaHolder);
            }
        } catch (HystrixBadRequestException e) {
            throw e.getCause() != null ? e.getCause() : e;
        } catch (HystrixRuntimeException e) {
            throw hystrixRuntimeExceptionToThrowable(metaHolder, e);
        }
        return result;
    }

    private Object executeObservable(HystrixInvokable invokable, ExecutionType executionType, final MetaHolder metaHolder) {
        return mapObservable(((Observable) CommandExecutor.execute(invokable, executionType, metaHolder))
                .onErrorResumeNext(new Func1<Throwable, Observable>() {
                    @Override
                    public Observable call(Throwable throwable) {
                        if (throwable instanceof HystrixBadRequestException) {
                            return Observable.error(throwable.getCause());
                        } else if (throwable instanceof HystrixRuntimeException) {
                            HystrixRuntimeException hystrixRuntimeException = (HystrixRuntimeException) throwable;
                            return Observable.error(hystrixRuntimeExceptionToThrowable(metaHolder, hystrixRuntimeException));
                        }
                        return Observable.error(throwable);
                    }
                }), metaHolder);
    }

    private Object mapObservable(Observable observable, final MetaHolder metaHolder) {
        if (Completable.class.isAssignableFrom(metaHolder.getMethod().getReturnType())) {
            return observable.toCompletable();
        } else if (Single.class.isAssignableFrom(metaHolder.getMethod().getReturnType())) {
            return observable.toSingle();
        }
        return observable;
    }

    private Throwable hystrixRuntimeExceptionToThrowable(MetaHolder metaHolder, HystrixRuntimeException e) {
        if (metaHolder.raiseHystrixExceptionsContains(HystrixException.RUNTIME_EXCEPTION)) {
            return e;
        }
        return getCause(e);
    }

    private Throwable getCause(HystrixRuntimeException e) {
        if (e.getFailureType() != HystrixRuntimeException.FailureType.COMMAND_EXCEPTION) {
            return e;
        }

        Throwable cause = e.getCause();

        // latest exception in flow should be propagated to end user
        if (e.getFallbackException() instanceof FallbackInvocationException) {
            cause = e.getFallbackException().getCause();
            if (cause instanceof HystrixRuntimeException) {
                cause = getCause((HystrixRuntimeException) cause);
            }
        } else if (cause instanceof CommandActionExecutionException) { // this situation is possible only if a callee throws an exception which type extends Throwable directly
            CommandActionExecutionException commandActionExecutionException = (CommandActionExecutionException) cause;
            cause = commandActionExecutionException.getCause();
        }

        return Optional.fromNullable(cause).or(e);
    }

    /**
     * A factory to create MetaHolder depending on {@link HystrixPointcutType}.
     */
    private static abstract class MetaHolderFactory {
        public MetaHolder create(final ProceedingJoinPoint joinPoint) {
            //添加HystrixCommand或HystrixCollaper注解方法
            Method method = getMethodFromTarget(joinPoint);
            Object obj = joinPoint.getTarget();//添加了注解的被代理对象
            Object[] args = joinPoint.getArgs();//方法参数
            Object proxy = joinPoint.getThis();//代理对象

            //调用CommandMetaHolderFactory或CollapserMetaHolderFactory创建MetaHolder
            //MetaHolder绑定了一个builder
            return create(proxy, method, obj, args, joinPoint);
        }

        public abstract MetaHolder create(Object proxy, Method method, Object obj, Object[] args, final ProceedingJoinPoint joinPoint);

        /***
         * 根据信息初始化一个MetaHolder
         * @param proxy 代理对象
         * @param method 方法
         * @param obj 被代理对象
         * @param args 参数
         * @param joinPoint 切点
         * @return
         */
        MetaHolder.Builder metaHolderBuilder(Object proxy, Method method, Object obj, Object[] args, final ProceedingJoinPoint joinPoint) {
            //根据出事参数构建一个MetaHolder的构建器
            MetaHolder.Builder builder = MetaHolder.builder()
                    .args(args).method(method).obj(obj).proxyObj(proxy)
                    .joinPoint(joinPoint);
            //根据类和HystrixCommand初始化fallBack对象
            setFallbackMethod(builder, obj.getClass(), method);
            //根据DefaultProperties注解，设置builder中默认的DefaultProperties
            builder = setDefaultProperties(builder, obj.getClass(), joinPoint);
            return builder;
        }
    }

    private static class CollapserMetaHolderFactory extends MetaHolderFactory {

        @Override
        public MetaHolder create(Object proxy, Method collapserMethod, Object obj, Object[] args, final ProceedingJoinPoint joinPoint) {
            HystrixCollapser hystrixCollapser = collapserMethod.getAnnotation(HystrixCollapser.class);
            if (collapserMethod.getParameterTypes().length > 1 || collapserMethod.getParameterTypes().length == 0) {
                throw new IllegalStateException("Collapser method must have one argument: " + collapserMethod);
            }

            Method batchCommandMethod = getDeclaredMethod(obj.getClass(), hystrixCollapser.batchMethod(), List.class);

            if (batchCommandMethod == null)
                throw new IllegalStateException("batch method is absent: " + hystrixCollapser.batchMethod());

            Class<?> batchReturnType = batchCommandMethod.getReturnType();
            Class<?> collapserReturnType = collapserMethod.getReturnType();
            boolean observable = collapserReturnType.equals(Observable.class);

            if (!collapserMethod.getParameterTypes()[0]
                    .equals(getFirstGenericParameter(batchCommandMethod.getGenericParameterTypes()[0]))) {
                throw new IllegalStateException("required batch method for collapser is absent, wrong generic type: expected "
                        + obj.getClass().getCanonicalName() + "." +
                        hystrixCollapser.batchMethod() + "(java.util.List<" + collapserMethod.getParameterTypes()[0] + ">), but it's " +
                        getFirstGenericParameter(batchCommandMethod.getGenericParameterTypes()[0]));
            }

            final Class<?> collapserMethodReturnType = getFirstGenericParameter(
                    collapserMethod.getGenericReturnType(),
                    Future.class.isAssignableFrom(collapserReturnType) || Observable.class.isAssignableFrom(collapserReturnType) ? 1 : 0);

            Class<?> batchCommandActualReturnType = getFirstGenericParameter(batchCommandMethod.getGenericReturnType());
            if (!collapserMethodReturnType
                    .equals(batchCommandActualReturnType)) {
                throw new IllegalStateException("Return type of batch method must be java.util.List parametrized with corresponding type: expected " +
                        "(java.util.List<" + collapserMethodReturnType + ">)" + obj.getClass().getCanonicalName() + "." +
                        hystrixCollapser.batchMethod() + "(java.util.List<" + collapserMethod.getParameterTypes()[0] + ">), but it's " +
                        batchCommandActualReturnType);
            }

            HystrixCommand hystrixCommand = batchCommandMethod.getAnnotation(HystrixCommand.class);
            if (hystrixCommand == null) {
                throw new IllegalStateException("batch method must be annotated with HystrixCommand annotation");
            }
            // method of batch hystrix command must be passed to metaholder because basically collapser doesn't have any actions
            // that should be invoked upon intercepted method, it's required only for underlying batch command

            MetaHolder.Builder builder = metaHolderBuilder(proxy, batchCommandMethod, obj, args, joinPoint);

            if (isCompileWeaving()) {
                builder.ajcMethod(getAjcMethodAroundAdvice(obj.getClass(), batchCommandMethod.getName(), List.class));
            }

            builder.hystrixCollapser(hystrixCollapser);
            builder.defaultCollapserKey(collapserMethod.getName());
            builder.collapserExecutionType(ExecutionType.getExecutionType(collapserReturnType));

            builder.defaultCommandKey(batchCommandMethod.getName());
            builder.hystrixCommand(hystrixCommand);
            builder.executionType(ExecutionType.getExecutionType(batchReturnType));
            builder.observable(observable);
            FallbackMethod fallbackMethod = MethodProvider.getInstance().getFallbackMethod(obj.getClass(), batchCommandMethod);
            if (fallbackMethod.isPresent()) {
                fallbackMethod.validateReturnType(batchCommandMethod);
                builder
                        .fallbackMethod(fallbackMethod.getMethod())
                        .fallbackExecutionType(ExecutionType.getExecutionType(fallbackMethod.getMethod().getReturnType()));
            }
            return builder.build();
        }
    }

    /***
     * CommandMetaHolderFactory用于解析HystixCommand注解，并返回一个MethodHolder
     *
     *
     */
    private static class CommandMetaHolderFactory extends MetaHolderFactory {
        @Override
        public MetaHolder create(Object proxy, Method method, Object obj, Object[] args, final ProceedingJoinPoint joinPoint) {
            //获得 HystrixCommand注解
            HystrixCommand hystrixCommand = method.getAnnotation(HystrixCommand.class);
            //根据返回参数获得执行类型:ASYNCHRONOUS/OBSERVABLE/SYNCHRONOUS
            //  ASYNCHRONOUS：返回值中是Future类型
            //  OBSERVABLE：返回值中带OBSERVABLE/Single/Completable
            //  SYNCHRONOUS：除了上面两种之外

            ExecutionType executionType = ExecutionType.getExecutionType(method.getReturnType());
            //根据基本参数初始化一个MetaHolder.Builder，在这一步会绑定fallback方法，并初始化DefaultProperties
            MetaHolder.Builder builder = metaHolderBuilder(proxy, method, obj, args, joinPoint);
            if (isCompileWeaving()) {
                builder.ajcMethod(getAjcMethodFromTarget(joinPoint));
            }
            return builder.defaultCommandKey(method.getName())//根据方法名，设置默认的commandKey
                            .hystrixCommand(hystrixCommand)//绑定整个HystrixCommand注解
                            .observableExecutionMode(hystrixCommand.observableExecutionMode())//默认是null
                            .executionType(executionType)//设置builder的执行类型,这个参数根据hystrixCommand的返回类型确定
                            .observable(ExecutionType.OBSERVABLE == executionType)//标示是否是observable
                            .build();
        }
    }

    private enum HystrixPointcutType {
        COMMAND,
        COLLAPSER;

        /***
         * 获得注解类型
         * @param method
         * @return
         */
        static HystrixPointcutType of(Method method) {
            /***
             * 如果注解是HystrixCommand，则返回COMMAND
             */
            if (method.isAnnotationPresent(HystrixCommand.class)) {
                return COMMAND;
            } else if (method.isAnnotationPresent(HystrixCollapser.class)) {//如果注解是HystrixCollapser，则返回COLLAPSER
                return COLLAPSER;
            } else {
                String methodInfo = getMethodInfo(method);//不然就获得方法名，然后提示报错
                throw new IllegalStateException("'https://github.com/Netflix/Hystrix/issues/1458' - no valid annotation found for: \n" + methodInfo);
            }
        }
    }

    private static Method getAjcMethodFromTarget(JoinPoint joinPoint) {
        return getAjcMethodAroundAdvice(joinPoint.getTarget().getClass(), (MethodSignature) joinPoint.getSignature());
    }


    private static Class<?> getFirstGenericParameter(Type type) {
        return getFirstGenericParameter(type, 1);
    }

    private static Class<?> getFirstGenericParameter(final Type type, final int nestedDepth) {
        int cDepth = 0;
        Type tType = type;

        for (int cDept = 0; cDept < nestedDepth; cDept++) {
            if (!(tType instanceof ParameterizedType))
                throw new IllegalStateException(String.format("Sub type at nesting level %d of %s is expected to be generic", cDepth, type));
            tType = ((ParameterizedType) tType).getActualTypeArguments()[cDept];
        }

        if (tType instanceof ParameterizedType)
            return (Class<?>) ((ParameterizedType) tType).getRawType();
        else if (tType instanceof Class)
            return (Class<?>) tType;

        throw new UnsupportedOperationException("Unsupported type " + tType);
    }

    /***
     * 开始为MetaHolder.Builder设置默认的 CommandProperties
     * @param builder  MetaHolder.Builder
     * @param declaringClass 添加hystrixCommand注解的类
     * @param joinPoint 切点
     * @return
     *
     * 回设置默认的CommandProperties，同时根据 DefaultProperties更新默认的groupKey和threadPoolKey
     *
     */
    private static MetaHolder.Builder setDefaultProperties(MetaHolder.Builder builder, Class<?> declaringClass, final ProceedingJoinPoint joinPoint) {
        //获得DefaultProperties注解
        Optional<DefaultProperties> defaultPropertiesOpt = AopUtils.getAnnotation(joinPoint, DefaultProperties.class);
        //默认的groupKey是类名
        builder.defaultGroupKey(declaringClass.getSimpleName());

        if (defaultPropertiesOpt.isPresent()) {//判断是否配置了DefaultProperties注解
            //获得对应的DefaultProperties注解对象
            DefaultProperties defaultProperties = defaultPropertiesOpt.get();
            builder.defaultProperties(defaultProperties);
            //使用DefaultProperties注解中的groupkey属性替换默认的类名
            if (StringUtils.isNotBlank(defaultProperties.groupKey())) {
                builder.defaultGroupKey(defaultProperties.groupKey());
            }
            if (StringUtils.isNotBlank(defaultProperties.threadPoolKey())) {
                builder.defaultThreadPoolKey(defaultProperties.threadPoolKey());
            }
        }
        return builder;
    }

    /***
     * 为Metaolder.Builder绑定fallbackMethod,并根据commandMethod校验fallbackMethod参数和返回类型
     * @param builder
     * @param declaringClass
     * @param commandMethod
     * @return
     */
    private static MetaHolder.Builder setFallbackMethod(MetaHolder.Builder builder, Class<?> declaringClass, Method commandMethod) {
        /**
         * 根据类和hystrixCommand注解的方法，初始化一个FallbackMethod对象
         */
        FallbackMethod fallbackMethod = MethodProvider.getInstance().getFallbackMethod(declaringClass, commandMethod);
        if (fallbackMethod.isPresent()) {//如果fallback方法存在
            //校验fallbackMethod返回类型和commandMethod的参数要匹配
            fallbackMethod.validateReturnType(commandMethod);//校验fallbackMethod参数
            builder
                    .fallbackMethod(fallbackMethod.getMethod())//将fallbackMethod绑定到MetaHolder.Builder上
                    .fallbackExecutionType(//设置fallbackMethod对应的执行类型
                            ExecutionType.getExecutionType(//根据fallbackMethod返回类型获得执行执行类型ASYNCHRONOUS,SYNCHRONOUS,OBSERVABLE
                                    fallbackMethod.getMethod().getReturnType()//获得fallbackMethod返回类型
                            )
                    );
        }
        return builder;
    }

}
