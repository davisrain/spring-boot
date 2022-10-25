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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySource.StubPropertySource;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ConcurrentReferenceHashMap.ReferenceType;

/**
 * Adapter to convert Spring's {@link MutablePropertySources} to
 * {@link ConfigurationPropertySource ConfigurationPropertySources}.
 *
 * @author Phillip Webb
 */
class SpringConfigurationPropertySources implements Iterable<ConfigurationPropertySource> {

	private final Iterable<PropertySource<?>> sources;

	private final Map<PropertySource<?>, ConfigurationPropertySource> cache = new ConcurrentReferenceHashMap<>(16,
			ReferenceType.SOFT);

	SpringConfigurationPropertySources(Iterable<PropertySource<?>> sources) {
		Assert.notNull(sources, "Sources must not be null");
		this.sources = sources;
	}

	@Override
	public Iterator<ConfigurationPropertySource> iterator() {
		return new SourcesIterator(this.sources.iterator(), this::adapt);
	}

	private ConfigurationPropertySource adapt(PropertySource<?> source) {
		// 从缓存中尝试获取
		ConfigurationPropertySource result = this.cache.get(source);
		// Most PropertySources test equality only using the source name, so we need to
		// check the actual source hasn't also changed.
		// 如果result不为null，且它的底层propertySource就等于被适配前的propertySource，说明缓存命中，直接返回
		if (result != null && result.getUnderlyingSource() == source) {
			return result;
		}
		// 否则的话，将PropertySource类型适配为ConfigurationPropertySource
		result = SpringConfigurationPropertySource.from(source);
		// 将结果存入缓存
		this.cache.put(source, result);
		return result;
	}

	private static class SourcesIterator implements Iterator<ConfigurationPropertySource> {

		private final Deque<Iterator<PropertySource<?>>> iterators;

		private ConfigurationPropertySource next;

		private final Function<PropertySource<?>, ConfigurationPropertySource> adapter;

		SourcesIterator(Iterator<PropertySource<?>> iterator,
				Function<PropertySource<?>, ConfigurationPropertySource> adapter) {
			this.iterators = new ArrayDeque<>(4);
			this.iterators.push(iterator);
			this.adapter = adapter;
		}

		@Override
		public boolean hasNext() {
			return fetchNext() != null;
		}

		@Override
		public ConfigurationPropertySource next() {
			// 调用fetchNext方法，获取this.next的值
			ConfigurationPropertySource next = fetchNext();
			// 如果next为null，报错
			if (next == null) {
				throw new NoSuchElementException();
			}
			// 将this.next置为null，为了下次调用fetchNext方法
			this.next = null;
			// 返回next
			return next;
		}

		private ConfigurationPropertySource fetchNext() {
			// 当this.next字段为null时，执行以下逻辑去获取
			if (this.next == null) {
				// 判断iterators是否为空，如果是，直接返回null
				if (this.iterators.isEmpty()) {
					return null;
				}
				// 判断iterators里第一个iterator是否还有元素，如果没有的话，将其pop出栈，
				// 然后递归调用fetchNext，从第二个iterator中搜索
				if (!this.iterators.peek().hasNext()) {
					this.iterators.pop();
					return fetchNext();
				}
				// 获取iterators中第一个iterator的下一个PropertySource
				PropertySource<?> candidate = this.iterators.peek().next();
				// 如果该propertySource的source是属于ConfigurationEnvironment类型的
				if (candidate.getSource() instanceof ConfigurableEnvironment) {
					// 调用push方法，将该environment的propertySources添加到iterators中的第一个
					push((ConfigurableEnvironment) candidate.getSource());
					// 然后递归调用fetchNext，从新的iterator中进行搜索
					return fetchNext();
				}
				// 如果candidate是属于StubPropertySource类型或者ConfigurationPropertySourcesPropertySource类型，
				// 忽略，递归调用fetchNext方法
				if (isIgnored(candidate)) {
					return fetchNext();
				}
				// 如果该candidate满足条件，调用adapter的apply方法，将PropertySource适配为ConfigurationPropertySource，
				// 并且赋值给this.next
				this.next = this.adapter.apply(candidate);
			}
			// 返回this.next
			return this.next;
		}

		private void push(ConfigurableEnvironment environment) {
			this.iterators.push(environment.getPropertySources().iterator());
		}

		private boolean isIgnored(PropertySource<?> candidate) {
			return (candidate instanceof StubPropertySource
					|| candidate instanceof ConfigurationPropertySourcesPropertySource);
		}

	}

}
