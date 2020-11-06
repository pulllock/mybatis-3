/**
 *    Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * 属性分词器
 * @author Clinton Begin
 * 用来处理类似：user[0].items[0].name这种由.h和[]组合的表达式
 */
public class PropertyTokenizer implements Iterator<PropertyTokenizer> {

  /**
   * 比如user[0].items[0].name中的user[0]的user就是name
   */
  private String name;

  /**
   * 比如user[0].items[0].name中的user[0]
   */
  private final String indexedName;

  /**
   * 比如user[0].items[0].name中的user[0]中的0
   */
  private String index;

  /**
   * 比如user[0].items[0].name中的items[0].name
   */
  private final String children;

  public PropertyTokenizer(String fullname) {
    // 第一个.
    int delim = fullname.indexOf('.');
    if (delim > -1) {
      // 第一个点前面的是name，比如user[0].items[0].name中的user[0]
      name = fullname.substring(0, delim);
      // 第一个点后面的是子name，比如user[0].items[0].name中的items[0].name
      children = fullname.substring(delim + 1);
    }
    // 没有点的情况
    else {
      name = fullname;
      children = null;
    }
    indexedName = name;
    // name中含有[]
    delim = name.indexOf('[');
    if (delim > -1) {
      // 获取到index，比如user[0]中的0
      index = name.substring(delim + 1, name.length() - 1);
      // name，比如user[0]中的user
      name = name.substring(0, delim);
    }
  }

  public String getName() {
    return name;
  }

  public String getIndex() {
    return index;
  }

  public String getIndexedName() {
    return indexedName;
  }

  public String getChildren() {
    return children;
  }

  @Override
  public boolean hasNext() {
    return children != null;
  }

  @Override
  public PropertyTokenizer next() {
    return new PropertyTokenizer(children);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("Remove is not supported, as it has no meaning in the context of properties.");
  }
}
