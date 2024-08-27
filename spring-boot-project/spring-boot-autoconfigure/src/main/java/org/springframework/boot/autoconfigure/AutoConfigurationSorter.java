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

package org.springframework.boot.autoconfigure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.Assert;

/**
 * Sort {@link EnableAutoConfiguration auto-configuration} classes into priority order by
 * reading {@link AutoConfigureOrder @AutoConfigureOrder},
 * {@link AutoConfigureBefore @AutoConfigureBefore} and
 * {@link AutoConfigureAfter @AutoConfigureAfter} annotations (without loading classes).
 *
 * @author Phillip Webb
 */
class AutoConfigurationSorter {

	private final MetadataReaderFactory metadataReaderFactory;

	private final AutoConfigurationMetadata autoConfigurationMetadata;

	AutoConfigurationSorter(MetadataReaderFactory metadataReaderFactory,
			AutoConfigurationMetadata autoConfigurationMetadata) {
		Assert.notNull(metadataReaderFactory, "MetadataReaderFactory must not be null");
		this.metadataReaderFactory = metadataReaderFactory;
		this.autoConfigurationMetadata = autoConfigurationMetadata;
	}

	List<String> getInPriorityOrder(Collection<String> classNames) {
		// 根据传入的候选类名集合创建一个AutoConfigurationClasses对象
		AutoConfigurationClasses classes = new AutoConfigurationClasses(this.metadataReaderFactory,
				this.autoConfigurationMetadata, classNames);
		// 创建一个list用于保存排序后的类名
		List<String> orderedClassNames = new ArrayList<>(classNames);
		// Initially sort alphabetically
		// 先按照类名的字母顺序进行排序
		Collections.sort(orderedClassNames);
		// Then sort by order
		// 然后再按照AutoConfigurationClass的getOrder方法的结果进行排序
		// getOrder方法的逻辑是：
		// 1.如果AutoConfigurationMetadata存在且里面有className对应的属性，那么获取className.AutoConfigurationOrder属性的值作为order，默认为0；
		// 2.否则，判断类上是否标注了@AutoConfigurationOrder注解，如果有，获取value属性，否则返回默认的0
		orderedClassNames.sort((o1, o2) -> {
			int i1 = classes.get(o1).getOrder();
			int i2 = classes.get(o2).getOrder();
			return Integer.compare(i1, i2);
		});
		// Then respect @AutoConfigureBefore @AutoConfigureAfter
		// 然后再按照 @AutoConfigureBefore @AutoConfigureAfter注解声明的自动配置类之间的先后顺序进行排序
		orderedClassNames = sortByAnnotation(classes, orderedClassNames);
		// 返回排序后的类名集合
		return orderedClassNames;
	}

	private List<String> sortByAnnotation(AutoConfigurationClasses classes, List<String> classNames) {
		// 根据传入的classNames生成一个新的list，表示需要去排序的集合
		List<String> toSort = new ArrayList<>(classNames);
		// 获取classes中存在的所有AutoConfigurationClass的className，添加到需要去排序的集合。
		// 这一步可能会导致toSort集合里有重复的className
		toSort.addAll(classes.getAllNames());
		Set<String> sorted = new LinkedHashSet<>();
		Set<String> processing = new LinkedHashSet<>();
		// 创建两个set用于辅助排序，当toSort不为空时，循环进行排序
		while (!toSort.isEmpty()) {
			doSortByAfterAnnotation(classes, toSort, sorted, processing, null);
		}
		// 只保留排序后的set里面等同于classNames的元素
		sorted.retainAll(classNames);
		// 将排序后的set转换为list返回
		return new ArrayList<>(sorted);
	}

	private void doSortByAfterAnnotation(AutoConfigurationClasses classes, List<String> toSort, Set<String> sorted,
			Set<String> processing, String current) {
		// 如果当前元素为null的话，获取要排序的list的第一个元素作为当前元素
		if (current == null) {
			current = toSort.remove(0);
		}
		// 将当前元素添加进processingSet，表示正在对这个元素进行处理
		processing.add(current);
		// 获取到当前元素需要在哪些类装配之后才能装配的集合，进行遍历
		// 满足条件的：
		// 1.通过current对应的AutoConfigurationClass的AutoConfigurationAfter获取
		// 2.以及其他AutoConfigurationClass的AutoConfigurationBefore包含了current的那些class
		for (String after : classes.getClassesRequestedAfter(current)) {
			// 如果processingSet里面包含了本次循环的after，说明存在自动装配先后顺序的环，报错
			Assert.state(!processing.contains(after),
					"AutoConfigure cycle detected between " + current + " and " + after);
			// 如果sortedSet不包含after 并且 toSort集合包含after，
			// 表示after需要排序但还没有排序
			if (!sorted.contains(after) && toSort.contains(after)) {
				// 递归解析after，找到after需要在哪些类装配结束之后再进行装配
				doSortByAfterAnnotation(classes, toSort, sorted, processing, after);
			}
		}
		// 将当前元素从processingSet中删除，表示不在处理中了
		processing.remove(current);
		// 然后将当前元素添加进sortedSet，表示已经排序
		sorted.add(current);
	}

	private static class AutoConfigurationClasses {

		private final Map<String, AutoConfigurationClass> classes = new HashMap<>();

		AutoConfigurationClasses(MetadataReaderFactory metadataReaderFactory,
				AutoConfigurationMetadata autoConfigurationMetadata, Collection<String> classNames) {
			addToClasses(metadataReaderFactory, autoConfigurationMetadata, classNames, true);
		}

		Set<String> getAllNames() {
			return this.classes.keySet();
		}

		private void addToClasses(MetadataReaderFactory metadataReaderFactory,
				AutoConfigurationMetadata autoConfigurationMetadata, Collection<String> classNames, boolean required) {
			// 遍历类名集合
			for (String className : classNames) {
				// 如果AutoConfigurationClass类型的map还不存在对应的类
				if (!this.classes.containsKey(className)) {
					// 将类名传入，封装一个AutoConfigurationClass类型的对象
					AutoConfigurationClass autoConfigurationClass = new AutoConfigurationClass(className,
							metadataReaderFactory, autoConfigurationMetadata);
					// 判断类是否是可获得的
					boolean available = autoConfigurationClass.isAvailable();
					// 如果required为true 或者 available为true，将创建出的AutoConfigurationClass保存进classes这个map中，
					// key就为className
					if (required || available) {
						this.classes.put(className, autoConfigurationClass);
					}
					// 如果类是available的
					if (available) {
						// 将其before和after的自动配置类也封装成AutoConfigurationClass添加到classes这个map中
						// 但不是必须的，如果不存在，也就不会添加
						addToClasses(metadataReaderFactory, autoConfigurationMetadata,
								autoConfigurationClass.getBefore(), false);
						addToClasses(metadataReaderFactory, autoConfigurationMetadata,
								autoConfigurationClass.getAfter(), false);
					}
				}
			}
		}

		AutoConfigurationClass get(String className) {
			// 根据类名获取AutoConfigurationClass对象
			return this.classes.get(className);
		}

		Set<String> getClassesRequestedAfter(String className) {
			// 获取className对应的AutoConfigurationClass对象，然后获取它的AutoConfigurationAfter的类名集合。
			// 表示当前className需要在哪些类自动装配之后才能装配
			Set<String> classesRequestedAfter = new LinkedHashSet<>(get(className).getAfter());
			// 然后遍历所有的AutoConfigurationClass，如果某个class的AutoConfigurationBefore包含了当前className，
			// 说明该class需要在className装配之前进行自动装配，换言之，className需要在该class装配之后进行装配，
			// 所以该class也应该添加到className的after集合里
			this.classes.forEach((name, autoConfigurationClass) -> {
				if (autoConfigurationClass.getBefore().contains(className)) {
					classesRequestedAfter.add(name);
				}
			});
			// 返回after集合
			return classesRequestedAfter;
		}

	}

	private static class AutoConfigurationClass {

		private final String className;

		private final MetadataReaderFactory metadataReaderFactory;

		private final AutoConfigurationMetadata autoConfigurationMetadata;

		private volatile AnnotationMetadata annotationMetadata;

		private volatile Set<String> before;

		private volatile Set<String> after;

		AutoConfigurationClass(String className, MetadataReaderFactory metadataReaderFactory,
				AutoConfigurationMetadata autoConfigurationMetadata) {
			this.className = className;
			this.metadataReaderFactory = metadataReaderFactory;
			this.autoConfigurationMetadata = autoConfigurationMetadata;
		}

		boolean isAvailable() {
			try {
				// 调用wasProcessed方法判断该类是否已经处理过
				if (!wasProcessed()) {
					// 如果没有，调用getAnnotationMetadata进行处理
					getAnnotationMetadata();
				}
				// 没有异常抛出，返回true，表示是可获取的
				return true;
			}
			catch (Exception ex) {
				// 出现异常返回false，表示不可获取
				return false;
			}
		}

		Set<String> getBefore() {
			if (this.before == null) {
				// 如果已经处理过，从metadata里面根据className和AutoConfigurationBefore去获取，默认为空set；
				// 如果没有处理过，获取标注的@AutoConfigurationBefore注解的value属性，如果没有标注，返回空set；
				this.before = (wasProcessed() ? this.autoConfigurationMetadata.getSet(this.className,
						"AutoConfigureBefore", Collections.emptySet()) : getAnnotationValue(AutoConfigureBefore.class));
			}
			return this.before;
		}

		Set<String> getAfter() {
			if (this.after == null) {
				// 如果已经处理过，从metadata里面根据className和AutoConfigurationAfter去获取，默认为空set；
				// 如果没有处理过，获取标注的@AutoConfigurationAfter注解的value属性，如果没有标注，返回空set；
				this.after = (wasProcessed() ? this.autoConfigurationMetadata.getSet(this.className,
						"AutoConfigureAfter", Collections.emptySet()) : getAnnotationValue(AutoConfigureAfter.class));
			}
			return this.after;
		}

		private int getOrder() {
			// 如果类已经被处理过，从metadata里面直接获取类名对应的AutoConfigurationOrder属性的值，默认为0
			if (wasProcessed()) {
				return this.autoConfigurationMetadata.getInteger(this.className, "AutoConfigureOrder",
						AutoConfigureOrder.DEFAULT_ORDER);
			}
			// 如果没有被处理过，那么从AnnotationMetadata里面的标注的@AutoConfigurationOrder注解去获取
			Map<String, Object> attributes = getAnnotationMetadata()
					.getAnnotationAttributes(AutoConfigureOrder.class.getName());
			// 如果标注了@AutoConfigurationOrder注解，即属性map不为null，获取其value属性，否则的话，默认返回0
			return (attributes != null) ? (Integer) attributes.get("value") : AutoConfigureOrder.DEFAULT_ORDER;
		}

		private boolean wasProcessed() {
			// 如果autoConfigurationMetadata不为null并且autoConfigurationMetadata中className是已经被处理过的，返回true；
			// 否则返回false
			return (this.autoConfigurationMetadata != null
					&& this.autoConfigurationMetadata.wasProcessed(this.className));
		}

		private Set<String> getAnnotationValue(Class<?> annotation) {
			Map<String, Object> attributes = getAnnotationMetadata().getAnnotationAttributes(annotation.getName(),
					true);
			if (attributes == null) {
				return Collections.emptySet();
			}
			Set<String> value = new LinkedHashSet<>();
			Collections.addAll(value, (String[]) attributes.get("value"));
			Collections.addAll(value, (String[]) attributes.get("name"));
			return value;
		}

		private AnnotationMetadata getAnnotationMetadata() {
			if (this.annotationMetadata == null) {
				try {
					// 通过asm读取对应的类文件，拿到类的annotationMetadata，赋值给annotationMetadata属性
					MetadataReader metadataReader = this.metadataReaderFactory.getMetadataReader(this.className);
					this.annotationMetadata = metadataReader.getAnnotationMetadata();
				}
				catch (IOException ex) {
					throw new IllegalStateException("Unable to read meta-data for class " + this.className, ex);
				}
			}
			return this.annotationMetadata;
		}

	}

}
