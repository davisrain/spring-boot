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

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.env.Environment;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.util.Assert;

/**
 * A container object which Binds objects from one or more
 * {@link ConfigurationPropertySource ConfigurationPropertySources}.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 * @since 2.0.0
 */
public class Binder {

	private static final Set<Class<?>> NON_BEAN_CLASSES = Collections
			.unmodifiableSet(new HashSet<>(Arrays.asList(Object.class, Class.class)));

	private final Iterable<ConfigurationPropertySource> sources;

	private final PlaceholdersResolver placeholdersResolver;

	private final ConversionService conversionService;

	private final Consumer<PropertyEditorRegistry> propertyEditorInitializer;

	private final BindHandler defaultBindHandler;

	private final List<DataObjectBinder> dataObjectBinders;

	/**
	 * Create a new {@link Binder} instance for the specified sources. A
	 * {@link DefaultFormattingConversionService} will be used for all conversion.
	 * @param sources the sources used for binding
	 */
	public Binder(ConfigurationPropertySource... sources) {
		this(Arrays.asList(sources), null, null, null);
	}

	/**
	 * Create a new {@link Binder} instance for the specified sources. A
	 * {@link DefaultFormattingConversionService} will be used for all conversion.
	 * @param sources the sources used for binding
	 */
	public Binder(Iterable<ConfigurationPropertySource> sources) {
		this(sources, null, null, null);
	}

	/**
	 * Create a new {@link Binder} instance for the specified sources.
	 * @param sources the sources used for binding
	 * @param placeholdersResolver strategy to resolve any property placeholders
	 */
	public Binder(Iterable<ConfigurationPropertySource> sources, PlaceholdersResolver placeholdersResolver) {
		this(sources, placeholdersResolver, null, null);
	}

	/**
	 * Create a new {@link Binder} instance for the specified sources.
	 * @param sources the sources used for binding
	 * @param placeholdersResolver strategy to resolve any property placeholders
	 * @param conversionService the conversion service to convert values (or {@code null}
	 * to use {@link ApplicationConversionService})
	 */
	public Binder(Iterable<ConfigurationPropertySource> sources, PlaceholdersResolver placeholdersResolver,
			ConversionService conversionService) {
		this(sources, placeholdersResolver, conversionService, null);
	}

	/**
	 * Create a new {@link Binder} instance for the specified sources.
	 * @param sources the sources used for binding
	 * @param placeholdersResolver strategy to resolve any property placeholders
	 * @param conversionService the conversion service to convert values (or {@code null}
	 * to use {@link ApplicationConversionService})
	 * @param propertyEditorInitializer initializer used to configure the property editors
	 * that can convert values (or {@code null} if no initialization is required). Often
	 * used to call {@link ConfigurableListableBeanFactory#copyRegisteredEditorsTo}.
	 */
	public Binder(Iterable<ConfigurationPropertySource> sources, PlaceholdersResolver placeholdersResolver,
			ConversionService conversionService, Consumer<PropertyEditorRegistry> propertyEditorInitializer) {
		this(sources, placeholdersResolver, conversionService, propertyEditorInitializer, null);
	}

	/**
	 * Create a new {@link Binder} instance for the specified sources.
	 * @param sources the sources used for binding
	 * @param placeholdersResolver strategy to resolve any property placeholders
	 * @param conversionService the conversion service to convert values (or {@code null}
	 * to use {@link ApplicationConversionService})
	 * @param propertyEditorInitializer initializer used to configure the property editors
	 * that can convert values (or {@code null} if no initialization is required). Often
	 * used to call {@link ConfigurableListableBeanFactory#copyRegisteredEditorsTo}.
	 * @param defaultBindHandler the default bind handler to use if none is specified when
	 * binding
	 * @since 2.2.0
	 */
	public Binder(Iterable<ConfigurationPropertySource> sources, PlaceholdersResolver placeholdersResolver,
			ConversionService conversionService, Consumer<PropertyEditorRegistry> propertyEditorInitializer,
			BindHandler defaultBindHandler) {
		this(sources, placeholdersResolver, conversionService, propertyEditorInitializer, defaultBindHandler, null);
	}

	/**
	 * Create a new {@link Binder} instance for the specified sources.
	 * @param sources the sources used for binding
	 * @param placeholdersResolver strategy to resolve any property placeholders
	 * @param conversionService the conversion service to convert values (or {@code null}
	 * to use {@link ApplicationConversionService})
	 * @param propertyEditorInitializer initializer used to configure the property editors
	 * that can convert values (or {@code null} if no initialization is required). Often
	 * used to call {@link ConfigurableListableBeanFactory#copyRegisteredEditorsTo}.
	 * @param defaultBindHandler the default bind handler to use if none is specified when
	 * binding
	 * @param constructorProvider the constructor provider which provides the bind
	 * constructor to use when binding
	 * @since 2.2.1
	 */
	public Binder(Iterable<ConfigurationPropertySource> sources, PlaceholdersResolver placeholdersResolver,
			ConversionService conversionService, Consumer<PropertyEditorRegistry> propertyEditorInitializer,
			BindHandler defaultBindHandler, BindConstructorProvider constructorProvider) {
		Assert.notNull(sources, "Sources must not be null");
		this.sources = sources;
		// 如果placeholdersResolver为null，取默认的NONE对象 value -> value
		this.placeholdersResolver = (placeholdersResolver != null) ? placeholdersResolver : PlaceholdersResolver.NONE;
		// 如果conversionService为null，取ApplicationConversionService的单例
		this.conversionService = (conversionService != null) ? conversionService
				: ApplicationConversionService.getSharedInstance();
		this.propertyEditorInitializer = propertyEditorInitializer;
		// 如果defaultBindHandler为null，取默认的DEFAULT对象
		this.defaultBindHandler = (defaultBindHandler != null) ? defaultBindHandler : BindHandler.DEFAULT;
		// 如果constructorProvider为null，取默认的DEFAULT对象
		if (constructorProvider == null) {
			constructorProvider = BindConstructorProvider.DEFAULT;
		}
		// 初始化一个ValueObjectBinder和JavaBeanBinder，这两个类都属于DataObjectBinder的子类
		ValueObjectBinder valueObjectBinder = new ValueObjectBinder(constructorProvider);
		JavaBeanBinder javaBeanBinder = JavaBeanBinder.INSTANCE;
		// 将上述两个binder作为一个集合赋值给dataObjectBinders字段
		this.dataObjectBinders = Collections.unmodifiableList(Arrays.asList(valueObjectBinder, javaBeanBinder));
	}

	/**
	 * Bind the specified target {@link Class} using this binder's
	 * {@link ConfigurationPropertySource property sources}.
	 * @param name the configuration property name to bind
	 * @param target the target class
	 * @param <T> the bound type
	 * @return the binding result (never {@code null})
	 * @see #bind(ConfigurationPropertyName, Bindable, BindHandler)
	 */
	public <T> BindResult<T> bind(String name, Class<T> target) {
		return bind(name, Bindable.of(target));
	}

	/**
	 * Bind the specified target {@link Bindable} using this binder's
	 * {@link ConfigurationPropertySource property sources}.
	 * @param name the configuration property name to bind
	 * @param target the target bindable
	 * @param <T> the bound type
	 * @return the binding result (never {@code null})
	 * @see #bind(ConfigurationPropertyName, Bindable, BindHandler)
	 */
	public <T> BindResult<T> bind(String name, Bindable<T> target) {
		return bind(ConfigurationPropertyName.of(name), target, null);
	}

	/**
	 * Bind the specified target {@link Bindable} using this binder's
	 * {@link ConfigurationPropertySource property sources}.
	 * @param name the configuration property name to bind
	 * @param target the target bindable
	 * @param <T> the bound type
	 * @return the binding result (never {@code null})
	 * @see #bind(ConfigurationPropertyName, Bindable, BindHandler)
	 */
	public <T> BindResult<T> bind(ConfigurationPropertyName name, Bindable<T> target) {
		return bind(name, target, null);
	}

	/**
	 * Bind the specified target {@link Bindable} using this binder's
	 * {@link ConfigurationPropertySource property sources}.
	 * @param name the configuration property name to bind
	 * @param target the target bindable
	 * @param handler the bind handler (may be {@code null})
	 * @param <T> the bound type
	 * @return the binding result (never {@code null})
	 */
	public <T> BindResult<T> bind(String name, Bindable<T> target, BindHandler handler) {
		return bind(ConfigurationPropertyName.of(name), target, handler);
	}

	/**
	 * Bind the specified target {@link Bindable} using this binder's
	 * {@link ConfigurationPropertySource property sources}.
	 * @param name the configuration property name to bind
	 * @param target the target bindable
	 * @param handler the bind handler (may be {@code null})
	 * @param <T> the bound type
	 * @return the binding result (never {@code null})
	 */
	public <T> BindResult<T> bind(ConfigurationPropertyName name, Bindable<T> target, BindHandler handler) {
		T bound = bind(name, target, handler, false);
		return BindResult.of(bound);
	}

	/**
	 * Bind the specified target {@link Class} using this binder's
	 * {@link ConfigurationPropertySource property sources} or create a new instance using
	 * the type of the {@link Bindable} if the result of the binding is {@code null}.
	 * @param name the configuration property name to bind
	 * @param target the target class
	 * @param <T> the bound type
	 * @return the bound or created object
	 * @since 2.2.0
	 * @see #bind(ConfigurationPropertyName, Bindable, BindHandler)
	 */
	public <T> T bindOrCreate(String name, Class<T> target) {
		return bindOrCreate(name, Bindable.of(target));
	}

	/**
	 * Bind the specified target {@link Bindable} using this binder's
	 * {@link ConfigurationPropertySource property sources} or create a new instance using
	 * the type of the {@link Bindable} if the result of the binding is {@code null}.
	 * @param name the configuration property name to bind
	 * @param target the target bindable
	 * @param <T> the bound type
	 * @return the bound or created object
	 * @since 2.2.0
	 * @see #bindOrCreate(ConfigurationPropertyName, Bindable, BindHandler)
	 */
	public <T> T bindOrCreate(String name, Bindable<T> target) {
		return bindOrCreate(ConfigurationPropertyName.of(name), target, null);
	}

	/**
	 * Bind the specified target {@link Bindable} using this binder's
	 * {@link ConfigurationPropertySource property sources} or create a new instance using
	 * the type of the {@link Bindable} if the result of the binding is {@code null}.
	 * @param name the configuration property name to bind
	 * @param target the target bindable
	 * @param handler the bind handler
	 * @param <T> the bound type
	 * @return the bound or created object
	 * @since 2.2.0
	 * @see #bindOrCreate(ConfigurationPropertyName, Bindable, BindHandler)
	 */
	public <T> T bindOrCreate(String name, Bindable<T> target, BindHandler handler) {
		return bindOrCreate(ConfigurationPropertyName.of(name), target, handler);
	}

	/**
	 * Bind the specified target {@link Bindable} using this binder's
	 * {@link ConfigurationPropertySource property sources} or create a new instance using
	 * the type of the {@link Bindable} if the result of the binding is {@code null}.
	 * @param name the configuration property name to bind
	 * @param target the target bindable
	 * @param handler the bind handler (may be {@code null})
	 * @param <T> the bound or created type
	 * @return the bound or created object
	 * @since 2.2.0
	 */
	public <T> T bindOrCreate(ConfigurationPropertyName name, Bindable<T> target, BindHandler handler) {
		return bind(name, target, handler, true);
	}

	private <T> T bind(ConfigurationPropertyName name, Bindable<T> target, BindHandler handler, boolean create) {
		// 判断name 和 target 不能为null
		Assert.notNull(name, "Name must not be null");
		Assert.notNull(target, "Target must not be null");
		// 如果handler为null，取默认的handler，
		// 如果没有给binder的defaultBindHandler赋值，那么默认是BindHandler的一个匿名类
		handler = (handler != null) ? handler : this.defaultBindHandler;
		// 创建上下文对象，其中会根据binder的conversionService和propertyEditorInitializer创建一个BindConvert对象
		Context context = new Context();
		// 调用带context参数的bind方法
		return bind(name, target, handler, context, false, create);
	}

	private <T> T bind(ConfigurationPropertyName name, Bindable<T> target, BindHandler handler, Context context,
			boolean allowRecursiveBinding, boolean create) {
		try {
			// 调用bindHandler的onStart钩子函数
			Bindable<T> replacementTarget = handler.onStart(name, target, context);
			if (replacementTarget == null) {
				// 如果返回的为null，则调用结果为null的处理绑定结果的方法
				return handleBindResult(name, target, handler, context, null, create);
			}
			// 将target替换为onStart方法返回的target
			target = replacementTarget;
			// 调用bindObject方法，该方法执行具体的绑定逻辑
			Object bound = bindObject(name, target, handler, context, allowRecursiveBinding);
			// 拿到绑定结果，调用处理绑定结果的方法
			return handleBindResult(name, target, handler, context, bound, create);
		}
		catch (Exception ex) {
			// 如果出现异常，调用处理绑定错误的方法
			return handleBindError(name, target, handler, context, ex);
		}
	}

	private <T> T handleBindResult(ConfigurationPropertyName name, Bindable<T> target, BindHandler handler,
			Context context, Object result, boolean create) throws Exception {
		if (result != null) {
			// 调用bindHandler的onSuccess钩子函数
			result = handler.onSuccess(name, target, context, result);
			// 将结果转换为target中的type类型
			result = context.getConverter().convert(result, target);
		}
		// 如果结果为null，并且create标志是为true的
		if (result == null && create) {
			// 那么调用create方法进行创建
			result = create(target, context);
			// 调用bindHandler的onCreate钩子函数
			result = handler.onCreate(name, target, context, result);
			// 转换类型
			result = context.getConverter().convert(result, target);
			Assert.state(result != null, () -> "Unable to create instance for " + target.getType());
		}
		// 调用bindHandler的onFinish钩子函数
		handler.onFinish(name, target, context, result);
		return context.getConverter().convert(result, target);
	}

	private Object create(Bindable<?> target, Context context) {
		// 遍历binder持有的dataObjectBinder，并调用其create方法进行创建
		for (DataObjectBinder dataObjectBinder : this.dataObjectBinders) {
			Object instance = dataObjectBinder.create(target, context);
			if (instance != null) {
				return instance;
			}
		}
		return null;
	}

	private <T> T handleBindError(ConfigurationPropertyName name, Bindable<T> target, BindHandler handler,
			Context context, Exception error) {
		try {
			Object result = handler.onFailure(name, target, context, error);
			return context.getConverter().convert(result, target);
		}
		catch (Exception ex) {
			if (ex instanceof BindException) {
				throw (BindException) ex;
			}
			throw new BindException(name, target, context.getConfigurationProperty(), ex);
		}
	}

	private <T> Object bindObject(ConfigurationPropertyName name, Bindable<T> target, BindHandler handler,
			Context context, boolean allowRecursiveBinding) {
		// 根据ConfigurationPropertyName查找ConfigurationProperty
		ConfigurationProperty property = findProperty(name, context);
		// 如果查找到的属性为null，并且深度不为0，说明已经是在bindDataObject方法中，是针对对象属性的绑定了，
		// 并且所持有的ConfigurationPropertySources已经没有name对应的后代了，那么直接返回null，表示该属性为null。
		if (property == null && context.depth != 0 && containsNoDescendantOf(context.getSources(), name)) {
			return null;
		}
		// 根据target和context获取聚合binder，当target的type是属于List、Map、数组类型的时候，aggregate不为null
		AggregateBinder<?> aggregateBinder = getAggregateBinder(target, context);
		if (aggregateBinder != null) {
			// 进行聚合绑定
			return bindAggregate(name, target, handler, context, aggregateBinder);
		}
		// 如果property不为null，调用bindProperty方法
		if (property != null) {
			try {
				// 调用bindProperty方法，进行解析占位符，转换类型等操作
				return bindProperty(target, context, property);
			}
			catch (ConverterNotFoundException ex) {
				// We might still be able to bind it using the recursive binders
				Object instance = bindDataObject(name, target, handler, context, allowRecursiveBinding);
				if (instance != null) {
					return instance;
				}
				throw ex;
			}
		}
		return bindDataObject(name, target, handler, context, allowRecursiveBinding);
	}

	private AggregateBinder<?> getAggregateBinder(Bindable<?> target, Context context) {
		Class<?> resolvedType = target.getType().resolve(Object.class);
		if (Map.class.isAssignableFrom(resolvedType)) {
			return new MapBinder(context);
		}
		if (Collection.class.isAssignableFrom(resolvedType)) {
			return new CollectionBinder(context);
		}
		if (target.getType().isArray()) {
			return new ArrayBinder(context);
		}
		return null;
	}

	private <T> Object bindAggregate(ConfigurationPropertyName name, Bindable<T> target, BindHandler handler,
			Context context, AggregateBinder<?> aggregateBinder) {
		// 声明一个函数式接口
		AggregateElementBinder elementBinder = (itemName, itemTarget, source) -> {
			boolean allowRecursiveBinding = aggregateBinder.isAllowRecursiveBinding(source);
			Supplier<?> supplier = () -> bind(itemName, itemTarget, handler, context, allowRecursiveBinding, false);
			return context.withSource(source, supplier);
		};
		// 将context的depth+1，调用参数中的supplier函数式接口，完成之后将context的depth-1
		return context.withIncreasedDepth(() -> aggregateBinder.bind(name, target, elementBinder));
	}

	private ConfigurationProperty findProperty(ConfigurationPropertyName name, Context context) {
		// 如果ConfigurationPropertyName中的Elements的size为0，返回null
		if (name.isEmpty()) {
			return null;
		}
		// 调用上下文的getSources方法，获取上下文的ConfigurationPropertySource集合或者Binder的source集合
		for (ConfigurationPropertySource source : context.getSources()) {
			// 对每个source调用getConfigurationProperty，
			// 当property不为null时，返回，否则返回null
			ConfigurationProperty property = source.getConfigurationProperty(name);
			if (property != null) {
				return property;
			}
		}
		return null;
	}

	private <T> Object bindProperty(Bindable<T> target, Context context, ConfigurationProperty property) {
		// 将property存入上下文中
		context.setConfigurationProperty(property);
		// 拿到property中的value
		Object result = property.getValue();
		// 解析占位符
		result = this.placeholdersResolver.resolvePlaceholders(result);
		// 调用上下文中的bindConvert进行转换类型
		result = context.getConverter().convert(result, target);
		return result;
	}

	private Object bindDataObject(ConfigurationPropertyName name, Bindable<?> target, BindHandler handler,
			Context context, boolean allowRecursiveBinding) {
		// 判断target是否是不能被绑定的bean，判断依据是 不能是原始类型以及Object.class Class.class以及以java.开头的类
		if (isUnbindableBean(name, target, context)) {
			return null;
		}
		Class<?> type = target.getType().resolve(Object.class);
		// 如果不允许递归绑定 并且 type已经处在正绑定的状态了，返回null。
		if (!allowRecursiveBinding && context.isBindingDataObject(type)) {
			return null;
		}
		// 创建一个DataObjectPropertyBinder，用于将属性绑定进DataObject的属性中。
		// 实现是在name的后面添加上propertyName，再次调用binder的bind方法，由于此时name已经改变。
		// 如果在ConfigurationPropertySources中找到对应的属性，那么就会走到bindProperty方法中去；
		// 又或者仍然找不到对应的属性，那么会继续走到bindDataObject方法中，即是内嵌的对象。
		DataObjectPropertyBinder propertyBinder = (propertyName, propertyTarget) -> bind(name.append(propertyName),
				propertyTarget, handler, context, false, false);
		return context.withDataObject(type,
				// 传入的Supplier才是具体的绑定逻辑
				() -> {
			// 遍历binder所持有的dataObjectBinder(默认持有JavaBeanBinder和ValueObjectBinder)，并调用其bind方法，如果任一返回不为null，直接返回，否则返回null。
			for (DataObjectBinder dataObjectBinder : this.dataObjectBinders) {
				// 其中propertyBinder是前面创建的 用于绑定属性
				Object instance = dataObjectBinder.bind(name, target, context, propertyBinder);
				if (instance != null) {
					return instance;
				}
			}
			return null;
		});
	}

	private boolean isUnbindableBean(ConfigurationPropertyName name, Bindable<?> target, Context context) {
		// 遍历循环context中的ConfigurationPropertySource
		// 判断source中是否有name对应的后代，如果是存在状态的话，说明source中有属性可以被绑定，那么返回false，表示可以进行绑定。
		// 比如spring.profiles.active是spring.profiles的后代
		for (ConfigurationPropertySource source : context.getSources()) {
			if (source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT) {
				// We know there are properties to bind so we can't bypass anything
				return false;
			}
		}
		Class<?> resolved = target.getType().resolve(Object.class);
		// 如果类型是原始类型或者是Object.class Class.class，返回true，表示该类不能被绑定
		if (resolved.isPrimitive() || NON_BEAN_CLASSES.contains(resolved)) {
			return true;
		}
		// 如果类是以java.开头的，也不能被绑定
		return resolved.getName().startsWith("java.");
	}

	private boolean containsNoDescendantOf(Iterable<ConfigurationPropertySource> sources,
			ConfigurationPropertyName name) {
		for (ConfigurationPropertySource source : sources) {
			if (source.containsDescendantOf(name) != ConfigurationPropertyState.ABSENT) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Create a new {@link Binder} instance from the specified environment.
	 * @param environment the environment source (must have attached
	 * {@link ConfigurationPropertySources})
	 * @return a {@link Binder} instance
	 */
	public static Binder get(Environment environment) {
		return get(environment, null);
	}

	/**
	 * Create a new {@link Binder} instance from the specified environment.
	 * @param environment the environment source (must have attached
	 * {@link ConfigurationPropertySources})
	 * @param defaultBindHandler the default bind handler to use if none is specified when
	 * binding
	 * @return a {@link Binder} instance
	 * @since 2.2.0
	 */
	public static Binder get(Environment environment, BindHandler defaultBindHandler) {
		// 返回environment的sources的Iterable<ConfigurationPropertySource>的适配器
		Iterable<ConfigurationPropertySource> sources = ConfigurationPropertySources.get(environment);
		// 初始化一个PropertySourcesPlaceholdersResolver
		PropertySourcesPlaceholdersResolver placeholdersResolver = new PropertySourcesPlaceholdersResolver(environment);
		// 根据sources，placeholdersResolver等初始化一个Binder对象
		return new Binder(sources, placeholdersResolver, null, null, defaultBindHandler);
	}

	/**
	 * Context used when binding and the {@link BindContext} implementation.
	 */
	final class Context implements BindContext {

		private final BindConverter converter;

		private int depth;

		private final List<ConfigurationPropertySource> source = Arrays.asList((ConfigurationPropertySource) null);

		private int sourcePushCount;

		private final Deque<Class<?>> dataObjectBindings = new ArrayDeque<>();

		private final Deque<Class<?>> constructorBindings = new ArrayDeque<>();

		private ConfigurationProperty configurationProperty;

		Context() {
			// 根据binder中的conversionService和propertyEditorInitializer创建BindConvert
			this.converter = BindConverter.get(Binder.this.conversionService, Binder.this.propertyEditorInitializer);
		}

		private void increaseDepth() {
			this.depth++;
		}

		private void decreaseDepth() {
			this.depth--;
		}

		private <T> T withSource(ConfigurationPropertySource source, Supplier<T> supplier) {
			if (source == null) {
				return supplier.get();
			}
			this.source.set(0, source);
			this.sourcePushCount++;
			try {
				return supplier.get();
			}
			finally {
				this.sourcePushCount--;
			}
		}

		private <T> T withDataObject(Class<?> type, Supplier<T> supplier) {
			// 将type入栈，表示该type正在进行绑定
			this.dataObjectBindings.push(type);
			try {
				return withIncreasedDepth(supplier);
			}
			finally {
				// 将type出栈，表示type已经绑定完成
				this.dataObjectBindings.pop();
			}
		}

		private boolean isBindingDataObject(Class<?> type) {
			return this.dataObjectBindings.contains(type);
		}

		private <T> T withIncreasedDepth(Supplier<T> supplier) {
			// 增加绑定深度
			increaseDepth();
			try {
				// 调用supplier的get方法执行具体的绑定逻辑
				return supplier.get();
			}
			finally {
				// 减少绑定深度
				decreaseDepth();
			}
		}

		void setConfigurationProperty(ConfigurationProperty configurationProperty) {
			this.configurationProperty = configurationProperty;
		}

		void clearConfigurationProperty() {
			this.configurationProperty = null;
		}

		void pushConstructorBoundTypes(Class<?> value) {
			this.constructorBindings.push(value);
		}

		boolean isNestedConstructorBinding() {
			return !this.constructorBindings.isEmpty();
		}

		void popConstructorBoundTypes() {
			this.constructorBindings.pop();
		}

		PlaceholdersResolver getPlaceholdersResolver() {
			return Binder.this.placeholdersResolver;
		}

		BindConverter getConverter() {
			return this.converter;
		}

		@Override
		public Binder getBinder() {
			return Binder.this;
		}

		@Override
		public int getDepth() {
			return this.depth;
		}

		@Override
		public Iterable<ConfigurationPropertySource> getSources() {
			// 如果sourcePushCount大于0，代表上下文中有自己放入的source，则使用上下文中的source
			if (this.sourcePushCount > 0) {
				return this.source;
			}
			// 否则使用binder的sources
			return Binder.this.sources;
		}

		@Override
		public ConfigurationProperty getConfigurationProperty() {
			return this.configurationProperty;
		}

	}

}
