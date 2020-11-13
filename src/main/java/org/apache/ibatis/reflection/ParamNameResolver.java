/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 参数名解析器
 */
public class ParamNameResolver {

  public static final String GENERIC_NAME_PREFIX = "param";

  /**
   * <p>
   * The key is the index and the value is the name of the parameter.<br />
   * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
   * the parameter index is used. Note that this index could be different from the actual index
   * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
   * </p>
   * <ul>
   * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
   * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
   * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
   * </ul>
   * 保存了调用的方法的参数索引和名字的对应关系
   * key是索引，value是参数名字
   */
  private final SortedMap<Integer, String> names;

  /**
   * 参数上有没有@Param注解
   */
  private boolean hasParamAnnotation;

  /**
   * ParamNameResolver构造方法
   * @param config 配置
   * @param method 执行的mapper方法
   */
  public ParamNameResolver(Configuration config, Method method) {
    // 获取执行的方法的参数类型
    final Class<?>[] paramTypes = method.getParameterTypes();
    // 获取执行的方法的参数，以及参数对应的注解，就是使用了@Param注解的参数
    final Annotation[][] paramAnnotations = method.getParameterAnnotations();
    final SortedMap<Integer, String> map = new TreeMap<>();
    int paramCount = paramAnnotations.length;
    // get names from @Param annotations
    // 获取@Param注解指定的参数的名字
    for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
      // 看参数是不是一些特殊的参数，RowBounds和ResultHandler类型的参数
      if (isSpecialParameter(paramTypes[paramIndex])) {
        // skip special parameters
        continue;
      }
      String name = null;
      for (Annotation annotation : paramAnnotations[paramIndex]) {
        // 参数上有@Param注解
        if (annotation instanceof Param) {
          // 是否有@Param注解设置为true
          hasParamAnnotation = true;
          // 获取@Param注解指定的参数名字
          name = ((Param) annotation).value();
          break;
        }
      }
      // 没有@Param注解
      if (name == null) {
        // @Param was not specified.
        /**
         * 是否使用实际参数名，这个值默认是false，在XmlConfigBuilder解析配置的时候，如果settings没有指定useActualParamName属性，默认是false
         */
        if (config.isUseActualParamName()) {
          // 获取实际参数名
          name = getActualParamName(method, paramIndex);
        }
        // 不使用实际参数名字，则使用参数的索引当做名字，0,1,2,...
        if (name == null) {
          // use the parameter index as the name ("0", "1", ...)
          // gcode issue #71
          name = String.valueOf(map.size());
        }
      }
      map.put(paramIndex, name);
    }
    /**
     * 参数的名字，如果有@Param注解，则优先使用@Param注解指定的名字，
     * 如果没有@Param注解，则看useActualParamName的设置，如果为true，则使用参数的实际名字，
     * 如果useActualParamName为false，则使用参数的索引，比如0、1、2...等作为参数名字
     */
    names = Collections.unmodifiableSortedMap(map);
  }

  private String getActualParamName(Method method, int paramIndex) {
    return ParamNameUtil.getParamNames(method).get(paramIndex);
  }

  private static boolean isSpecialParameter(Class<?> clazz) {
    return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
  }

  /**
   * Returns parameter names referenced by SQL providers.
   */
  public String[] getNames() {
    return names.values().toArray(new String[0]);
  }

  /**
   * <p>
   * A single non-special parameter is returned without a name.
   * Multiple parameters are named using the naming rule.
   * In addition to the default names, this method also adds the generic names (param1, param2,
   * ...).
   * </p>
   */
  public Object getNamedParams(Object[] args) {
    // 参数的个数，在ParamNameResolver实例化的时候就已经解析过了，names中保存的是参数索引和名字的关系
    final int paramCount = names.size();
    // 没有参数，返回null
    if (args == null || paramCount == 0) {
      return null;
    }
    // 参数上有没有@Param注解，并且参数数量是1
    else if (!hasParamAnnotation && paramCount == 1) {
      // 返回第一个
      return args[names.firstKey()];
    }
    // 参数数量多于一个或者有@Param注解
    else {
      final Map<String, Object> param = new ParamMap<>();
      int i = 0;
      for (Map.Entry<Integer, String> entry : names.entrySet()) {
        /**
         * names中key是索引，value是解析后的参数名字
         * 这里param的key是参数名字value是实际参数值
         */
        param.put(entry.getValue(), args[entry.getKey()]);
        // add generic param names (param1, param2, ...)
        // 生成以param+索引格式的参数名字
        final String genericParamName = GENERIC_NAME_PREFIX + String.valueOf(i + 1);
        // ensure not to overwrite parameter named with @Param
        if (!names.containsValue(genericParamName)) {
          param.put(genericParamName, args[entry.getKey()]);
        }
        i++;
      }
      return param;
    }
  }
}
