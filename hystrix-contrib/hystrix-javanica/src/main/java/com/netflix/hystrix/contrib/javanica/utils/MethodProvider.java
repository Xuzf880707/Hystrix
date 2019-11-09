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
package com.netflix.hystrix.contrib.javanica.utils;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.netflix.hystrix.contrib.javanica.annotation.DefaultProperties;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.exception.FallbackDefinitionException;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.objectweb.asm.Opcodes.ACC_BRIDGE;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ASM5;

/**
 * Created by dmgcodevil
 * 这个类维护了一个单实例的MethodProvider
 *
 */
public final class MethodProvider {

    private MethodProvider() {

    }

    private static final MethodProvider INSTANCE = new MethodProvider();

    public static MethodProvider getInstance() {
        return INSTANCE;
    }

    private static final FallbackMethodFinder FALLBACK_METHOD_FINDER = new SpecificFallback(new DefaultCallback());

    private Map<Method, Method> cache = new ConcurrentHashMap<Method, Method>();

    /***
     *
     * @param type  添加了HytstrixCommand注解的类
     * @param commandMethod 添加了HytstrixCommand注解的方法
     * @return
     */
    public FallbackMethod getFallbackMethod(Class<?> type, Method commandMethod) {
        return getFallbackMethod(type, commandMethod, false);
    }

    /**
     * Gets fallback method for command method.
     *
     * @param enclosingType the enclosing class
     * @param commandMethod the command method. in the essence it can be a fallback
     *                      method annotated with HystrixCommand annotation that has a fallback as well.
     * @param extended      true if the given commandMethod was derived using additional parameter, otherwise - false
     * @return new instance of {@link FallbackMethod} or {@link FallbackMethod#ABSENT} if there is no suitable fallback method for the given command
     */
    /***
     *
     * @param enclosingType 添加了HystrixCommand注解的类
     * @param commandMethod  添加了HystrixCommand的注解的类的方法
     * @param extended 是否支持拓展，默认是false
     * @return
     */
    public FallbackMethod getFallbackMethod(Class<?> enclosingType, Method commandMethod, boolean extended) {
        //判断方法上是否有HystrixCommand注解，有的话则查找是否定义了相应的fallbackMethod
        if (commandMethod.isAnnotationPresent(HystrixCommand.class)) {
            //根据
            return FALLBACK_METHOD_FINDER.find(enclosingType, commandMethod, extended);
        }
        return FallbackMethod.ABSENT;
    }

    private void getDefaultFallback(){

    }

    private String getClassLevelFallback(Class<?> enclosingClass) {
        if (enclosingClass.isAnnotationPresent(DefaultProperties.class)) {
            return enclosingClass.getAnnotation(DefaultProperties.class).defaultFallback();
        }
        return StringUtils.EMPTY;
    }

    private static class SpecificFallback extends FallbackMethodFinder {

        public SpecificFallback(FallbackMethodFinder next) {
            super(next);
        }

        @Override
        boolean isSpecific() {
            return true;
        }

        /***
         *
         * @param enclosingType 添加了HystrixCommand注解的类
         * @param commandMethod  添加了HystrixCommand的注解的类的方法
         * @return
         *
         * 检查HystrixCommand是否配置了对应的fallbakc方法
         */
        @Override
        public String getFallbackName(Class<?> enclosingType, Method commandMethod) {
            return commandMethod.getAnnotation(HystrixCommand.class).fallbackMethod();
        }

        /***
         *
         * @param enclosingType 添加了HystrixCommand注解的类
         * @param commandMethod  添加了HystrixCommand的注解的类的方法
         * @return
         */
        @Override
        boolean canHandle(Class<?> enclosingType, Method commandMethod) {
            return StringUtils.isNotBlank(getFallbackName(enclosingType, commandMethod));
        }
    }

    private static class DefaultCallback extends FallbackMethodFinder {
        @Override
        boolean isDefault() {
            return true;
        }

        @Override
        public String getFallbackName(Class<?> enclosingType, Method commandMethod) {
            String commandDefaultFallback = commandMethod.getAnnotation(HystrixCommand.class).defaultFallback();
            String classDefaultFallback = Optional.fromNullable(enclosingType.getAnnotation(DefaultProperties.class))
                    .transform(new Function<DefaultProperties, String>() {
                        @Override
                        public String apply(DefaultProperties input) {
                            return input.defaultFallback();
                        }
                    }).or(StringUtils.EMPTY);

            return StringUtils.defaultIfEmpty(commandDefaultFallback, classDefaultFallback);
        }

        @Override
        boolean canHandle(Class<?> enclosingType, Method commandMethod) {
            return StringUtils.isNotBlank(getFallbackName(enclosingType, commandMethod));
        }
    }

    private static abstract class FallbackMethodFinder {
        FallbackMethodFinder next;

        public FallbackMethodFinder() {
        }

        public FallbackMethodFinder(FallbackMethodFinder next) {
            this.next = next;
        }

        boolean isDefault() {
            return false;
        }

        boolean isSpecific(){
            return false;
        }

        public abstract String getFallbackName(Class<?> enclosingType, Method commandMethod);

        /***
         *
         * @param enclosingType 添加了HystrixCommand注解的类
         * @param commandMethod  添加了HystrixCommand的注解的类的方法
         * @param extended 是否支持拓展，默认是false
         * @return
         * 检查HystrixCommand是否配置了fallback方法，如果没有配置，则使用默认的fallback方法
         */
        public FallbackMethod find(Class<?> enclosingType, Method commandMethod, boolean extended) {
            if (canHandle(enclosingType, commandMethod)) {
                return doFind(enclosingType, commandMethod, extended);
            } else if (next != null) {
                return next.find(enclosingType, commandMethod, extended);
            } else {
                //默认的new FallbackMethod((Method)null, false, false);
                return FallbackMethod.ABSENT;
            }
        }

        abstract boolean canHandle(Class<?> enclosingType, Method commandMethod);

        /***
         *
         * @param enclosingType 添加了HystrixCommand注解的类
         * @param commandMethod  添加了HystrixCommand的注解的类的方法
         * @param extended 是否支持拓展，默认是false
         * @return
         * 这里我们可以发现：
         *      如果配置了 fallback ，那么fallback的参数必须跟HystrixCommand保持一致
         */
        private FallbackMethod doFind(Class<?> enclosingType, Method commandMethod, boolean extended) {
            //获得HystrixCommand配置了的对应的fallbakc方法
            String name = getFallbackName(enclosingType, commandMethod);
            Class<?>[] fallbackParameterTypes = null;
            if (isDefault()) {//判断是否默认的，默认是false
                fallbackParameterTypes = new Class[0];
            } else {//根据HystrixCommand注解的方法获得请求参数，从这里，我们可以发现，fallback的参数必须跟HystrixCommand注解的方法保持一致
                fallbackParameterTypes = commandMethod.getParameterTypes();
            }

            if (extended && fallbackParameterTypes[fallbackParameterTypes.length - 1] == Throwable.class) {
                fallbackParameterTypes = ArrayUtils.remove(fallbackParameterTypes, fallbackParameterTypes.length - 1);
            }
            //维护一个fallBack参数的类型数组，我们可以发现，这边维护的数组长度比实际的参数个多一个，它是为了存放额外的Throwable.class
            Class<?>[] extendedFallbackParameterTypes = Arrays.copyOf(fallbackParameterTypes,
                    fallbackParameterTypes.length + 1);
            //存放额外的Throwable.class，这就是我们如果在fallback里添加了Throwable的参数，就能捕获到异常的原油
            extendedFallbackParameterTypes[fallbackParameterTypes.length] = Throwable.class;
            //根据方法名和参数在对应的类里查找定位对应的带异常处理的fallback方法。
            Optional<Method> exFallbackMethod = getMethod(enclosingType, name, extendedFallbackParameterTypes);
            //根据方法名和参数在对应的类里查找定位对应的不带异常处理的fallback方法。
            Optional<Method> fMethod = getMethod(enclosingType, name, fallbackParameterTypes);
            //如果指定了exFallbackMethod，则返回exFallbackMethod，不然就判断fMethod，如果都没有的话，则返回null
            Method method = exFallbackMethod.or(fMethod).orNull();
            if (method == null) {//如果都没指定的话，则报错
                throw new FallbackDefinitionException("fallback method wasn't found: " + name + "(" + Arrays.toString(fallbackParameterTypes) + ")");
            }
            return new FallbackMethod(method, exFallbackMethod.isPresent(), isDefault());
        }

    }


    /**
     * Gets method by name and parameters types using reflection,
     * if the given type doesn't contain required method then continue applying this method for all super classes up to Object class.
     *
     * @param type           the type to search method
     * @param name           the method name
     * @param parameterTypes the parameters types
     * @return Some if method exists otherwise None
     */
    /***
     *
     * @param type 类的类型
     * @param name 方法名
     * @param parameterTypes 参数类型数组
     * @return
     *
     * 遍历类里所有的方法，根据方法名和参数类型查找对应方法，如果没有的话，则从父类继续查找
     */
    public static Optional<Method> getMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Method[] methods = type.getDeclaredMethods();
        for (Method method : methods) {//根据方法名和参数类型查找对应方法
            if (method.getName().equals(name) && Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                return Optional.of(method);
            }
        }
        //如果没有的话，则从父类继续查找
        Class<?> superClass = type.getSuperclass();
        if (superClass != null && !superClass.equals(Object.class)) {
            return getMethod(superClass, name, parameterTypes);
        } else {
            return Optional.absent();
        }
    }

    /**
     * Finds generic method for the given bridge method.
     *
     * @param bridgeMethod the bridge method
     * @param aClass       the type where the bridge method is declared
     * @return generic method
     * @throws IOException
     * @throws NoSuchMethodException
     * @throws ClassNotFoundException
     */
    public Method unbride(final Method bridgeMethod, Class<?> aClass) throws IOException, NoSuchMethodException, ClassNotFoundException {
        if (bridgeMethod.isBridge() && bridgeMethod.isSynthetic()) {
            if (cache.containsKey(bridgeMethod)) {
                return cache.get(bridgeMethod);
            }

            ClassReader classReader = new ClassReader(aClass.getName());
            final MethodSignature methodSignature = new MethodSignature();
            classReader.accept(new ClassVisitor(ASM5) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    boolean bridge = (access & ACC_BRIDGE) != 0 && (access & ACC_SYNTHETIC) != 0;
                    if (bridge && bridgeMethod.getName().equals(name) && getParameterCount(desc) == bridgeMethod.getParameterTypes().length) {
                        return new MethodFinder(methodSignature);
                    }
                    return super.visitMethod(access, name, desc, signature, exceptions);
                }
            }, 0);
            Method method = aClass.getDeclaredMethod(methodSignature.name, methodSignature.getParameterTypes());
            cache.put(bridgeMethod, method);
            return method;

        } else {
            return bridgeMethod;
        }
    }

    private static int getParameterCount(String desc) {
        return parseParams(desc).length;
    }

    private static String[] parseParams(String desc) {
        String params = desc.split("\\)")[0].replace("(", "");
        if (params.length() == 0) {
            return new String[0];
        }
        return params.split(";");
    }

    private static class MethodSignature {
        String name;
        String desc;

        public Class<?>[] getParameterTypes() throws ClassNotFoundException {
            if (desc == null) {
                return new Class[0];
            }
            String[] params = parseParams(desc);
            Class<?>[] parameterTypes = new Class[params.length];

            for (int i = 0; i < params.length; i++) {
                String arg = params[i].substring(1).replace("/", ".");
                parameterTypes[i] = Class.forName(arg);
            }
            return parameterTypes;
        }
    }

    private static class MethodFinder extends MethodVisitor {
        private MethodSignature methodSignature;

        public MethodFinder(MethodSignature methodSignature) {
            super(ASM5);
            this.methodSignature = methodSignature;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            methodSignature.name = name;
            methodSignature.desc = desc;
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }
    }
}
