/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.properties.bind;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import kotlin.reflect.KFunction;
import kotlin.reflect.KParameter;
import kotlin.reflect.jvm.ReflectJvmMapping;

import org.springframework.beans.BeanUtils;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.KotlinDetector;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionException;
import org.springframework.util.Assert;

/**
 * {@link DataObjectBinder} for immutable value objects.
 *
 * @author Madhura Bhave
 * @author Stephane Nicoll
 * @author Phillip Webb
 */
class ValueObjectBinder implements DataObjectBinder {

	private final BindConstructorProvider constructorProvider;

	ValueObjectBinder(BindConstructorProvider constructorProvider) {
		this.constructorProvider = constructorProvider;
	}

	@Override
	public <T> T bind(ConfigurationPropertyName name, Bindable<T> target, Binder.Context context,
			DataObjectPropertyBinder propertyBinder) {
		// 根据bindable以及constructorProvider以及context获取一个ValueObject
		ValueObject<T> valueObject = ValueObject.get(target, this.constructorProvider, context);
		if (valueObject == null) {
			return null;
		}
		// 将bindable的type压入context的constructorBindings栈中
		context.pushConstructorBoundTypes(target.getType().resolve());
		// 获取到ValueObject持有的ConstructorParameter集合
		List<ConstructorParameter> parameters = valueObject.getConstructorParameters();
		List<Object> args = new ArrayList<>(parameters.size());
		boolean bound = false;
		// 遍历ConstructorParameter集合
		for (ConstructorParameter parameter : parameters) {
			// 调用其bind方法，将propertyBinder传入，获取到参数对应的对象
			Object arg = parameter.bind(propertyBinder);
			// 如果参数对象arg不为null的话，将bound标记为true
			bound = bound || arg != null;
			// 如果参数arg为null，从context中获取默认值
			arg = (arg != null) ? arg : getDefaultValue(context, parameter);
			// 将参数arg添加进集合
			args.add(arg);
		}
		// 将context中的ConfigurationProperty置为null
		context.clearConfigurationProperty();
		// 将之前入栈的type出栈
		context.popConstructorBoundTypes();
		// 如果bound标记为true的话，使用构造器初始化对象并返回，否则返回null
		return bound ? valueObject.instantiate(args) : null;
	}

	@Override
	public <T> T create(Bindable<T> target, Binder.Context context) {
		ValueObject<T> valueObject = ValueObject.get(target, this.constructorProvider, context);
		if (valueObject == null) {
			return null;
		}
		List<ConstructorParameter> parameters = valueObject.getConstructorParameters();
		List<Object> args = new ArrayList<>(parameters.size());
		for (ConstructorParameter parameter : parameters) {
			args.add(getDefaultValue(context, parameter));
		}
		return valueObject.instantiate(args);
	}

	private <T> T getDefaultValue(Binder.Context context, ConstructorParameter parameter) {
		// 获取参数的resolvableType
		ResolvableType type = parameter.getType();
		// 获取参数上标注的注解
		Annotation[] annotations = parameter.getAnnotations();
		for (Annotation annotation : annotations) {
			// 如果存在@DefaultValue注解的话，获取注解的value值
			if (annotation instanceof DefaultValue) {
				String[] defaultValue = ((DefaultValue) annotation).value();
				// 如果value数组长度为0
				if (defaultValue.length == 0) {
					return getNewInstanceIfPossible(context, type);
				}
				// 如果value数组长度不为0，调用convertDefaultValue方法将其转为参数对应的类型
				return convertDefaultValue(context.getConverter(), defaultValue, type, annotations);
			}
		}
		// 如果参数没有标注@DefaultValue注解，返回null
		return null;
	}

	private <T> T convertDefaultValue(BindConverter converter, String[] defaultValue, ResolvableType type,
			Annotation[] annotations) {
		try {
			return converter.convert(defaultValue, type, annotations);
		}
		catch (ConversionException ex) {
			// Try again in case ArrayToObjectConverter is not in play
			// 如果没有数组转换为对象的转换器的话，判断defaultValue数组长度是否为1，如果是，将数组中的第一个元素进行转换
			if (defaultValue.length == 1) {
				return converter.convert(defaultValue[0], type, annotations);
			}
			// 否则抛出异常
			throw ex;
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T getNewInstanceIfPossible(Binder.Context context, ResolvableType type) {
		Class<T> resolved = (Class<T>) type.resolve();
		Assert.state(resolved == null || isEmptyDefaultValueAllowed(resolved),
				() -> "Parameter of type " + type + " must have a non-empty default value.");
		T instance = create(Bindable.of(type), context);
		if (instance != null) {
			return instance;
		}
		return (resolved != null) ? BeanUtils.instantiateClass(resolved) : null;
	}

	private boolean isEmptyDefaultValueAllowed(Class<?> type) {
		if (type.isPrimitive() || type.isEnum() || isAggregate(type) || type.getName().startsWith("java.lang")) {
			return false;
		}
		return true;
	}

	private boolean isAggregate(Class<?> type) {
		return type.isArray() || Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type);
	}

	/**
	 * The value object being bound.
	 *
	 * @param <T> the value object type
	 */
	private abstract static class ValueObject<T> {

		private final Constructor<T> constructor;

		protected ValueObject(Constructor<T> constructor) {
			this.constructor = constructor;
		}

		T instantiate(List<Object> args) {
			return BeanUtils.instantiateClass(this.constructor, args.toArray());
		}

		abstract List<ConstructorParameter> getConstructorParameters();

		@SuppressWarnings("unchecked")
		static <T> ValueObject<T> get(Bindable<T> bindable, BindConstructorProvider constructorProvider,
				Binder.Context context) {
			// 获取到bindable的type
			Class<T> type = (Class<T>) bindable.getType().resolve();
			// 如果type为null或者type是枚举类或者是抽象类的话，返回null
			if (type == null || type.isEnum() || Modifier.isAbstract(type.getModifiers())) {
				return null;
			}
			// 通过constructorProvider获取构造器，从context中获取当前是否是嵌套的构造器绑定
			// 只有类中只存在一个有参构造器的时候，才会返回，否则都只返回null。
			// 并且如果bindable的value不为null的话，也直接返回null，
			// 因为bindable的value存在的话，就不需要通过构造器去构造一个实例对象出来了，直接跳过ValueObjectBinder这个binder
			Constructor<?> bindConstructor = constructorProvider.getBindConstructor(bindable,
					context.isNestedConstructorBinding());
			// 如果获取的构造器为null的话，直接返回null
			if (bindConstructor == null) {
				return null;
			}
			// 判断是否是kotlin语言
			if (KotlinDetector.isKotlinType(type)) {
				return KotlinValueObject.get((Constructor<T>) bindConstructor, bindable.getType());
			}
			// 调用DefaultValueObject的get方法获取绑定对象
			return DefaultValueObject.get(bindConstructor, bindable.getType());
		}

	}

	/**
	 * A {@link ValueObject} implementation that is aware of Kotlin specific constructs.
	 */
	private static final class KotlinValueObject<T> extends ValueObject<T> {

		private final List<ConstructorParameter> constructorParameters;

		private KotlinValueObject(Constructor<T> primaryConstructor, KFunction<T> kotlinConstructor,
				ResolvableType type) {
			super(primaryConstructor);
			this.constructorParameters = parseConstructorParameters(kotlinConstructor, type);
		}

		private List<ConstructorParameter> parseConstructorParameters(KFunction<T> kotlinConstructor,
				ResolvableType type) {
			List<KParameter> parameters = kotlinConstructor.getParameters();
			List<ConstructorParameter> result = new ArrayList<>(parameters.size());
			for (KParameter parameter : parameters) {
				String name = parameter.getName();
				ResolvableType parameterType = ResolvableType
						.forType(ReflectJvmMapping.getJavaType(parameter.getType()), type);
				Annotation[] annotations = parameter.getAnnotations().toArray(new Annotation[0]);
				result.add(new ConstructorParameter(name, parameterType, annotations));
			}
			return Collections.unmodifiableList(result);
		}

		@Override
		List<ConstructorParameter> getConstructorParameters() {
			return this.constructorParameters;
		}

		static <T> ValueObject<T> get(Constructor<T> bindConstructor, ResolvableType type) {
			KFunction<T> kotlinConstructor = ReflectJvmMapping.getKotlinFunction(bindConstructor);
			if (kotlinConstructor != null) {
				return new KotlinValueObject<>(bindConstructor, kotlinConstructor, type);
			}
			return DefaultValueObject.get(bindConstructor, type);
		}

	}

	/**
	 * A default {@link ValueObject} implementation that uses only standard Java
	 * reflection calls.
	 */
	private static final class DefaultValueObject<T> extends ValueObject<T> {

		// 初始化一个默认的参数名称发现器，其构造方法里添加了一个 基础反射参数名发现器 和一个 LocalVariableTable参数名发现器
		// DefaultParameterNameDiscover继承了PrioritizedParameterNameDiscover，这个类持有了一个发现器集合，可以向集合中添加不同的发现器，
		// 并且它的参数名发现方法是遍历其持有的所有发现器来查找参数名。
		// 如果反射拿不到参数名，就会尝试从class文件中method的属性表的Code属性中的LocalVariableTable属性表中取参数名
		private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

		private final List<ConstructorParameter> constructorParameters;

		private DefaultValueObject(Constructor<T> constructor, ResolvableType type) {
			super(constructor);
			// 解析构造器需要的参数
			this.constructorParameters = parseConstructorParameters(constructor, type);
		}

		private static List<ConstructorParameter> parseConstructorParameters(Constructor<?> constructor,
				ResolvableType type) {
			// 通过参数名发现器获取构造器的参数名
			String[] names = PARAMETER_NAME_DISCOVERER.getParameterNames(constructor);
			Assert.state(names != null, () -> "Failed to extract parameter names for " + constructor);
			// 反射获取构造器的参数
			Parameter[] parameters = constructor.getParameters();
			List<ConstructorParameter> result = new ArrayList<>(parameters.length);
			for (int i = 0; i < parameters.length; i++) {
				String name = names[i];
				// 获取到方法参数的ResolvableType
				ResolvableType parameterType = ResolvableType.forMethodParameter(new MethodParameter(constructor, i),
						type);
				// 获取参数上声明的注解
				Annotation[] annotations = parameters[i].getDeclaredAnnotations();
				// 将参数名，参数ResolvableType，参数注解封装成一个ConstructorParameter对象放入集合中
				result.add(new ConstructorParameter(name, parameterType, annotations));
			}
			// 将集合变为不可变集合返回
			return Collections.unmodifiableList(result);
		}

		@Override
		List<ConstructorParameter> getConstructorParameters() {
			return this.constructorParameters;
		}

		@SuppressWarnings("unchecked")
		static <T> ValueObject<T> get(Constructor<?> bindConstructor, ResolvableType type) {
			return new DefaultValueObject<>((Constructor<T>) bindConstructor, type);
		}

	}

	/**
	 * A constructor parameter being bound.
	 */
	private static class ConstructorParameter {

		private final String name;

		private final ResolvableType type;

		private final Annotation[] annotations;

		ConstructorParameter(String name, ResolvableType type, Annotation[] annotations) {
			// 将name转换为dashed模式
			this.name = DataObjectPropertyName.toDashedForm(name);
			this.type = type;
			this.annotations = annotations;
		}

		Object bind(DataObjectPropertyBinder propertyBinder) {
			// 调用propertyBinder的bindProperty方法，内部逻辑是调用的binder的bind方法，将参数的name拼接到了binder的ConfigurationPropertyName之后，
			// target替换为构造器参数对应的bindable
			return propertyBinder.bindProperty(this.name, Bindable.of(this.type).withAnnotations(this.annotations));
		}

		Annotation[] getAnnotations() {
			return this.annotations;
		}

		ResolvableType getType() {
			return this.type;
		}

	}

}
