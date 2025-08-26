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

package org.springframework.boot.context.properties;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.HierarchicalBeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean.BindMethod;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Delegate used by {@link EnableConfigurationPropertiesRegistrar} and
 * {@link ConfigurationPropertiesScanRegistrar} to register a bean definition for a
 * {@link ConfigurationProperties @ConfigurationProperties} class.
 *
 * @author Madhura Bhave
 * @author Phillip Webb
 */
final class ConfigurationPropertiesBeanRegistrar {

	private final BeanDefinitionRegistry registry;

	private final BeanFactory beanFactory;

	ConfigurationPropertiesBeanRegistrar(BeanDefinitionRegistry registry) {
		this.registry = registry;
		this.beanFactory = (BeanFactory) this.registry;
	}

	void register(Class<?> type) {
		MergedAnnotation<ConfigurationProperties> annotation = MergedAnnotations
				.from(type, SearchStrategy.TYPE_HIERARCHY).get(ConfigurationProperties.class);
		register(type, annotation);
	}

	void register(Class<?> type, MergedAnnotation<ConfigurationProperties> annotation) {
		// 获取要注册的类的beanName
		// 1.如果类上标注了@ConfigurationProperties注解，且注解设置了prefix属性，那么beanName为prefix-${className}
		// 2.否则就为className
		String name = getName(type, annotation);
		// 判断ioc中是否已经存在对应的beanDefinition
		if (!containsBeanDefinition(name)) {
			// 注册beanDefinition到ioc中
			registerBeanDefinition(name, type, annotation);
		}
	}

	private String getName(Class<?> type, MergedAnnotation<ConfigurationProperties> annotation) {
		String prefix = annotation.isPresent() ? annotation.getString("prefix") : "";
		return (StringUtils.hasText(prefix) ? prefix + "-" + type.getName() : type.getName());
	}

	private boolean containsBeanDefinition(String name) {
		return containsBeanDefinition(this.beanFactory, name);
	}

	private boolean containsBeanDefinition(BeanFactory beanFactory, String name) {
		if (beanFactory instanceof ListableBeanFactory
				&& ((ListableBeanFactory) beanFactory).containsBeanDefinition(name)) {
			return true;
		}
		if (beanFactory instanceof HierarchicalBeanFactory) {
			return containsBeanDefinition(((HierarchicalBeanFactory) beanFactory).getParentBeanFactory(), name);
		}
		return false;
	}

	private void registerBeanDefinition(String beanName, Class<?> type,
										MergedAnnotation<ConfigurationProperties> annotation) {
		Assert.state(annotation.isPresent(), () -> "No " + ConfigurationProperties.class.getSimpleName()
				+ " annotation found on  '" + type.getName() + "'.");
		this.registry.registerBeanDefinition(beanName, createBeanDefinition(beanName, type));
	}

	private BeanDefinition createBeanDefinition(String beanName, Class<?> type) {
		// 判断绑定类型
		// 1.如果存在标注了@ConstructorBinding注解的构造器，或者类上标注了@ConstructorBinding注解，且只有一个有参构造器，那么是VALUE_OBJECT类型的
		// 2.否则是JAVA_BEAN类型
		if (BindMethod.forType(type) == BindMethod.VALUE_OBJECT) {
			// 如果是VALUE_OBJECT类型，使用特殊的beanDefinition，走instanceSupplier的方式去实例化
			return new ConfigurationPropertiesValueObjectBeanDefinition(this.beanFactory, beanName, type);
		}
		// 如果是JAVA_BEAN类型，走常规的bean的实例化，通过bpp进行绑定操作
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClass(type);
		return definition;
	}

}
