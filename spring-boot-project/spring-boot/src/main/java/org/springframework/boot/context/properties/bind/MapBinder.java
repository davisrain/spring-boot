/*
 * Copyright 2012-2019 the original author or authors.
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

import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import org.springframework.boot.context.properties.bind.Binder.Context;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.CollectionFactory;
import org.springframework.core.ResolvableType;

/**
 * {@link AggregateBinder} for Maps.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 */
class MapBinder extends AggregateBinder<Map<Object, Object>> {

	private static final Bindable<Map<String, String>> STRING_STRING_MAP = Bindable.mapOf(String.class, String.class);

	MapBinder(Context context) {
		super(context);
	}

	@Override
	protected boolean isAllowRecursiveBinding(ConfigurationPropertySource source) {
		return true;
	}

	@Override
	protected Object bindAggregate(ConfigurationPropertyName name, Bindable<?> target,
			AggregateElementBinder elementBinder) {
		// 使用CollectionFactory创建出一个map
		Map<Object, Object> map = CollectionFactory
				.createMap((target.getValue() != null) ? Map.class : target.getType().resolve(Object.class), 0);
		// 解析bindable，如果发现bindable的resolvableType的resolved字段是Properties的话，
		// 那么返回STRING_STRING_MAP，否则返回原对象
		Bindable<?> resolvedTarget = resolveTarget(target);
		// 判断sources中是否有name的子属性
		boolean hasDescendants = hasDescendants(name);
		// 遍历ConfigurationPropertySource集合
		for (ConfigurationPropertySource source : getContext().getSources()) {
			// 如果name不等于Empty的ConfigurationPropertyName的话
			if (!ConfigurationPropertyName.EMPTY.equals(name)) {
				// 从source中根据name获取ConfigurationProperty
				ConfigurationProperty property = source.getConfigurationProperty(name);
				// 如果property不为null，并且没有子属性的话
				if (property != null && !hasDescendants) {
					// 将property的value转换为target返回
					return getContext().getConverter().convert(property.getValue(), target);
				}
				// 否则的话，将source转换为FilteredConfigurationPropertiesSource类型，
				// 筛选条件： name是ConfigurationPropertyName的祖先属性
				source = source.filter(name::isAncestorOf);
			}
			// 根据参数创建一个EntryBinder来进行绑定
			new EntryBinder(name, resolvedTarget, elementBinder).bindEntries(source, map);
		}
		return map.isEmpty() ? null : map;
	}

	private boolean hasDescendants(ConfigurationPropertyName name) {
		for (ConfigurationPropertySource source : getContext().getSources()) {
			if (source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT) {
				return true;
			}
		}
		return false;
	}

	private Bindable<?> resolveTarget(Bindable<?> target) {
		Class<?> type = target.getType().resolve(Object.class);
		if (Properties.class.isAssignableFrom(type)) {
			return STRING_STRING_MAP;
		}
		return target;
	}

	@Override
	protected Map<Object, Object> merge(Supplier<Map<Object, Object>> existing, Map<Object, Object> additional) {
		Map<Object, Object> existingMap = getExistingIfPossible(existing);
		if (existingMap == null) {
			return additional;
		}
		try {
			existingMap.putAll(additional);
			return copyIfPossible(existingMap);
		}
		catch (UnsupportedOperationException ex) {
			Map<Object, Object> result = createNewMap(additional.getClass(), existingMap);
			result.putAll(additional);
			return result;
		}
	}

	private Map<Object, Object> getExistingIfPossible(Supplier<Map<Object, Object>> existing) {
		try {
			return existing.get();
		}
		catch (Exception ex) {
			return null;
		}
	}

	private Map<Object, Object> copyIfPossible(Map<Object, Object> map) {
		try {
			return createNewMap(map.getClass(), map);
		}
		catch (Exception ex) {
			return map;
		}
	}

	private Map<Object, Object> createNewMap(Class<?> mapClass, Map<Object, Object> map) {
		Map<Object, Object> result = CollectionFactory.createMap(mapClass, map.size());
		result.putAll(map);
		return result;
	}

	private class EntryBinder {

		private final ConfigurationPropertyName root;

		private final AggregateElementBinder elementBinder;

		private final ResolvableType mapType;

		private final ResolvableType keyType;

		private final ResolvableType valueType;

		EntryBinder(ConfigurationPropertyName root, Bindable<?> target, AggregateElementBinder elementBinder) {
			this.root = root;
			this.elementBinder = elementBinder;
			this.mapType = target.getType().asMap();
			this.keyType = this.mapType.getGeneric(0);
			this.valueType = this.mapType.getGeneric(1);
		}

		void bindEntries(ConfigurationPropertySource source, Map<Object, Object> map) {
			if (source instanceof IterableConfigurationPropertySource) {
				// 循环遍历所有是root子属性的属性name
				for (ConfigurationPropertyName name : (IterableConfigurationPropertySource) source) {
					// 获取到value的bindable
					Bindable<?> valueBindable = getValueBindable(name);
					// 获取到entry的name
					ConfigurationPropertyName entryName = getEntryName(source, name);
					// 获取keyName并且转换为keyType类型
					Object key = getContext().getConverter().convert(getKeyName(entryName), this.keyType);
					// 然后如果map中不存在key的话，调用elementBinder的bind方法进行绑定并放入map中
					map.computeIfAbsent(key, (k) -> this.elementBinder.bind(entryName, valueBindable));
				}
			}
		}

		private Bindable<?> getValueBindable(ConfigurationPropertyName name) {
			// 如果name的size不止比root多1 并且 valueType的resolved字段是Object.class的话，
			// 说明value可能是嵌套的map，因此返回mapType的bindable。
			if (!this.root.isParentOf(name) && isValueTreatedAsNestedMap()) {
				return Bindable.of(this.mapType);
			}
			// 否则返回valueType的bindable
			return Bindable.of(this.valueType);
		}

		private ConfigurationPropertyName getEntryName(ConfigurationPropertySource source,
				ConfigurationPropertyName name) {
			// 获取valueType的resolved字段
			Class<?> resolved = this.valueType.resolve(Object.class);
			// 如果value是Collection类型的或者是数组类型的，调用chopNameAtNumericIndex方法对name进行处理并返回
			if (Collection.class.isAssignableFrom(resolved) || this.valueType.isArray()) {
				return chopNameAtNumericIndex(name);
			}
			// 如果root不是name的父属性，即name比root多不止一个element 并且 (valueType对应的是Object.class或者name对应的属性值不是标量值)
			if (!this.root.isParentOf(name) && (isValueTreatedAsNestedMap() || !isScalarValue(source, name))) {
				// 将name截取到root的size+1的位置
				return name.chop(this.root.getNumberOfElements() + 1);
			}
			return name;
		}

		private ConfigurationPropertyName chopNameAtNumericIndex(ConfigurationPropertyName name) {
			int start = this.root.getNumberOfElements() + 1;
			int size = name.getNumberOfElements();
			// 从root的size+1开始，一直遍历到name的size
			for (int i = start; i < size; i++) {
				// 如果发现name在下标i的位置的element是NumericIndex类型的，
				if (name.isNumericIndex(i)) {
					// 直接将name截取到该位置返回
					return name.chop(i);
				}
			}
			return name;
		}

		private boolean isValueTreatedAsNestedMap() {
			return Object.class.equals(this.valueType.resolve(Object.class));
		}

		private boolean isScalarValue(ConfigurationPropertySource source, ConfigurationPropertyName name) {
			Class<?> resolved = this.valueType.resolve(Object.class);
			// 如果valueType对应的类不是以java.lang开头的(比如String和所有基础类型的包装类型) 并且 不是枚举，返回false，说明不是标量值
			if (!resolved.getName().startsWith("java.lang") && !resolved.isEnum()) {
				return false;
			}
			// 如果source中不存在name对应的属性，返回false
			ConfigurationProperty property = source.getConfigurationProperty(name);
			if (property == null) {
				return false;
			}
			// 否则获取到属性的值
			Object value = property.getValue();
			value = getContext().getPlaceholdersResolver().resolvePlaceholders(value);
			// 并且判断转换器是否能够转换
			return getContext().getConverter().canConvert(value, this.valueType);
		}

		private String getKeyName(ConfigurationPropertyName name) {
			StringBuilder result = new StringBuilder();
			// 从root的size的位置，遍历到name的size-1的位置
			for (int i = this.root.getNumberOfElements(); i < name.getNumberOfElements(); i++) {
				// 如果result中已经有值了，每次将元素添加进result前需要添加一个.
				if (result.length() != 0) {
					result.append('.');
				}
				// 将element以original的形式添加进元素，会保留大小写字母，数字，下划线以及破折号
				result.append(name.getElement(i, Form.ORIGINAL));
			}
			return result.toString();
		}

	}

}
