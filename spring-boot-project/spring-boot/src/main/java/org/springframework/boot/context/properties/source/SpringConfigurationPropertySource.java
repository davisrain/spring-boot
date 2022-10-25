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

package org.springframework.boot.context.properties.source;

import java.util.Map;
import java.util.Random;

import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.PropertySourceOrigin;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.util.Assert;

/**
 * {@link ConfigurationPropertySource} backed by a non-enumerable Spring
 * {@link PropertySource} or a restricted {@link EnumerablePropertySource} implementation
 * (such as a security restricted {@code systemEnvironment} source). A
 * {@link PropertySource} is adapted with the help of a {@link PropertyMapper} which
 * provides the mapping rules for individual properties.
 * <p>
 * Each {@link ConfigurationPropertySource#getConfigurationProperty
 * getConfigurationProperty} call attempts to
 * {@link PropertyMapper#map(ConfigurationPropertyName) map} the
 * {@link ConfigurationPropertyName} to one or more {@code String} based names. This
 * allows fast property resolution for well formed property sources.
 * <p>
 * When possible the {@link SpringIterableConfigurationPropertySource} will be used in
 * preference to this implementation since it supports full "relaxed" style resolution.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 * @see #from(PropertySource)
 * @see PropertyMapper
 * @see SpringIterableConfigurationPropertySource
 */
class SpringConfigurationPropertySource implements ConfigurationPropertySource {

	private static final PropertyMapper[] DEFAULT_MAPPERS = { DefaultPropertyMapper.INSTANCE };

	private static final PropertyMapper[] SYSTEM_ENVIRONMENT_MAPPERS = { SystemEnvironmentPropertyMapper.INSTANCE,
			DefaultPropertyMapper.INSTANCE };

	private final PropertySource<?> propertySource;

	private final PropertyMapper[] mappers;

	/**
	 * Create a new {@link SpringConfigurationPropertySource} implementation.
	 * @param propertySource the source property source
	 * @param mappers the property mappers
	 */
	SpringConfigurationPropertySource(PropertySource<?> propertySource, PropertyMapper... mappers) {
		Assert.notNull(propertySource, "PropertySource must not be null");
		Assert.isTrue(mappers.length > 0, "Mappers must contain at least one item");
		this.propertySource = propertySource;
		this.mappers = mappers;
	}

	@Override
	public ConfigurationProperty getConfigurationProperty(ConfigurationPropertyName name) {
		if (name == null) {
			return null;
		}
		for (PropertyMapper mapper : this.mappers) {
			try {
				for (String candidate : mapper.map(name)) {
					Object value = getPropertySource().getProperty(candidate);
					if (value != null) {
						Origin origin = PropertySourceOrigin.get(getPropertySource(), candidate);
						return ConfigurationProperty.of(name, value, origin);
					}
				}
			}
			catch (Exception ex) {
			}
		}
		return null;
	}

	@Override
	public ConfigurationPropertyState containsDescendantOf(ConfigurationPropertyName name) {
		PropertySource<?> source = getPropertySource();
		if (source.getSource() instanceof Random) {
			return containsDescendantOfForRandom("random", name);
		}
		if (source.getSource() instanceof PropertySource<?>
				&& ((PropertySource<?>) source.getSource()).getSource() instanceof Random) {
			// Assume wrapped random sources use the source name as the prefix
			return containsDescendantOfForRandom(source.getName(), name);
		}
		return ConfigurationPropertyState.UNKNOWN;
	}

	private static ConfigurationPropertyState containsDescendantOfForRandom(String prefix,
			ConfigurationPropertyName name) {
		if (name.getNumberOfElements() > 1 && name.getElement(0, Form.DASHED).equals(prefix)) {
			return ConfigurationPropertyState.PRESENT;
		}
		return ConfigurationPropertyState.ABSENT;
	}

	@Override
	public Object getUnderlyingSource() {
		return this.propertySource;
	}

	protected PropertySource<?> getPropertySource() {
		return this.propertySource;
	}

	protected final PropertyMapper[] getMappers() {
		return this.mappers;
	}

	@Override
	public String toString() {
		return this.propertySource.toString();
	}

	/**
	 * Create a new {@link SpringConfigurationPropertySource} for the specified
	 * {@link PropertySource}.
	 * @param source the source Spring {@link PropertySource}
	 * @return a {@link SpringConfigurationPropertySource} or
	 * {@link SpringIterableConfigurationPropertySource} instance
	 */
	static SpringConfigurationPropertySource from(PropertySource<?> source) {
		Assert.notNull(source, "Source must not be null");
		// 获取propertyMapper
		PropertyMapper[] mappers = getPropertyMappers(source);
		// 判断source是否是完全Enumerable类型的
		if (isFullEnumerable(source)) {
			// 如果是，创建一个SpringIterableConfigurationPropertySource类型
			return new SpringIterableConfigurationPropertySource((EnumerablePropertySource<?>) source, mappers);
		}
		// 否则，创建一个SpringConfigurationPropertySource返回
		return new SpringConfigurationPropertySource(source, mappers);
	}

	private static PropertyMapper[] getPropertyMappers(PropertySource<?> source) {
		// 如果source是属于SystemEnvironmentPropertySource或者source的name是systemEnvironment或者以该名字结尾的
		if (source instanceof SystemEnvironmentPropertySource && hasSystemEnvironmentName(source)) {
			// 返回SystemEnvironmentPropertyMapper和DefaultPropertyMapper
			return SYSTEM_ENVIRONMENT_MAPPERS;
		}
		// 否则返回DefaultPropertyMapper
		return DEFAULT_MAPPERS;
	}

	private static boolean hasSystemEnvironmentName(PropertySource<?> source) {
		String name = source.getName();
		return StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(name)
				|| name.endsWith("-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
	}

	private static boolean isFullEnumerable(PropertySource<?> source) {
		// 获取到该PropertySource的真正的rootSource
		PropertySource<?> rootSource = getRootSource(source);
		// 判断rootSource是否是Map类型的
		if (rootSource.getSource() instanceof Map) {
			// Check we're not security restricted
			try {
				// 检查是否没有安全限制
				((Map<?, ?>) rootSource.getSource()).size();
			}
			// 如果抛出异常，返回false
			catch (UnsupportedOperationException ex) {
				return false;
			}
		}
		// 返回source是否是EnumerablePropertySource类型的
		return (source instanceof EnumerablePropertySource);
	}

	private static PropertySource<?> getRootSource(PropertySource<?> source) {
		while (source.getSource() != null && source.getSource() instanceof PropertySource) {
			source = (PropertySource<?>) source.getSource();
		}
		return source;
	}

}
