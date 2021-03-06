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
package com.netflix.hystrix.contrib.javanica.cache;


import com.netflix.hystrix.contrib.javanica.cache.annotation.CacheKey;
import com.netflix.hystrix.contrib.javanica.command.MethodExecutionAction;
import com.netflix.hystrix.contrib.javanica.exception.HystrixCacheKeyGenerationException;
import org.apache.commons.lang3.StringUtils;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;

/**
 * Generates a {@link HystrixGeneratedCacheKey} based on
 * a {@link CacheInvocationContext}.
 * <p/>
 * Implementation is thread-safe.
 *
 * @author dmgcodevil
 */
public class HystrixCacheKeyGenerator {

    private static final HystrixCacheKeyGenerator INSTANCE = new HystrixCacheKeyGenerator();

    public static HystrixCacheKeyGenerator getInstance() {
        return INSTANCE;
    }

    /***
     * 根据 cacheInvocationContext(比如：cacheResultInvocationContext/cacheRemoveInvocationContext）生成一个cachekey
     *      这里主要是反射调用cacheKeyMethod方法生成cacheKey
     * @param cacheInvocationContext
     * @return
     * @throws HystrixCacheKeyGenerationException
     *
     */
    public HystrixGeneratedCacheKey generateCacheKey(
            CacheInvocationContext<? extends Annotation> cacheInvocationContext)
            throws HystrixCacheKeyGenerationException {
        //获得cacheInvocationContext中的CacheKeyMethod方法
        MethodExecutionAction cacheKeyMethod = cacheInvocationContext.getCacheKeyMethod();
        if (cacheKeyMethod != null) {//如果定义了 cacheKeyMethod
            try {//直接调用cacheKeyMethod生成cacheKey
                return new DefaultHystrixGeneratedCacheKey((String) cacheKeyMethod.execute(cacheInvocationContext.getExecutionType()));
            } catch (Throwable throwable) {
                throw new HystrixCacheKeyGenerationException(throwable);
            }
        } else {//如果未定义cacheKeyMethod属性
            if (cacheInvocationContext.hasKeyParameters()) {//如果指定了带CacheKey注解的参数
                StringBuilder cacheKeyBuilder = new StringBuilder();
                //遍历所有CacheKey注解参数
                for (CacheInvocationParameter parameter : cacheInvocationContext.getKeyParameters()) {
                    CacheKey cacheKey = parameter.getCacheKeyAnnotation();//获得CacheKey
                    //如果cacheKey不为空，且指定CacheKey中的value，如果cacheKey根据多个属性来确定后，它们直接用'.'分割
                    if (cacheKey != null && StringUtils.isNotBlank(cacheKey.value())) {
                        appendPropertyValue(cacheKeyBuilder, Arrays.asList(StringUtils.split(cacheKey.value(), ".")), parameter.getValue());
                    } else {//如果cache没有指定value，则整个参数作为cacheKey
                        cacheKeyBuilder.append(parameter.getValue());
                    }
                }
                return new DefaultHystrixGeneratedCacheKey(cacheKeyBuilder.toString());
            } else {
                return DefaultHystrixGeneratedCacheKey.EMPTY;
            }
        }
    }

    private Object appendPropertyValue(StringBuilder cacheKeyBuilder, List<String> names, Object obj) throws HystrixCacheKeyGenerationException {
        for (String name : names) {
            if (obj != null) {
                obj = getPropertyValue(name, obj);
            }
        }
        if (obj != null) {
            cacheKeyBuilder.append(obj);
        }
        return obj;
    }

    private Object getPropertyValue(String name, Object obj) throws HystrixCacheKeyGenerationException {
        try {
            return new PropertyDescriptor(name, obj.getClass())
                    .getReadMethod().invoke(obj);
        } catch (IllegalAccessException e) {
            throw new HystrixCacheKeyGenerationException(e);
        } catch (IntrospectionException e) {
            throw new HystrixCacheKeyGenerationException(e);
        } catch (InvocationTargetException e) {
            throw new HystrixCacheKeyGenerationException(e);
        }
    }

}
