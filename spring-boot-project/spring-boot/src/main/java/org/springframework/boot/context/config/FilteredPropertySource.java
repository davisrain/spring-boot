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

package org.springframework.boot.context.config;

import java.util.Set;
import java.util.function.Consumer;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Internal {@link PropertySource} implementation used by
 * {@link ConfigFileApplicationListener} to filter out properties for specific operations.
 *
 * @author Phillip Webb
 */
class FilteredPropertySource extends PropertySource<PropertySource<?>> {

	private final Set<String> filteredProperties;

	FilteredPropertySource(PropertySource<?> original, Set<String> filteredProperties) {
		super(original.getName(), original);
		this.filteredProperties = filteredProperties;
	}

	@Override
	public Object getProperty(String name) {
		if (this.filteredProperties.contains(name)) {
			return null;
		}
		return getSource().getProperty(name);
	}

	static void apply(ConfigurableEnvironment environment, String propertySourceName, Set<String> filteredProperties,
			Consumer<PropertySource<?>> operation) {
		// 拿到environment中的propertySources
		MutablePropertySources propertySources = environment.getPropertySources();
		// 判断propertySources中是否有name为该参数的propertySource
		PropertySource<?> original = propertySources.get(propertySourceName);
		if (original == null) {
			// 如果没有，调用consumer的accept方法
			operation.accept(null);
			return;
		}
		// 如果存在，初始化一个FilteredPropertySource来将原propertySource进行替换，
		// FilterPropertySource相当于原propertySource的一个装饰器，多添加了set类型的filteredProperties属性
		propertySources.replace(propertySourceName, new FilteredPropertySource(original, filteredProperties));
		try {
			// 然后调用consumer的accept方法，参数为原始的propertySource
			operation.accept(original);
		}
		finally {
			// 最后使用原来的propertySource将propertySources中的内容替换回来
			propertySources.replace(propertySourceName, original);
		}
	}

}
