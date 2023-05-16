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

import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.BeanUtils;
import org.springframework.boot.context.properties.bind.Binder.Context;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;

/**
 * {@link DataObjectBinder} for mutable Java Beans.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 */
class JavaBeanBinder implements DataObjectBinder {

	static final JavaBeanBinder INSTANCE = new JavaBeanBinder();

	@Override
	public <T> T bind(ConfigurationPropertyName name, Bindable<T> target, Context context,
			DataObjectPropertyBinder propertyBinder) {
		// 如果 bindable的value不为null 并且 有知道的可绑定属性
		boolean hasKnownBindableProperties = target.getValue() != null && hasKnownBindableProperties(name, context);
		// 获取到bindable对应的bean对象
		// bean对象中持有的BeanProperty是根据class中声明的getter setter方法来的，因此如果只有字段，没有方法的话，无法生成对应的BeanProperty
		Bean<T> bean = Bean.get(target, hasKnownBindableProperties);
		// 如果bean为null的话，直接返回null
		if (bean == null) {
			return null;
		}
		// 调用bean的getSupplier方法返回一个BeanSupplier，它的get方法的逻辑是：
		// 如果bindable的value不为null的话，调用value的get方法获取对应的绑定对象，
		// 否则根据自己的resolvedType(也就是bindable的type的resolvedClass)直接实例化出一个绑定对象。
		// 它和普通supplier不一样的地方在于它会持有get方法返回的对象。
		BeanSupplier<T> beanSupplier = bean.getSupplier(target);
		// 调用bind方法，返回是否绑定成功的标志
		boolean bound = bind(propertyBinder, bean, beanSupplier, context);
		// 如果bound标志为true，调用beanSupplier的get方法获取绑定对象
		return (bound ? beanSupplier.get() : null);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T create(Bindable<T> target, Context context) {
		Class<T> type = (Class<T>) target.getType().resolve();
		return (type != null) ? BeanUtils.instantiateClass(type) : null;
	}

	private boolean hasKnownBindableProperties(ConfigurationPropertyName name, Context context) {
		// 遍历context的ConfigurationPropertySource集合
		for (ConfigurationPropertySource source : context.getSources()) {
			// 如果存在一个source里面的任一ConfigurationPropertyName是name的后代，返回true
			if (source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT) {
				return true;
			}
		}
		// 否则返回false
		return false;
	}

	private <T> boolean bind(DataObjectPropertyBinder propertyBinder, Bean<T> bean, BeanSupplier<T> beanSupplier,
			Context context) {
		boolean bound = false;
		// 获取到bean对象中properties这个map中的所有value，也就是BeanProperty，进行遍历
		for (BeanProperty beanProperty : bean.getProperties().values()) {
			// 调用bind的重载方法对属性进行绑定，并将结果返回和bound标志进行or操作
			bound |= bind(beanSupplier, propertyBinder, beanProperty);
			// 将context中的ConfigurationProperty置为null
			context.clearConfigurationProperty();
		}
		// 返回bound标志
		return bound;
	}

	private <T> boolean bind(BeanSupplier<T> beanSupplier, DataObjectPropertyBinder propertyBinder,
			BeanProperty property) {
		// 获取属性名称
		String propertyName = property.getName();
		// 获取属性对应的resolvableType，逻辑是：
		// 如果setter方法不为空，从方法参数上获取；
		// 否则从getter方法的返回值上获取
		ResolvableType type = property.getType();
		// 根据beanSupplier生成一个属性值获取的supplier，逻辑是：
		// 调用beanSupplier的get方法获取到属性所在的对象，然后反射调用getter方法获取属性值
		Supplier<Object> value = property.getValue(beanSupplier);
		// 从属性的field字段上获取声明的注解
		Annotation[] annotations = property.getAnnotations();
		// 调用propertyBinder的bindProperty方法，具体逻辑是调用binder的bind方法，只不过是propertyName拼接到name之后，
		// 并且bindable替换为属性对应的类型，且其中bindable的value是上面生成的属性值获取的supplier。
		Object bound = propertyBinder.bindProperty(propertyName,
				Bindable.of(type).withSuppliedValue(value).withAnnotations(annotations));
		// 如果绑定的结果为null的话，说明没有找到对应的属性值，返回false
		if (bound == null) {
			return false;
		}
		// 如果找到了对应的属性值，判断property是否是可以set的，即是否存在set方法
		if (property.isSettable()) {
			// 如果可以，通过beanSupplier获取到持有属性的对象，并通过反射调用set方法将属性值填入对象中
			property.setValue(beanSupplier, bound);
		}
		// 如果属性没有set方法的话，并且 没有get方法或者返回的绑定对象不等于属性原本的值，抛出异常
		else if (value == null || !bound.equals(value.get())) {
			throw new IllegalStateException("No setter found for property: " + property.getName());
		}
		// 返回true，表示属性绑定成功
		return true;
	}

	/**
	 * The bean being bound.
	 *
	 * @param <T> the bean type
	 */
	static class Bean<T> {

		private static Bean<?> cached;

		private final ResolvableType type;

		private final Class<?> resolvedType;

		private final Map<String, BeanProperty> properties = new LinkedHashMap<>();

		Bean(ResolvableType type, Class<?> resolvedType) {
			this.type = type;
			this.resolvedType = resolvedType;
			// 根据resolvedType这个class对象添加属性进properties这个map中
			addProperties(resolvedType);
		}

		private void addProperties(Class<?> type) {
			// 当type不为null且type不是Object.class的时候
			while (type != null && !Object.class.equals(type)) {
				// 获取到type声明的方法，并且根据名称排序
				Method[] declaredMethods = getSorted(type, Class::getDeclaredMethods, Method::getName);
				// 获取到type中声明的字段，并且根据名称排序
				Field[] declaredFields = getSorted(type, Class::getDeclaredFields, Field::getName);
				// 调用addProperties的重载方法，根据方法和字段添加属性
				addProperties(declaredMethods, declaredFields);
				// 然后循环查找其父类
				type = type.getSuperclass();
			}
		}

		private <S, E> E[] getSorted(S source, Function<S, E[]> elements, Function<E, String> name) {
			E[] result = elements.apply(source);
			Arrays.sort(result, Comparator.comparing(name));
			return result;
		}

		protected void addProperties(Method[] declaredMethods, Field[] declaredFields) {
			// 遍历方法数组
			for (int i = 0; i < declaredMethods.length; i++) {
				// 判断方法是否是候选的，如果不是，将数组该下标的元素置为null。
				// 如果方法没有被private protected abstract static修饰，且方法不是在Object类和Class类中声明的，并且方法名中不包含$字符，那么该方法是可候选的
				if (!isCandidate(declaredMethods[i])) {
					declaredMethods[i] = null;
				}
			}
			for (Method method : declaredMethods) {
				// 如果是参数数量为0的以get开头的方法，会初始化一个BeanProperty放入到properties中，并将放入放入BeanProperty的getter属性中
				addMethodIfPossible(method, "get", 0, BeanProperty::addGetter);
				// 如果找到了参数数量为0的以is开头的方法，也会放入到BeanProperty的getter属性中
				addMethodIfPossible(method, "is", 0, BeanProperty::addGetter);
			}
			for (Method method : declaredMethods) {
				// 如果是参数数量为1的以set开头的方法，会放入到BeanProperty的setter属性中
				addMethodIfPossible(method, "set", 1, BeanProperty::addSetter);
			}
			for (Field field : declaredFields) {
				// 将字段添加进properties中
				addField(field);
			}
		}

		private boolean isCandidate(Method method) {
			// 获取方法的访问修饰符
			int modifiers = method.getModifiers();
			// 如果方法没有被private protected abstract static修饰，且方法不是在Object类和Class类中声明的，并且方法名中不包含$字符，那么该方法是可候选的
			return !Modifier.isPrivate(modifiers) && !Modifier.isProtected(modifiers) && !Modifier.isAbstract(modifiers)
					&& !Modifier.isStatic(modifiers) && !Object.class.equals(method.getDeclaringClass())
					&& !Class.class.equals(method.getDeclaringClass()) && method.getName().indexOf('$') == -1;
		}

		private void addMethodIfPossible(Method method, String prefix, int parameterCount,
				BiConsumer<BeanProperty, Method> consumer) {
			// 如果方法不为null，且方法参数满足条件，并且方法名是以prefix为前缀的，且方法名长度大于prefix的长度，执行if里的逻辑
			if (method != null && method.getParameterCount() == parameterCount && method.getName().startsWith(prefix)
					&& method.getName().length() > prefix.length()) {
				// 将方法名截取掉prefix的部分，并且将首字母小写，获取到属性的名称
				String propertyName = Introspector.decapitalize(method.getName().substring(prefix.length()));
				// 如果properties这个map中不存在propertyName对应的BeanProperty，就根据方法名和bean对象持有的type调用getBeanProperty方法初始化一个BeanProperty，
				// 并放入map中，然后调用consumer的accept方法将method加入到BeanProperty对应的属性中(可能是getter方法，也可能是setter方法，取决于传入的consumer)。
				consumer.accept(this.properties.computeIfAbsent(propertyName, this::getBeanProperty), method);
			}
		}

		private BeanProperty getBeanProperty(String name) {
			return new BeanProperty(name, this.type);
		}

		private void addField(Field field) {
			// 从properties中根据字段名获取BeanProperty
			BeanProperty property = this.properties.get(field.getName());
			if (property != null) {
				// 如果property不为null的话，将field添加进property的field字段中
				property.addField(field);
			}
		}

		Map<String, BeanProperty> getProperties() {
			return this.properties;
		}

		@SuppressWarnings("unchecked")
		BeanSupplier<T> getSupplier(Bindable<T> target) {
			return new BeanSupplier<>(() -> {
				T instance = null;
				if (target.getValue() != null) {
					instance = target.getValue().get();
				}
				if (instance == null) {
					instance = (T) BeanUtils.instantiateClass(this.resolvedType);
				}
				return instance;
			});
		}

		@SuppressWarnings("unchecked")
		static <T> Bean<T> get(Bindable<T> bindable, boolean canCallGetValue) {
			ResolvableType type = bindable.getType();
			Class<?> resolvedType = type.resolve(Object.class);
			Supplier<T> value = bindable.getValue();
			T instance = null;
			// 如果canCallGetValue为true 且 value不为null
			if (canCallGetValue && value != null) {
				// 调用value的get方法获取要绑定的对象
				instance = value.get();
				// 获取instance的类型
				resolvedType = (instance != null) ? instance.getClass() : resolvedType;
			}
			// 如果要绑定的对象为null，且对应的类型无法实例化，那么直接返回null
			if (instance == null && !isInstantiable(resolvedType)) {
				return null;
			}
			// 获取缓存的bean
			Bean<?> bean = Bean.cached;
			// 如果缓存的bean为null 或者 缓存的bean和type不是一个类型的
			if (bean == null || !bean.isOfType(type, resolvedType)) {
				// 那么根据type和resolvedType初始化一个新的bean
				bean = new Bean<>(type, resolvedType);
				// 并且放入缓存
				cached = bean;
			}
			// 返回bean
			return (Bean<T>) bean;
		}

		private static boolean isInstantiable(Class<?> type) {
			if (type.isInterface()) {
				return false;
			}
			try {
				type.getDeclaredConstructor();
				return true;
			}
			catch (Exception ex) {
				return false;
			}
		}

		private boolean isOfType(ResolvableType type, Class<?> resolvedType) {
			if (this.type.hasGenerics() || type.hasGenerics()) {
				return this.type.equals(type);
			}
			return this.resolvedType != null && this.resolvedType.equals(resolvedType);
		}

	}

	private static class BeanSupplier<T> implements Supplier<T> {

		private final Supplier<T> factory;

		private T instance;

		BeanSupplier(Supplier<T> factory) {
			this.factory = factory;
		}

		@Override
		public T get() {
			if (this.instance == null) {
				this.instance = this.factory.get();
			}
			return this.instance;
		}

	}

	/**
	 * A bean property being bound.
	 */
	static class BeanProperty {

		private final String name;

		private final ResolvableType declaringClassType;

		private Method getter;

		private Method setter;

		private Field field;

		BeanProperty(String name, ResolvableType declaringClassType) {
			// 将name转换为dashed格式的
			this.name = DataObjectPropertyName.toDashedForm(name);
			this.declaringClassType = declaringClassType;
		}

		void addGetter(Method getter) {
			if (this.getter == null) {
				this.getter = getter;
			}
		}

		void addSetter(Method setter) {
			// 如果setter为null 或者 判断出该方法是更好的setter的时候，赋值
			if (this.setter == null || isBetterSetter(setter)) {
				this.setter = setter;
			}
		}

		private boolean isBetterSetter(Method setter) {
			// 如果getter不为null，并且getter方法的返回值类型等于setter方法的第一个参数类型，那么可以判断该setter方法是更好的
			return this.getter != null && this.getter.getReturnType().equals(setter.getParameterTypes()[0]);
		}

		void addField(Field field) {
			if (this.field == null) {
				this.field = field;
			}
		}

		String getName() {
			return this.name;
		}

		ResolvableType getType() {
			if (this.setter != null) {
				MethodParameter methodParameter = new MethodParameter(this.setter, 0);
				return ResolvableType.forMethodParameter(methodParameter, this.declaringClassType);
			}
			MethodParameter methodParameter = new MethodParameter(this.getter, -1);
			return ResolvableType.forMethodParameter(methodParameter, this.declaringClassType);
		}

		Annotation[] getAnnotations() {
			try {
				return (this.field != null) ? this.field.getDeclaredAnnotations() : null;
			}
			catch (Exception ex) {
				return null;
			}
		}

		Supplier<Object> getValue(Supplier<?> instance) {
			if (this.getter == null) {
				return null;
			}
			return () -> {
				try {
					this.getter.setAccessible(true);
					return this.getter.invoke(instance.get());
				}
				catch (Exception ex) {
					throw new IllegalStateException("Unable to get value for property " + this.name, ex);
				}
			};
		}

		boolean isSettable() {
			return this.setter != null;
		}

		void setValue(Supplier<?> instance, Object value) {
			try {
				this.setter.setAccessible(true);
				this.setter.invoke(instance.get(), value);
			}
			catch (Exception ex) {
				throw new IllegalStateException("Unable to set value for property " + this.name, ex);
			}
		}

	}

}
