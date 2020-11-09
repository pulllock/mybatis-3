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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Clinton Begin
 * 解析xml中的sql
 */
public class XMLScriptBuilder extends BaseBuilder {

  private final XNode context;
  private boolean isDynamic;
  private final Class<?> parameterType;
  /**
   * 存放动态标签的解析器
   * key是动态标签名字，value是对应解析器
   * XMLScriptBuilder在初始化的时候会初始化一些默认的标签解析器
   */
  private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<>();

  public XMLScriptBuilder(Configuration configuration, XNode context) {
    this(configuration, context, null);
  }

  /**
   * 初始化XMLScriptBuilder，并初始化一些NodeHandler
   * @param configuration
   * @param context
   * @param parameterType
   */
  public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
    super(configuration);
    this.context = context;
    this.parameterType = parameterType;
    // 初始化NodeHandler
    initNodeHandlerMap();
  }


  private void initNodeHandlerMap() {
    nodeHandlerMap.put("trim", new TrimHandler());
    nodeHandlerMap.put("where", new WhereHandler());
    nodeHandlerMap.put("set", new SetHandler());
    nodeHandlerMap.put("foreach", new ForEachHandler());
    nodeHandlerMap.put("if", new IfHandler());
    nodeHandlerMap.put("choose", new ChooseHandler());
    nodeHandlerMap.put("when", new IfHandler());
    nodeHandlerMap.put("otherwise", new OtherwiseHandler());
    nodeHandlerMap.put("bind", new BindHandler());
  }

  /**
   * 从xml中解析sql语句
   * @return
   */
  public SqlSource parseScriptNode() {
    /**
     * 解析动态标签
     * 先判断当前结点是不是有动态SQL，动态SQL会包括占位符或是动态SQL的相关节点
     * 并将SqlNode包装成MixedSqlNode
     *
     * context是select insert update delete等节点
     */
    MixedSqlNode rootSqlNode = parseDynamicTags(context);
    SqlSource sqlSource;
    // 根据是否是动态sql来创建sqlSource对象
    if (isDynamic) {
      // 动态sql，创建DynamicSqlSource对象
      sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
    } else {
      // 纯静态文本sql，创建RawSqlSource
      sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
    }
    return sqlSource;
  }

  /**
   * 解析动态标签
   * @param node
   * @return
   *
   * <sql id="Base_Column_List">
   *     id, gmt_created, gmt_modified,
   *     creator, modifier, is_deleted,
   *     version, id, name
   *   </sql>
   *
   * <select id="selectByPrimaryKey" parameterType="java.lang.Long" resultMap="BaseResultMap">
   *     select
   *     <include refid="Base_Column_List" />
   *     from user
   *     where id = #{id,jdbcType=BIGINT}
   *   </select>
   */
  protected MixedSqlNode parseDynamicTags(XNode node) {
    /**
     * contents存储从select insert update delete等节点中解析出来的sql
     * 比如上面的例子，会解析出来三个SqlNode
     * select
     *
     * id, gmt_created, gmt_modified,
     * creator, modifier, is_deleted,
     * version, id, name
     *
     * from user
     * where id = #{id,jdbcType=BIGINT}
     *
     * 并且这三个都是StaticTextSqlNode
     */
    List<SqlNode> contents = new ArrayList<>();
    NodeList children = node.getNode().getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      // 获取sql节点，创建XNode，该过程会将能解析的'${}'都解析掉
      XNode child = node.newXNode(children.item(i));
      // 文本节点
      if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE || child.getNode().getNodeType() == Node.TEXT_NODE) {
        String data = child.getStringBody("");
        // TextSqlNode表示的是可能还有占位符的的动态节点
        TextSqlNode textSqlNode = new TextSqlNode(data);
        // 会先解析sql，如果发现含有未解析的'${}'占位符，为动态sql
        if (textSqlNode.isDynamic()) {
          contents.add(textSqlNode);
          isDynamic = true;
        } else {
          contents.add(new StaticTextSqlNode(data));
        }
      }
      // 标签节点，比如where trim等等
      else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628
        // 如果子节点是一个标签，则一定是动态sql，根据不同的标签生成不同的NodeHandler
        String nodeName = child.getNode().getNodeName();
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        if (handler == null) {
          throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
        }
        // 使用节点对应的Handler进行节点的解析，比如WhereHandler
        handler.handleNode(child, contents);
        isDynamic = true;
      }
    }
    // 返回一个MixedSqlNode对象
    return new MixedSqlNode(contents);
  }

  private interface NodeHandler {
    void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
  }

  private class BindHandler implements NodeHandler {
    public BindHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      final String name = nodeToHandle.getStringAttribute("name");
      final String expression = nodeToHandle.getStringAttribute("value");
      final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
      targetContents.add(node);
    }
  }

  /**
   * where和set都是trim
   * where等价于：
   * <trim prefix="WHERE" prefixOverrides="AND |OR ">
   *   ...
   * </trim>
   *
   * set等价于：
   * <trim prefix="SET" suffixOverrides=",">
   *   ...
   * </trim>
   */
  private class TrimHandler implements NodeHandler {
    public TrimHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      // 解析动态标签
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      // 前缀，包含的子节点解析后sql文本不为空的时候，要添加的前缀内容，比如where、set
      String prefix = nodeToHandle.getStringAttribute("prefix");
      // 要被覆盖的前缀，解析后sql文本前要覆盖的内容，比如去除or、and等
      String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
      // 后缀
      String suffix = nodeToHandle.getStringAttribute("suffix");
      // 要被覆盖的后缀
      String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
      // 构建一个TrimSqlNode对象
      TrimSqlNode trim = new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
      targetContents.add(trim);
    }
  }

  private class WhereHandler implements NodeHandler {
    public WhereHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      // 先进行动态标签解析
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      // 创建一个WhereSqlNode对象
      WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
      targetContents.add(where);
    }
  }

  private class SetHandler implements NodeHandler {
    public SetHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      // 解析动态标签
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      // 创建一个SetSqlNode对象
      SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
      targetContents.add(set);
    }
  }

  private class ForEachHandler implements NodeHandler {
    public ForEachHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      // 解析动态标签
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      // collection属性
      String collection = nodeToHandle.getStringAttribute("collection");
      // item属性
      String item = nodeToHandle.getStringAttribute("item");
      // index属性
      String index = nodeToHandle.getStringAttribute("index");
      // open属性，用来指定开头字符
      String open = nodeToHandle.getStringAttribute("open");
      // close属性，用来指定结尾字符
      String close = nodeToHandle.getStringAttribute("close");
      // separator属性，用来指定分隔符
      String separator = nodeToHandle.getStringAttribute("separator");
      // 创建一个ForEachSqlNode对象
      ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
      targetContents.add(forEachSqlNode);
    }
  }

  private class IfHandler implements NodeHandler {
    public IfHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      // 解析动态标签
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      String test = nodeToHandle.getStringAttribute("test");
      //  获取test属性，构建IfSqlNode对象
      IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
      targetContents.add(ifSqlNode);
    }
  }

  private class OtherwiseHandler implements NodeHandler {
    public OtherwiseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      // 解析动态标签
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      targetContents.add(mixedSqlNode);
    }
  }

  private class ChooseHandler implements NodeHandler {
    public ChooseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      List<SqlNode> whenSqlNodes = new ArrayList<>();
      List<SqlNode> otherwiseSqlNodes = new ArrayList<>();
      // 处理when和otherwise标签
      handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
      // otherwise中获取默认节点,otherwise只能有一个
      SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
      // 创建ChooseSqlNode对象
      ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
      targetContents.add(chooseSqlNode);
    }

    private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes, List<SqlNode> defaultSqlNodes) {
      // 遍历choose下面的子节点
      List<XNode> children = chooseSqlNode.getChildren();
      for (XNode child : children) {
        String nodeName = child.getNode().getNodeName();
        // when标签使用的是IfHandler
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        if (handler instanceof IfHandler) {
          handler.handleNode(child, ifSqlNodes);
        }
        // OtherwiseHandler
        else if (handler instanceof OtherwiseHandler) {
          handler.handleNode(child, defaultSqlNodes);
        }
      }
    }

    private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
      SqlNode defaultSqlNode = null;
      if (defaultSqlNodes.size() == 1) {
        defaultSqlNode = defaultSqlNodes.get(0);
      } else if (defaultSqlNodes.size() > 1) {
        throw new BuilderException("Too many default (otherwise) elements in choose statement.");
      }
      return defaultSqlNode;
    }
  }

}
