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
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.bind.Binder.Context;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.ResolvableType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Base class for {@link AggregateBinder AggregateBinders} that read a sequential run of
 * indexed items.
 *
 * @param <T> the type being bound
 * @author Phillip Webb
 * @author Madhura Bhave
 */
abstract class IndexedElementsBinder<T> extends AggregateBinder<T> {

	private static final String INDEX_ZERO = "[0]";

	IndexedElementsBinder(Context context) {
		super(context);
	}

	@Override
	protected boolean isAllowRecursiveBinding(ConfigurationPropertySource source) {
		// 如果source为null或者source是IterableConfigurationSource类型的，那么允许递归绑定
		return source == null || source instanceof IterableConfigurationPropertySource;
	}

	/**
	 * Bind indexed elements to the supplied collection.
	 * @param name the name of the property to bind
	 * @param target the target bindable
	 * @param elementBinder the binder to use for elements
	 * @param aggregateType the aggregate type, may be a collection or an array
	 * @param elementType the element type
	 * @param result the destination for results
	 */
	protected final void bindIndexed(ConfigurationPropertyName name, Bindable<?> target,
			AggregateElementBinder elementBinder, ResolvableType aggregateType, ResolvableType elementType,
			IndexedCollectionSupplier result) {
		// 获取到context中的sources，并开始循环
		for (ConfigurationPropertySource source : getContext().getSources()) {
			// 调用重载的bindIndexed方法，多传一个ConfigurationPropertySource类型的参数
			bindIndexed(source, name, target, elementBinder, result, aggregateType, elementType);
			// 一旦supplier的supplied字段不为null了，就返回
			if (result.wasSupplied() && result.get() != null) {
				return;
			}
		}
	}

	private void bindIndexed(ConfigurationPropertySource source, ConfigurationPropertyName root, Bindable<?> target,
			AggregateElementBinder elementBinder, IndexedCollectionSupplier collection, ResolvableType aggregateType,
			ResolvableType elementType) {
		// 根据ConfigurationPropertyName获取到ConfigurationProperty
		ConfigurationProperty property = source.getConfigurationProperty(root);
		// 如果获取到的property不为null
		if (property != null) {
			// 将property设置进上下文中
			getContext().setConfigurationProperty(property);
			// 调用bindValue方法，将property中的value绑定进supplier的get返回对象中
			bindValue(target, collection.get(), aggregateType, elementType, property.getValue());
		}
		else {
			// 如果没有对应的property，尝试查找root的子属性，并且子属性需要为root+[i]这种格式的
			bindIndexed(source, root, elementBinder, collection, elementType);
		}
	}

	private void bindValue(Bindable<?> target, Collection<Object> collection, ResolvableType aggregateType,
			ResolvableType elementType, Object value) {
		// 如果value是String类型的，但是没有内容，直接返回，不绑定
		if (value instanceof String && !StringUtils.hasText((String) value)) {
			return;
		}
		// 将value转换为聚合对象
		Object aggregate = convert(value, aggregateType, target.getAnnotations());
		// 根据生成的collection对象的类型和elementType初始化一个集合对应的ResolvableType
		ResolvableType collectionType = ResolvableType.forClassWithGenerics(collection.getClass(), elementType);
		// 然后将聚合对象转换为这个集合对象
		Collection<Object> elements = convert(aggregate, collectionType);
		// 往集合中添加转换后的集合对象
		collection.addAll(elements);
	}

	private void bindIndexed(ConfigurationPropertySource source, ConfigurationPropertyName root,
			AggregateElementBinder elementBinder, IndexedCollectionSupplier collection, ResolvableType elementType) {
		// 查找source中是否存在root的最后一个element为indexed类型的子属性
		MultiValueMap<String, ConfigurationPropertyName> knownIndexedChildren = getKnownIndexedChildren(source, root);
		for (int i = 0; i < Integer.MAX_VALUE; i++) {
			// 依次往root后面添加[i]，然后调用elementBinder进行绑定
			ConfigurationPropertyName name = root.append((i != 0) ? "[" + i + "]" : INDEX_ZERO);
			Object value = elementBinder.bind(name, Bindable.of(elementType), source);
			// 如果value为null的话，跳出循环
			if (value == null) {
				break;
			}
			// 绑定完成后将MultiValueMap中对应的key删除
			knownIndexedChildren.remove(name.getLastElement(Form.UNIFORM));
			// 将绑定的结果添加到最后要返回的集合中
			collection.get().add(value);
		}
		// 最后检查是否有没有绑定到的子属性，即检查MultiValueMap是否为空的，有的话抛出异常
		assertNoUnboundChildren(source, knownIndexedChildren);
	}

	private MultiValueMap<String, ConfigurationPropertyName> getKnownIndexedChildren(ConfigurationPropertySource source,
			ConfigurationPropertyName root) {
		MultiValueMap<String, ConfigurationPropertyName> children = new LinkedMultiValueMap<>();
		// 如果source不是IterableConfigurationPropertySource类型的，直接返回
		if (!(source instanceof IterableConfigurationPropertySource)) {
			return children;
		}
		// 遍历并筛选出source中是root子属性的ConfigurationPropertyName
		for (ConfigurationPropertyName name : (IterableConfigurationPropertySource) source.filter(root::isAncestorOf)) {
			// 截取筛选出来的name，使得它的size只能比root大1
			ConfigurationPropertyName choppedName = name.chop(root.getNumberOfElements() + 1);
			// 判断截取出来的name的最后一个element是否是indexed类型的
			if (choppedName.isLastElementIndexed()) {
				// 如果是，以UNIFORM的格式获取最后一个element，只会保留小写字符和数字
				String key = choppedName.getLastElement(Form.UNIFORM);
				// 然后将key和name添加到MultiValueMap中
				children.add(key, name);
			}
		}
		// 返回MultiValueMap
		return children;
	}

	private void assertNoUnboundChildren(ConfigurationPropertySource source,
			MultiValueMap<String, ConfigurationPropertyName> children) {
		if (!children.isEmpty()) {
			throw new UnboundConfigurationPropertiesException(children.values().stream().flatMap(List::stream)
					.map(source::getConfigurationProperty).collect(Collectors.toCollection(TreeSet::new)));
		}
	}

	private <C> C convert(Object value, ResolvableType type, Annotation... annotations) {
		// 调用占位符解析器对value进行占位符解析
		value = getContext().getPlaceholdersResolver().resolvePlaceholders(value);
		// 然后调用上下文中的bindConvert对value进行类型转换，转换为type对应的类型
		return getContext().getConverter().convert(value, type, annotations);
	}

	/**
	 * {@link AggregateBinder.AggregateSupplier AggregateSupplier} for an indexed
	 * collection.
	 */
	protected static class IndexedCollectionSupplier extends AggregateSupplier<Collection<Object>> {

		public IndexedCollectionSupplier(Supplier<Collection<Object>> supplier) {
			super(supplier);
		}

	}

}
