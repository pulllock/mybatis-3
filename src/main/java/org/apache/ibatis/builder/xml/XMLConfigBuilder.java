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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * xml配置构建器
 * 主要负责解析mybatis-config.xml配置
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  /**
   * 是否已解析
   */
  private boolean parsed;

  /**
   * XPath解析器
   */
  private final XPathParser parser;

  /**
   * 环境
   */
  private String environment;

  /**
   * 创建Reflector的工厂
   */
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    // 创建Configuration对象
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    // 设置Configuration的属性
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  /**
   * 解析mybatis-config.xml文件为Configuration对象
   * @return
   */
  public Configuration parse() {
    // xml只能解析一次
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    // 解析配置，configuration结点
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  private void parseConfiguration(XNode root) {
    try {
      //issue #117 read properties first
      /**
       * 解析properties标签
       * <properties resource="org/mybatis/example/config.properties">
       *   <property name="username" value="dev_user"/>
       *   <property name="password" value="F2Fa3!33TYyg"/>
       * </properties>
       * 属性来源：
       * 1. <property/>标签中指定的属性
       * 2. properties文件中指定的属性
       * 3. 在构建XMLConfigBuilder对象的时候传入进来的属性
       *
       * 属性读取完成后，会设置到Configuration对象中
       */
      propertiesElement(root.evalNode("properties"));
      /**
       * 解析settings标签，仅仅把合法的<setting/>标签中的属性都解析出来
       */
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      // 加载自定义VFS实现类
      loadCustomVfs(settings);
      // 加载自定义Log实现，logImpl指定MyBatis所用日志的具体实现，未指定的时候将自动查找
      loadCustomLogImpl(settings);
      //
      /**
       * 解析typeAliases标签
       * <typeAliases>
       *   <typeAlias alias="Author" type="domain.blog.Author"/>
       *   <typeAlias alias="Blog" type="domain.blog.Blog"/>
       *   <typeAlias alias="Comment" type="domain.blog.Comment"/>
       *   <typeAlias alias="Post" type="domain.blog.Post"/>
       *   <typeAlias alias="Section" type="domain.blog.Section"/>
       *   <typeAlias alias="Tag" type="domain.blog.Tag"/>
       * </typeAliases>
       * 或者
       * <typeAliases>
       *   <package name="domain.blog"/>
       * </typeAliases>
       */
      typeAliasesElement(root.evalNode("typeAliases"));
      /**
       * 解析plugins标签
       * MyBatis允许在映射语句执行过程中的某一点进行拦截调用
       *
       * <plugins>
       *   <plugin interceptor="org.mybatis.example.ExamplePlugin">
       *     <property name="someProperty" value="100"/>
       *   </plugin>
       * </plugins>
       */
      pluginElement(root.evalNode("plugins"));
      /**
       * 解析objectFactory标签
       * 每次 MyBatis 创建结果对象的新实例时，它都会使用一个对象工厂（ObjectFactory）实例来完成实例化工作。
       * 默认的对象工厂需要做的仅仅是实例化目标类，要么通过默认无参构造方法，要么通过存在的参数映射来调用带有参数的构造方法。
       * 如果想覆盖对象工厂的默认行为，可以通过创建自己的对象工厂来实现。
       * <objectFactory type="org.mybatis.example.ExampleObjectFactory">
       *   <property name="someProperty" value="100"/>
       * </objectFactory>
       */
      objectFactoryElement(root.evalNode("objectFactory"));
      // 解析objectWrapperFactory标签
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      // 解析reflectorFactory标签
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      // 设置settings中的各个属性到Configuration对象中
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      // 解析environment标签
      environmentsElement(root.evalNode("environments"));
      /**
       * 解析databaseIdProvider标签，数据库厂商标识
       * MyBatis 可以根据不同的数据库厂商执行不同的语句，这种多厂商的支持是基于映射语句中的 databaseId 属性。
       *  MyBatis 会加载带有匹配当前数据库 databaseId 属性和所有不带 databaseId 属性的语句。
       *  如果同时找到带有 databaseId 和不带 databaseId 的相同语句，则后者会被舍弃。
       */
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      /**
       * 解析typeHandlers标签，类型处理器
       * MyBatis 在设置预处理语句（PreparedStatement）中的参数或从结果集中取出一个值时，
       * 都会用类型处理器将获取到的值以合适的方式转换成 Java 类型。
       *
       * 这里会把我们自定义的TypeHandler注册到TypeHandlerRegistry中，后面解析mapper文件会使用到
       *
       * <typeHandlers>
       *   <typeHandler handler="org.mybatis.example.ExampleTypeHandler"/>
       * </typeHandlers>
       * 或者
       * <typeHandlers>
       *   <package name="org.mybatis.example"/>
       * </typeHandlers>
       */
      typeHandlerElement(root.evalNode("typeHandlers"));
      /**
       * 解析mappers标签
       * <mappers>
       *   <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
       *   <mapper resource="org/mybatis/builder/BlogMapper.xml"/>
       *   <mapper resource="org/mybatis/builder/PostMapper.xml"/>
       * </mappers>
       */
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  /**
   * 解析settings属性
   * <settings>
   *   <setting name="cacheEnabled" value="true"/>
   *   <setting name="lazyLoadingEnabled" value="true"/>
   *   <setting name="multipleResultSetsEnabled" value="true"/>
   *   <setting name="useColumnLabel" value="true"/>
   *   <setting name="useGeneratedKeys" value="false"/>
   *   <setting name="autoMappingBehavior" value="PARTIAL"/>
   *   <setting name="autoMappingUnknownColumnBehavior" value="WARNING"/>
   *   <setting name="defaultExecutorType" value="SIMPLE"/>
   *   <setting name="defaultStatementTimeout" value="25"/>
   *   <setting name="defaultFetchSize" value="100"/>
   *   <setting name="safeRowBoundsEnabled" value="false"/>
   *   <setting name="mapUnderscoreToCamelCase" value="false"/>
   *   <setting name="localCacheScope" value="SESSION"/>
   *   <setting name="jdbcTypeForNull" value="OTHER"/>
   *   <setting name="lazyLoadTriggerMethods" value="equals,clone,hashCode,toString"/>
   * </settings>
   * @param context
   * @return
   */
  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    // 解析子标签，获取所有的<setting/>元素
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    // 检查每个属性，保证是Configuration的set方法
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    // 获取vfsImpl属性
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          // 设置到Configuration中
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    configuration.setLogImpl(logImpl);
  }

  /**
   * 解析类型别名typeAliases元素
   * <typeAliases>
   *   <typeAlias alias="Author" type="domain.blog.Author"/>
   *   <typeAlias alias="Blog" type="domain.blog.Blog"/>
   *   <typeAlias alias="Comment" type="domain.blog.Comment"/>
   *   <typeAlias alias="Post" type="domain.blog.Post"/>
   *   <typeAlias alias="Section" type="domain.blog.Section"/>
   *   <typeAlias alias="Tag" type="domain.blog.Tag"/>
   * </typeAliases>
   * 或者
   * <typeAliases>
   *   <package name="domain.blog"/>
   * </typeAliases>
   * @param parent
   */
  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 指定为包的，解析package属性，会扫描包下的每个类
        if ("package".equals(child.getName())) {
          String typeAliasPackage = child.getStringAttribute("name");
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            // 指定为类，将类和别名注册到typeAliasRegistry中
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      // 遍历plugin标签，创建Interceptor对象设置属性，并添加到interceptorChain中
      for (XNode child : parent.getChildren()) {
        String interceptor = child.getStringAttribute("interceptor");
        Properties properties = child.getChildrenAsProperties();
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor().newInstance();
        interceptorInstance.setProperties(properties);
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获取ObjectFactory实现类
      String type = context.getStringAttribute("type");
      // 获取属性
      Properties properties = context.getChildrenAsProperties();
      // 实例化ObjectFactory对象
      ObjectFactory factory = (ObjectFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置属性
      factory.setProperties(properties);
      // 设置到Configuration中
      configuration.setObjectFactory(factory);
    }
  }

  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      configuration.setReflectorFactory(factory);
    }
  }

  /**
   * 解析properties元素
   * <properties resource="org/mybatis/example/config.properties">
   *   <property name="username" value="dev_user"/>
   *   <property name="password" value="F2Fa3!33TYyg"/>
   * </properties>
   * @param context
   * @throws Exception
   */
  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      // 读取<property/>子标签，构造Properties对象
      Properties defaults = context.getChildrenAsProperties();
      // 获取resource属性，resource属性指定类properties配置文件
      String resource = context.getStringAttribute("resource");
      // 获取url属性，url属性可以指定属性文件位置
      String url = context.getStringAttribute("url");
      // resource属性和url属性不能同时存在
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      // 下面从properties文件中读取的属性会覆盖上面<property/>元素中读取的数据
      if (resource != null) {
        // 读取本地properties配置文件到defaults中
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        // 读取远程properties配置文件到defaults中
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      /**
       * 从Configuration中读取属性
       * 在创建XMLConfigBuilder实例对象的时候，可能会有properties属性传入
       * 在创建XMLConfigBuilder实例对象的时候，会同时创建Configuration对象，也会把properties属性设置到
       * Configuration对象中，这里获取的属性就是设置到Configuration的那些属性
       * 这里的属性会覆盖<property/>元素和properties文件中的属性
       */
      Properties vars = configuration.getVariables();
      // 覆盖defaults中存在的属性
      if (vars != null) {
        defaults.putAll(vars);
      }
      //  将解析到的属性设置到parser中
      parser.setVariables(defaults);
      // 将属性设置到Configuration对象中去
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) {
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      // environment为空的话，从default属性中获取
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      for (XNode child : context.getChildren()) {
        // 判断environment是否一致
        String id = child.getStringAttribute("id");
        if (isSpecifiedEnvironment(id)) {
          // 解析transactionManager属性
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          // 解析dataSource标签
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          DataSource dataSource = dsFactory.getDataSource();
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      Properties properties = context.getChildrenAsProperties();
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      configuration.setDatabaseId(databaseId);
    }
  }

  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      TransactionFactory factory = (TransactionFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 如果是package标签，扫描该包
        if ("package".equals(child.getName())) {
          String typeHandlerPackage = child.getStringAttribute("name");
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          // 按照指定的注册
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 如果指定了package，则扫描该包下面的mapper文件
        if ("package".equals(child.getName())) {
          String mapperPackage = child.getStringAttribute("name");
          // MapperRegistry会进行Mapper和接口的映射关系解析，会为每一个接口对应一个代理MapperProxyFactory
          // 同时也会解析mapper文件
          configuration.addMappers(mapperPackage);
        } else {
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          // 使用相对路径的资源引用
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            InputStream inputStream = Resources.getResourceAsStream(resource);
            // 创建一个XMLMapperBuilder对象，用来解析mapper文件
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            // 解析mapper文件
            mapperParser.parse();
          } else if (resource == null && url != null && mapperClass == null) {
            // 使用URL
            ErrorContext.instance().resource(url);
            InputStream inputStream = Resources.getUrlAsStream(url);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            mapperParser.parse();
          } else if (resource == null && url == null && mapperClass != null) {
            // 使用接口全限定名
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) {
      return true;
    }
    return false;
  }

}
