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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * 动态sql语句封装，运行时需要根据参数、标签或者${}等处理后才能生成最后要执行的静态sql语句
 *
 * 处理动态sql语句，最后会将处理后的sql语句封装成StaticSqlSource返回，是在实际执行sql语句的之前执行
 *
 * 封装的sql还需进行一些列的解析，才可以形成数据库可执行的sql语句
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

  private final Configuration configuration;

  /**
   * SqlNode使用了组合模式，形成一个树形结构
   * 该字段记录了待解析的SqlNode的根节点
   */
  private final SqlNode rootSqlNode;

  public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
    this.configuration = configuration;
    this.rootSqlNode = rootSqlNode;
  }

  /**
   *
   * @param parameterObject 用户传入的实参
   * @return
   */
  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    // 创建DynamicContext对象
    DynamicContext context = new DynamicContext(configuration, parameterObject);
    // 调用整个树形结构中全部SqlNode.apply()方法
    rootSqlNode.apply(context);
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
    // 将sql语句中的#{}占位符替换成?占位符
    SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
    // 创建BoundSql对象，并将bindings中参数信息复制到additionalParameter中
    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
    context.getBindings().forEach(boundSql::setAdditionalParameter);
    return boundSql;
  }

}
