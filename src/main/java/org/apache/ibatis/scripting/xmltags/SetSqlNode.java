/**
 *    Copyright 2009-2018 the original author or authors.
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

import java.util.Collections;
import java.util.List;

import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 * set标签节点
 */
public class SetSqlNode extends TrimSqlNode {

  private static final List<String> COMMA = Collections.singletonList(",");

  public SetSqlNode(Configuration configuration,SqlNode contents) {
    // set节点解析后的sql语句片段如果以‘,’结尾，将结尾的,删除，再将set关键字添加到sql片段开始位置
    super(configuration, contents, "SET", COMMA, null, COMMA);
  }

}
