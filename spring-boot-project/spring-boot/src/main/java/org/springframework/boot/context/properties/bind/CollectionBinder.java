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
import java.util.List;
import java.util.function.Supplier;

import org.springframework.boot.context.properties.bind.Binder.Context;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.core.CollectionFactory;
import org.springframework.core.ResolvableType;

/**
 * {@link AggregateBinder} for collections.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 */
class CollectionBinder extends IndexedElementsBinder<Collection<Object>> {

	CollectionBinder(Context context) {
		super(context);
	}

	@Override
	protected Object bindAggregate(ConfigurationPropertyName name, Bindable<?> target,
			AggregateElementBinder elementBinder) {
		Class<?> collectionType = (target.getValue() != null) ? List.class : target.getType().resolve(Object.class);
		// 然后根据获取到的generics(ResolvableType数组，其中type为TypeVariable，resolved字段为TypeVariable实际对应的类型)
		// 和List.class生成一个聚合的ResolvableType，其中type为SyntheticParameterizedType，variableResolver是根据variables
		// 和generics构建的TypeVariablesVariableResolver
		ResolvableType aggregateType = ResolvableType.forClassWithGenerics(List.class,
				// 获取到bindable的resolvableType的generics
				target.getType().asCollection().getGenerics());
		// 获取到集合内元素的实际ResolvableType，因为List类型只会带一个泛型，
		// 因此获取到的ResolvableType的resolved字段就是集合对应的元素类型
		ResolvableType elementType = target.getType().asCollection().getGeneric();
		// 初始化一个IndexedCollectionSupplier，它的get方法的逻辑是调用持有的supplier的get方法，
		// 一旦调用了该supplier的get方法，它就会将获取的对象持有到supplied字段中
		IndexedCollectionSupplier result = new IndexedCollectionSupplier(
				// 传入的supplier逻辑是：根据集合类型 和 元素类型，调用CollectionFactory的方法创建一个集合
				() -> CollectionFactory.createCollection(collectionType, elementType.resolve(), 0));
		bindIndexed(name, target, elementBinder, aggregateType, elementType, result);
		// 判断IndexedCollectionSupplier的supplied字段是否不为null，如果是，返回supplied字段
		if (result.wasSupplied()) {
			return result.get();
		}
		// 否则返回null
		return null;
	}

	@Override
	protected Collection<Object> merge(Supplier<Collection<Object>> existing, Collection<Object> additional) {
		Collection<Object> existingCollection = getExistingIfPossible(existing);
		if (existingCollection == null) {
			return additional;
		}
		try {
			existingCollection.clear();
			existingCollection.addAll(additional);
			return copyIfPossible(existingCollection);
		}
		catch (UnsupportedOperationException ex) {
			return createNewCollection(additional);
		}
	}

	private Collection<Object> getExistingIfPossible(Supplier<Collection<Object>> existing) {
		try {
			return existing.get();
		}
		catch (Exception ex) {
			return null;
		}
	}

	private Collection<Object> copyIfPossible(Collection<Object> collection) {
		try {
			return createNewCollection(collection);
		}
		catch (Exception ex) {
			return collection;
		}
	}

	private Collection<Object> createNewCollection(Collection<Object> collection) {
		Collection<Object> result = CollectionFactory.createCollection(collection.getClass(), collection.size());
		result.addAll(collection);
		return result;
	}

}
