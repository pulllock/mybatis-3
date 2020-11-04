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

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.AmbiguousMethodInvoker;
import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * MyBatis进行参数处理、结果映射处理等都会使用Java的反射操作，
 * 出于性能考虑和反射操作的复杂问题，Mybatis专门提供了反射模块，将Java反射进行封装，并进行缓存。
 *
 * 每个Reflector对应一个类，Reflector会缓存反射操作需要的类的信息等
 *
 * @author Clinton Begin
 */
public class Reflector {

  /**
   * 对应的类的Class类型
   */
  private final Class<?> type;

  /**
   * 可读属性数组，getter方法
   */
  private final String[] readablePropertyNames;

  /**
   * 可写属性数组，setter方法
   */
  private final String[] writablePropertyNames;

  /**
   * set方法映射，记录属性对应的setter方法，key是属性名称，value是setter方法的封装Invoker对象：SetFieldInvoker
   */
  private final Map<String, Invoker> setMethods = new HashMap<>();

  /**
   * get方法映射，记录属性对应的getter方法，key是属性名称，value是getter方法的封装Invoker对象：GetFieldInvoker
   */
  private final Map<String, Invoker> getMethods = new HashMap<>();

  /**
   * set方法的方法参数类型，key是属性名，value是setter方法的参数的类型
   */
  private final Map<String, Class<?>> setTypes = new HashMap<>();

  /**
   * get方法的方法参数类型，key是属性名，value是getter方法的参数的类型
   */
  private final Map<String, Class<?>> getTypes = new HashMap<>();

  /**
   * 默认构造方法
   */
  private Constructor<?> defaultConstructor;

  /**
   * 不区分大小写的所有的属性名称的集合
   */
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  /**
   * Reflector构造方法，实例化的时候，会解析指定的类
   * @param clazz
   */
  public Reflector(Class<?> clazz) {
    // 设置对应类的Class类型
    type = clazz;
    // 设置默认构造方法，获取clazz所有的构造方法，找到无参构造
    addDefaultConstructor(clazz);
    // 初始化getter方法和参数类型映射，遍历所有getter方法，包括父类的
    addGetMethods(clazz);
    // 初始化setter方法和参数类型映射，遍历所有setter方法，包括父类的
    addSetMethods(clazz);
    // 初始化get和set方法以及类型映射，通过遍历fields属性
    addFields(clazz);
    // 初始化可读的属性名字
    readablePropertyNames = getMethods.keySet().toArray(new String[0]);
    // 初始化可写的属性名字
    writablePropertyNames = setMethods.keySet().toArray(new String[0]);
    // 初始化不区分大小写的属性集合
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  /**
   * 添加默认构造方法
   * 获得clazz所有的构造方法，遍历，查找无参构造方法
   * @param clazz
   */
  private void addDefaultConstructor(Class<?> clazz) {
    // 获取所有的构造方法
    Constructor<?>[] constructors = clazz.getDeclaredConstructors();
    // 遍历构造方法，找到参数长度为0的，就是无参构造
    Arrays.stream(constructors).filter(constructor -> constructor.getParameterTypes().length == 0)
      .findAny().ifPresent(constructor -> this.defaultConstructor = constructor);
  }

  /**
   * 添加getter方法
   * @param clazz
   */
  private void addGetMethods(Class<?> clazz) {
    // 属性和方法的映射，父类和子类都可能定义相同属性的getting方法，所以value是个List
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    /**
     * 获得所有的方法，包括父类中的方法，不包含Object类中的方法，这里面还可能有重复的方法：
     * 子类重写了父类方法，会产生相同签名的方法
     * 但是也可能出现两个签名不同的方法，比如子类重写了父类的方法，但是子类该方法的返回值和父类的不一致，
     * 子类方法的返回值是父类方法的返回值的子类，这样会导致两个不同签名的方法
     */
    Method[] methods = getClassMethods(clazz);
    Arrays
      .stream(methods)
      // 方法的参数长度为0，并且是以get或者is开头的方法，不是setter方法，需要过滤掉
      .filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
      /**
       * 先从方法名转成属性名，再把属性名和对应的方法添加到conflictingGetters映射中
       * 就是将属性名相同的方法添加到同一个List中去
       */
      .forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));
    // 解决冲突的getter方法
    resolveGetterConflicts(conflictingGetters);
  }

  /**
   * 解决getter方法冲突
   * 子类重写父类方法，可能会产生两个签名相同的方法，方法唯一签名规则：返回值类型#方法名:参数1类型,参数2类型,...
   * 一个属性只保留一个对应的方法
   * @param conflictingGetters
   */
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null;
      String propName = entry.getKey();
      boolean isAmbiguous = false;
      for (Method candidate : entry.getValue()) {
        if (winner == null) {
          winner = candidate;
          continue;
        }
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        /**
         * 属性名相同，返回类型也相同的情况
         * 这种特殊的情况如下
         * public boolean isDeleted(){return xxxx}
         * public boolean getDeleted(){return xxxx}
         * 这样两个方法的唯一签名不同，但是属性名都是deleted
         */
        if (candidateType.equals(winnerType)) {
          // 返回类型不是boolean的，冲突
          if (!boolean.class.equals(candidateType)) {
            isAmbiguous = true;
            break;
          }
          // 保留is开头的getter方法，get开头的忽略掉
          else if (candidate.getName().startsWith("is")) {
            winner = candidate;
          }
        }
        // winnerType是candidateType的子类或接口的子实现
        else if (candidateType.isAssignableFrom(winnerType)) {
          // OK getter type is descendant
        }
        // candidateType是winnerType的子类或接口的子实现
        else if (winnerType.isAssignableFrom(candidateType)) {
          winner = candidate;
        } else {
          isAmbiguous = true;
          break;
        }
      }
      // 添加方法到getMethods和getTypes中去
      addGetMethod(propName, winner, isAmbiguous);
    }
  }

  private void addGetMethod(String name, Method method, boolean isAmbiguous) {
    /**
     * 如果名字重复，意味着两个getter方法冲突，创建一个AmbiguousMethodInvoker对象，表示有歧义的方法调用封装
     * 在调用这个方法的时候AmbiguousMethodInvoker的invoke方法会抛异常
     *
     * 其他正常的方法会封装成MethodInvoker
     */
    MethodInvoker invoker = isAmbiguous
        ? new AmbiguousMethodInvoker(method, MessageFormat.format(
            "Illegal overloaded getter method with ambiguous type for property ''{0}'' in class ''{1}''. This breaks the JavaBeans specification and can cause unpredictable results.",
            name, method.getDeclaringClass().getName()))
        : new MethodInvoker(method);
    // 添加到getMethods中
    getMethods.put(name, invoker);
    // 获得方法的返回类型，这里面解析有点复杂
    Type returnType = TypeParameterResolver.resolveReturnType(method, type);
    // 返回类型转换成Class后添加到getTypes中
    getTypes.put(name, typeToClass(returnType));
  }

  private void addSetMethods(Class<?> clazz) {
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    // 获取clazz以及父类的所有方法
    Method[] methods = getClassMethods(clazz);
    // 方法参数长度为1，并且是setter方法
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName()))
      /**
       * 先根据方法名获取属性名，然后解决冲突的setter方法
       * setter冲突的类型是父类的setter方法参数是泛型，
       * 子类重写的方法参数是具体类型，这两个方法生成的唯一签名就不一样
       *
       * 或者是重载方法
       */
      .forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));
    // 解决setter方法冲突
    resolveSetterConflicts(conflictingSetters);
  }


  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    if (isValidPropertyName(name)) {
      List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
      list.add(method);
    }
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (String propName : conflictingSetters.keySet()) {
      List<Method> setters = conflictingSetters.get(propName);
      // 获取getter方法的返回类型，getter方法不存在重载的情况，
      // 可以用getter方法的返回值类型当来推断哪个setter方法更合适
      Class<?> getterType = getTypes.get(propName);
      boolean isGetterAmbiguous = getMethods.get(propName) instanceof AmbiguousMethodInvoker;
      boolean isSetterAmbiguous = false;
      Method match = null;
      for (Method setter : setters) {
        // setter的参数类型和getter方法的返回类型一样，认为是最合适的
        if (!isGetterAmbiguous && setter.getParameterTypes()[0].equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        // 不能根据getter方法的返回类型推断，需要选一个更合适的setter方法
        if (!isSetterAmbiguous) {
          match = pickBetterSetter(match, setter, propName);
          isSetterAmbiguous = match == null;
        }
      }
      // 找到更合适的setter方法
      if (match != null) {
        // 添加setter方法到setMethods中以及setTypes中
        addSetMethod(propName, match);
      }
    }
  }

  /**
   * 从两个setter方法中选一个更合适的
   * @param setter1
   * @param setter2
   * @param property
   * @return
   */
  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    // 参数2是参数1的子类，则参数2更合适
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    }
    // 参数1是参数2的子类，则参数1更合适
    else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    MethodInvoker invoker = new AmbiguousMethodInvoker(setter1,
        MessageFormat.format(
            "Ambiguous setters defined for property ''{0}'' in class ''{1}'' with types ''{2}'' and ''{3}''.",
            property, setter2.getDeclaringClass().getName(), paramType1.getName(), paramType2.getName()));
    setMethods.put(property, invoker);
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(setter1, type);
    setTypes.put(property, typeToClass(paramTypes[0]));
    return null;
  }

  private void addSetMethod(String name, Method method) {
    // 将setter方法封装成一个MethodInvoker对象
    MethodInvoker invoker = new MethodInvoker(method);
    // 添加到setMethods中
    setMethods.put(name, invoker);
    // 解析参数类型
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
    // 将参数类型转换成Class，并添加到setTypes中
    setTypes.put(name, typeToClass(paramTypes[0]));
  }

  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    // 普通类型，可以直接使用
    if (src instanceof Class) {
      result = (Class<?>) src;
    }
    // 参数化类型，带<>的类型
    else if (src instanceof ParameterizedType) {
      // 获取参数类型<>的载体，也就是<>前面的值，比如Map<K, V>，获取到的就是Map
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    }
    // 泛型数组类型
    else if (src instanceof GenericArrayType) {
      // 获取泛型类型数组的声明类型，比如List<T>[] myArray，返回的就是List<T>
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      // 如果泛型数组类型是Class，实例化数组元素，然后获取Class对象
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      }
      // 泛型数组的类型是其他泛型类型，则递归进行转换Class
      else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    // 如果找不到具体类型，使用Object
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  private void addFields(Class<?> clazz) {
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        int modifiers = field.getModifiers();
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
          // 添加到setMethods和setTypes中
          addSetField(field);
        }
      }
      if (!getMethods.containsKey(field.getName())) {
        // 添加到getMethods和getTypes中
        addGetField(field);
      }
    }
    if (clazz.getSuperclass() != null) {
      // 递归处理父类
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler <code>Class.getMethods()</code>,
   * because we want to look for private methods as well.
   *
   * @param clazz The class
   * @return An array containing all methods in this class
   * 获取当前类以及父类中的所有方法，除了Object类
   */
  private Method[] getClassMethods(Class<?> clazz) {
    // 每个方法的唯一签名和方法的映射
    Map<String, Method> uniqueMethods = new HashMap<>();
    Class<?> currentClass = clazz;
    // 依次循环父类，除了Object类
    while (currentClass != null && currentClass != Object.class) {
      // 记录当前类中定义的全部方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      // 当前类实现的接口中的方法也要记录下来
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      // 继续查找父类
      currentClass = currentClass.getSuperclass();
    }

    // 返回方法数组
    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[0]);
  }

  /**
   * 为每个方法生成一个唯一签名，并记录到uniqueMethods这个Map中国
   * @param uniqueMethods
   * @param methods
   */
  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      if (!currentMethod.isBridge()) {
        // 获得方法签名，规则：返回值类型#方法名:参数1类型,参数2类型,...
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        /**
         * 不存在就添加，这里判断主要是因为子类重写了父类方法，会产生相同签名的方法
         * 但是也可能出现两个签名不同的方法，比如子类重写了父类的方法，但是子类该方法的返回值和父类的不一致，
         * 子类方法的返回值是父类方法的返回值的子类，这样会导致两个不同签名的方法
         */
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  /**
   * 获得方法唯一签名
   * 格式：returnType#方法名:参数1类型,参数2类型,参数3类型,...
   * @param method
   * @return
   */
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    // 返回类型
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    // 方法名
    sb.append(method.getName());
    // 方法参数类型
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for.
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object.
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object.
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
