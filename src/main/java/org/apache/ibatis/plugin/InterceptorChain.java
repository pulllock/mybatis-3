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
package org.apache.ibatis.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Clinton Begin
 * 拦截器链，可以一下将很多个拦截器插入到目标对象中去
 */
public class InterceptorChain {

  private final List<Interceptor> interceptors = new ArrayList<>();

  /**
   * 将很多个拦截器插入到目标对象中去
   *
   * MyBatis会在创建Executor对象、ParameterHandler对象、ResultSet对象、StatementHandler对象的时候调用该方法，
   * 为这几个对象添加拦截器。
   * 也可认为MyBatis默认允许拦截Executor、ParameterHandler、ResultSet、StatementHandler这四个接口实现类的
   * Executor来执行sql，Executor创建的时候会创建StatementHandler、ParameterHandler、ResultSetHandler对象，
   * ParameterHandler用来设置sql语句中的占位符参数，StatementHandler用来执行sql语句，ResultSetHandler封装执行结果，
   * 这几个就可以覆盖了整个sql的执行流程
   * @param target
   * @return
   */
  public Object pluginAll(Object target) {
    for (Interceptor interceptor : interceptors) {
      target = interceptor.plugin(target);
    }
    return target;
  }

  /**
   * 添加拦截器到链接器链中
   * 拦截器来源只有mybatis-config.xml配置文件中的plugins标签里面写的拦截器
   * @param interceptor
   */
  public void addInterceptor(Interceptor interceptor) {
    interceptors.add(interceptor);
  }

  public List<Interceptor> getInterceptors() {
    return Collections.unmodifiableList(interceptors);
  }

}
