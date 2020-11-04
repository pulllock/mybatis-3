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
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;

/**
 * @author Iwao AVE!
 * 用来解析方法的参数、方法的返回类型、类中定义的字段类型
 *
 * Type是java中所有类型的父接口
 * Class表示JVM中的一个类或接口，可通过xxx.class、getClass()、Class.forName()、ClassLoader等方式获取一个类或者接口的Class
 * ParameterizedType表示参数化类型，有参数的类型，比如Collection<T>、Map<K,V>
 * TypeVariable类型变量，是JVM在编译泛型前的信息
 */
public class TypeParameterResolver {

  /**
   * @return The field type as {@link Type}. If it has type parameters in the declaration,<br>
   *         they will be resolved to the actual runtime {@link Type}s.
   */
  public static Type resolveFieldType(Field field, Type srcType) {
    Type fieldType = field.getGenericType();
    Class<?> declaringClass = field.getDeclaringClass();
    return resolveType(fieldType, srcType, declaringClass);
  }

  /**
   * 解析方法的返回类型
   * @param method 方法
   * @param srcType 方法所属的类的类型
   * @return The return type of the method as {@link Type}. If it has type parameters in the declaration,<br>
   *         they will be resolved to the actual runtime {@link Type}s.
   */
  public static Type resolveReturnType(Method method, Type srcType) {
    /**
     * 获取方法的返回类型对应的Type对象
     * 如果方法的返回值是普通简单类型，如Object、int、String等，返回的就是这些类型
     * 如果方法的返回值是泛型，返回的就是参数化类型
     */
    Type returnType = method.getGenericReturnType();
    // 返回定义方法所在的类的Class对象
    Class<?> declaringClass = method.getDeclaringClass();
    // 解析类型
    return resolveType(returnType, srcType, declaringClass);
  }

  /**
   * @param method 要解析的方法
   * @param srcType 要解析的方法所在的类的类型
   * @return The parameter types of the method as an array of {@link Type}s. If they have type parameters in the declaration,<br>
   *         they will be resolved to the actual runtime {@link Type}s.
   */
  public static Type[] resolveParamTypes(Method method, Type srcType) {
    // 获取参数类型，可能是泛型参数类型
    Type[] paramTypes = method.getGenericParameterTypes();
    // 方法所在类的类型
    Class<?> declaringClass = method.getDeclaringClass();
    Type[] result = new Type[paramTypes.length];
    for (int i = 0; i < paramTypes.length; i++) {
      // 解析类型
      result[i] = resolveType(paramTypes[i], srcType, declaringClass);
    }
    return result;
  }

  /**
   * 解析类型
   * @param type 要解析的类型
   * @param srcType 要解析的类型所在的类的类型
   * @param declaringClass 要解析的类型所在的类的Class对象
   * @return
   */
  private static Type resolveType(Type type, Type srcType, Class<?> declaringClass) {
    // 类型变量，JVM编译泛型前的信息，比如List<T>中的T
    if (type instanceof TypeVariable) {
      // 解析类型变量
      return resolveTypeVar((TypeVariable<?>) type, srcType, declaringClass);
    }
    // 参数化类型，带<>的类型，比如List<String>
    else if (type instanceof ParameterizedType) {
      return resolveParameterizedType((ParameterizedType) type, srcType, declaringClass);
    }
    // 泛型数组类型，比如List<T>[] myArray，类型就是List<T>[]
    else if (type instanceof GenericArrayType) {
      return resolveGenericArrayType((GenericArrayType) type, srcType, declaringClass);
    } else {
      // Class类型，不需要解析，返回即可
      return type;
    }
  }

  private static Type resolveGenericArrayType(GenericArrayType genericArrayType, Type srcType, Class<?> declaringClass) {
    Type componentType = genericArrayType.getGenericComponentType();
    Type resolvedComponentType = null;
    if (componentType instanceof TypeVariable) {
      resolvedComponentType = resolveTypeVar((TypeVariable<?>) componentType, srcType, declaringClass);
    } else if (componentType instanceof GenericArrayType) {
      resolvedComponentType = resolveGenericArrayType((GenericArrayType) componentType, srcType, declaringClass);
    } else if (componentType instanceof ParameterizedType) {
      resolvedComponentType = resolveParameterizedType((ParameterizedType) componentType, srcType, declaringClass);
    }
    if (resolvedComponentType instanceof Class) {
      return Array.newInstance((Class<?>) resolvedComponentType, 0).getClass();
    } else {
      return new GenericArrayTypeImpl(resolvedComponentType);
    }
  }

  /**
   * 解析参数化类型
   * 参数化类型，带<>的类型，比如List<String>
   * @param parameterizedType 要解析的类型
   * @param srcType 要解析的类型所在类的类型
   * @param declaringClass 要解析的类型所在的类的Class对象
   * @return
   */
  private static ParameterizedType resolveParameterizedType(ParameterizedType parameterizedType, Type srcType, Class<?> declaringClass) {
    // 获取参数类型<>的载体，也就是<>前面的值，比如Map<K, V>，获取到的就是Map
    Class<?> rawType = (Class<?>) parameterizedType.getRawType();
    // 获取参数类型的实际类型参数，比如Map<String,Integer>获取到的就是String、Integer
    Type[] typeArgs = parameterizedType.getActualTypeArguments();
    Type[] args = new Type[typeArgs.length];
    // 挨个解析参数类型的实际类型参数
    for (int i = 0; i < typeArgs.length; i++) {
      // 类型变量
      if (typeArgs[i] instanceof TypeVariable) {
        args[i] = resolveTypeVar((TypeVariable<?>) typeArgs[i], srcType, declaringClass);
      }
      // 参数化类型
      else if (typeArgs[i] instanceof ParameterizedType) {
        args[i] = resolveParameterizedType((ParameterizedType) typeArgs[i], srcType, declaringClass);
      }
      // 通配符类型
      else if (typeArgs[i] instanceof WildcardType) {
        args[i] = resolveWildcardType((WildcardType) typeArgs[i], srcType, declaringClass);
      }
      // 普通的Class对象
      else {
        args[i] = typeArgs[i];
      }
    }
    // 实例化一个参数化类型实例对象
    return new ParameterizedTypeImpl(rawType, null, args);
  }

  private static Type resolveWildcardType(WildcardType wildcardType, Type srcType, Class<?> declaringClass) {
    Type[] lowerBounds = resolveWildcardTypeBounds(wildcardType.getLowerBounds(), srcType, declaringClass);
    Type[] upperBounds = resolveWildcardTypeBounds(wildcardType.getUpperBounds(), srcType, declaringClass);
    return new WildcardTypeImpl(lowerBounds, upperBounds);
  }

  private static Type[] resolveWildcardTypeBounds(Type[] bounds, Type srcType, Class<?> declaringClass) {
    Type[] result = new Type[bounds.length];
    for (int i = 0; i < bounds.length; i++) {
      if (bounds[i] instanceof TypeVariable) {
        result[i] = resolveTypeVar((TypeVariable<?>) bounds[i], srcType, declaringClass);
      } else if (bounds[i] instanceof ParameterizedType) {
        result[i] = resolveParameterizedType((ParameterizedType) bounds[i], srcType, declaringClass);
      } else if (bounds[i] instanceof WildcardType) {
        result[i] = resolveWildcardType((WildcardType) bounds[i], srcType, declaringClass);
      } else {
        result[i] = bounds[i];
      }
    }
    return result;
  }

  /**
   * 解析类型变量的实际类型，比如List<T>中的T
   * @param typeVar 要解析的类型变量
   * @param srcType 要解析的类型变量所在的类的类型
   * @param declaringClass 要解析的类型变量所在的类的Class对象
   * @return
   */
  private static Type resolveTypeVar(TypeVariable<?> typeVar, Type srcType, Class<?> declaringClass) {
    Type result;

    // 表示所在类的Class对象
    Class<?> clazz;
    // 所在类是一个Class类型的
    if (srcType instanceof Class) {
      clazz = (Class<?>) srcType;
    }
    // 所在类是一个参数化类型的，也就是一个带<>的类
    else if (srcType instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) srcType;
      // 需要获取参数类型的原始类型，比如Map<K,V> 获取到的就是Map
      clazz = (Class<?>) parameterizedType.getRawType();
    } else {
      throw new IllegalArgumentException("The 2nd arg must be Class or ParameterizedType, but was: " + srcType.getClass());
    }

    // 如果当前类就是声明了类型变量的类，可以直接获取typeVar的类型
    if (clazz == declaringClass) {
      // typeVar声明了上界，就获取上界的第一个
      Type[] bounds = typeVar.getBounds();
      if (bounds.length > 0) {
        return bounds[0];
      }
      // 如果没有声明上界，就返回Object
      return Object.class;
    }

    // 扫描父类，确定类型
    Type superclass = clazz.getGenericSuperclass();
    result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superclass);
    if (result != null) {
      return result;
    }

    // 通过父类无法确定类型，扫描实现的接口确定类型
    Type[] superInterfaces = clazz.getGenericInterfaces();
    for (Type superInterface : superInterfaces) {
      result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superInterface);
      if (result != null) {
        return result;
      }
    }
    return Object.class;
  }

  private static Type scanSuperTypes(TypeVariable<?> typeVar, Type srcType, Class<?> declaringClass, Class<?> clazz, Type superclass) {
    if (superclass instanceof ParameterizedType) {
      ParameterizedType parentAsType = (ParameterizedType) superclass;
      Class<?> parentAsClass = (Class<?>) parentAsType.getRawType();
      TypeVariable<?>[] parentTypeVars = parentAsClass.getTypeParameters();
      if (srcType instanceof ParameterizedType) {
        parentAsType = translateParentTypeVars((ParameterizedType) srcType, clazz, parentAsType);
      }
      if (declaringClass == parentAsClass) {
        for (int i = 0; i < parentTypeVars.length; i++) {
          if (typeVar == parentTypeVars[i]) {
            return parentAsType.getActualTypeArguments()[i];
          }
        }
      }
      if (declaringClass.isAssignableFrom(parentAsClass)) {
        return resolveTypeVar(typeVar, parentAsType, declaringClass);
      }
    } else if (superclass instanceof Class && declaringClass.isAssignableFrom((Class<?>) superclass)) {
      return resolveTypeVar(typeVar, superclass, declaringClass);
    }
    return null;
  }

  private static ParameterizedType translateParentTypeVars(ParameterizedType srcType, Class<?> srcClass, ParameterizedType parentType) {
    Type[] parentTypeArgs = parentType.getActualTypeArguments();
    Type[] srcTypeArgs = srcType.getActualTypeArguments();
    TypeVariable<?>[] srcTypeVars = srcClass.getTypeParameters();
    Type[] newParentArgs = new Type[parentTypeArgs.length];
    boolean noChange = true;
    for (int i = 0; i < parentTypeArgs.length; i++) {
      if (parentTypeArgs[i] instanceof TypeVariable) {
        for (int j = 0; j < srcTypeVars.length; j++) {
          if (srcTypeVars[j] == parentTypeArgs[i]) {
            noChange = false;
            newParentArgs[i] = srcTypeArgs[j];
          }
        }
      } else {
        newParentArgs[i] = parentTypeArgs[i];
      }
    }
    return noChange ? parentType : new ParameterizedTypeImpl((Class<?>)parentType.getRawType(), null, newParentArgs);
  }

  private TypeParameterResolver() {
    super();
  }

  static class ParameterizedTypeImpl implements ParameterizedType {
    private Class<?> rawType;

    private Type ownerType;

    private Type[] actualTypeArguments;

    public ParameterizedTypeImpl(Class<?> rawType, Type ownerType, Type[] actualTypeArguments) {
      super();
      this.rawType = rawType;
      this.ownerType = ownerType;
      this.actualTypeArguments = actualTypeArguments;
    }

    @Override
    public Type[] getActualTypeArguments() {
      return actualTypeArguments;
    }

    @Override
    public Type getOwnerType() {
      return ownerType;
    }

    @Override
    public Type getRawType() {
      return rawType;
    }

    @Override
    public String toString() {
      return "ParameterizedTypeImpl [rawType=" + rawType + ", ownerType=" + ownerType + ", actualTypeArguments=" + Arrays.toString(actualTypeArguments) + "]";
    }
  }

  static class WildcardTypeImpl implements WildcardType {
    private Type[] lowerBounds;

    private Type[] upperBounds;

    WildcardTypeImpl(Type[] lowerBounds, Type[] upperBounds) {
      super();
      this.lowerBounds = lowerBounds;
      this.upperBounds = upperBounds;
    }

    @Override
    public Type[] getLowerBounds() {
      return lowerBounds;
    }

    @Override
    public Type[] getUpperBounds() {
      return upperBounds;
    }
  }

  static class GenericArrayTypeImpl implements GenericArrayType {
    private Type genericComponentType;

    GenericArrayTypeImpl(Type genericComponentType) {
      super();
      this.genericComponentType = genericComponentType;
    }

    @Override
    public Type getGenericComponentType() {
      return genericComponentType;
    }
  }
}
