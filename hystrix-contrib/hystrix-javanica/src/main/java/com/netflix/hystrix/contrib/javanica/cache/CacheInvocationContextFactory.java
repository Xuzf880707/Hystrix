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

import com.netflix.hystrix.contrib.javanica.cache.annotation.CacheRemove;
import com.netflix.hystrix.contrib.javanica.cache.annotation.CacheResult;
import com.netflix.hystrix.contrib.javanica.command.MetaHolder;
import com.netflix.hystrix.contrib.javanica.command.MethodExecutionAction;
import com.netflix.hystrix.contrib.javanica.exception.HystrixCachingException;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Method;

import static com.netflix.hystrix.contrib.javanica.utils.AopUtils.getDeclaredMethod;

/**
 * Factory to create certain {@link CacheInvocationContext}.
 *
 * @author dmgcodevil
 */
public class CacheInvocationContextFactory {

    /**
     * Create {@link CacheInvocationContext} parametrized with {@link CacheResult} annotation.
     *
     * @param metaHolder the meta holder, see {@link com.netflix.hystrix.contrib.javanica.command.MetaHolder}
     * @return initialized and configured {@link CacheInvocationContext}
     *
     * 返回CacheResult注解中的cacheKeyMethod属性对应的CacheInvocationContext，绑定了上下文
     *
     * metaHolder：HystrixCommand注解的方法对象
     */
    public static CacheInvocationContext<CacheResult> createCacheResultInvocationContext(MetaHolder metaHolder) {
        //获得commandMethod的方法
        Method method = metaHolder.getMethod();
        //判断方法是否存在CacheResult注解
        if (method.isAnnotationPresent(CacheResult.class)) {
            //获得CacheResult注解对象
            CacheResult cacheResult = method.getAnnotation(CacheResult.class);
            //根据CacheResult注解中的cacheKeyMethod属性，找到对应的方法，然后创建一个用于获取对应的cacheKey的MethodExecutionAction
            //后面可以直接根据这个MethodExecutionAction获得对应缓存key
            MethodExecutionAction cacheKeyMethod = createCacheKeyAction(cacheResult.cacheKeyMethod(), metaHolder);
            //将MethodExecutionAction和commandMethod用CacheInvocationContext保存起来，
            // 后面就可以根据commandMethod找到对应的CacheInvocationContext，然后获取MethodExecutionAction，进而获得cachekey
            return new CacheInvocationContext<CacheResult>(cacheResult, cacheKeyMethod, metaHolder.getObj(), method, metaHolder.getArgs());
        }
        //如果没有加CacheResult，则返回空
        return null;
    }

    /**
     * Create {@link CacheInvocationContext} parametrized with {@link CacheRemove} annotation.
     *
     * @param metaHolder the meta holder, see {@link com.netflix.hystrix.contrib.javanica.command.MetaHolder}
     * @return initialized and configured {@link CacheInvocationContext}
     *
     * metaHolder：HystrixCommand注解的方法对象
     */
    public static CacheInvocationContext<CacheRemove> createCacheRemoveInvocationContext(MetaHolder metaHolder) {
        Method method = metaHolder.getMethod(); //获得command注解的方法
        //判断是否加了CacheRemove注解
        if (method.isAnnotationPresent(CacheRemove.class)) {
            //获得CacheRemove注解
            CacheRemove cacheRemove = method.getAnnotation(CacheRemove.class);
            //根据CacheRemove注解中的cacheKeyMethod和HystrixCommand注解的方法对象
            MethodExecutionAction cacheKeyMethod = createCacheKeyAction(cacheRemove.cacheKeyMethod(), metaHolder);
            return new CacheInvocationContext<CacheRemove>(cacheRemove, cacheKeyMethod, metaHolder.getObj(), method, metaHolder.getArgs());
        }
        return null;
    }

    /***
     *
     * @param method CacheResult注解中的cacheKeyMethod属性值
     * @param metaHolder 添加HystrixCommand注解的方法对象
     * @return
     */
    private static MethodExecutionAction createCacheKeyAction(String method, MetaHolder metaHolder) {
        MethodExecutionAction cacheKeyAction = null;
        //如果指定了cacheKeyMethod属性来返回缓存key的话，这这个方法必须返回字符串
        if (StringUtils.isNotBlank(method)) {
            /***
             * metaHolder.getObj().getClass()：CommandMethod所属的类对象
             * method：CacheResult注解中的cacheKeyMethod属性值
             * metaHolder.getMethod().getParameterTypes()：CommandMethod方法的参数类型数组
             *
             * 在CommandMethod所属的类对象中查找对应的方法对象cacheKeyMethod是否已存在，如果不存在，则抛出异常
             *
             * 注意：cacheKeyMethod其实是用来指定
             */
            Method cacheKeyMethod = getDeclaredMethod(metaHolder.getObj().getClass(), method,
                    metaHolder.getMethod().getParameterTypes());
            if (cacheKeyMethod == null) {//如果方法不存在
                throw new HystrixCachingException("method with name '" + method + "' doesn't exist in class '"
                        + metaHolder.getObj().getClass() + "'");
            }
            //如果指定了cacheKeyMethod属性来返回缓存key的话，这这个方法必须返回字符串
            if (!cacheKeyMethod.getReturnType().equals(String.class)) {
                throw new HystrixCachingException("return type of cacheKey method must be String. Method: '" + method + "', Class: '"
                        + metaHolder.getObj().getClass() + "'");
            }
            //根据cacheKeyMethod创建一个对应的用于获得缓存key值的MetaHolder,通过MetaHolder可以获得相应的缓存key
            MetaHolder cMetaHolder = MetaHolder.builder()
                    .obj(metaHolder.getObj())//cacheKeyMethod所属的类对象
                    .method(cacheKeyMethod) //cacheKeyMethod方法
                    .args(metaHolder.getArgs())//cacheKeyMethod需要的参数
                    .build();
            //根据MetaHolder绑定一个获得cacheKey的
            cacheKeyAction = new MethodExecutionAction(cMetaHolder.getObj(), cacheKeyMethod, cMetaHolder.getArgs(), cMetaHolder);
        }
        return cacheKeyAction;
    }

}
