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

/**
 * Internal utility to help when dealing with data object property names.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 * @since 2.2.3
 * @see DataObjectBinder
 */
public abstract class DataObjectPropertyName {

	private DataObjectPropertyName() {
	}

	/**
	 * Return the specified Java Bean property name in dashed form.
	 * @param name the source name
	 * @return the dashed from
	 */
	public static String toDashedForm(String name) {
		StringBuilder result = new StringBuilder(name.length());
		boolean inIndex = false;
		for (int i = 0; i < name.length(); i++) {
			char ch = name.charAt(i);
			// 如果是在[]内的内容，直接将字符添加进result中，不进行额外处理
			if (inIndex) {
				result.append(ch);
				if (ch == ']') {
					inIndex = false;
				}
			}
			else {
				if (ch == '[') {
					inIndex = true;
					result.append(ch);
				}
				// 如果不是在[]中的内容，进行转换，保证了 驼峰命名法 和 下划线命名法 会被转换为 破折号写法
				else {
					// 将字符_替换为字符-
					ch = (ch != '_') ? ch : '-';
					// 如果字符为大写，并且不是首字符，并且result的最后一个字符不是-，那么向result中添加一个-
					if (Character.isUpperCase(ch) && result.length() > 0 && result.charAt(result.length() - 1) != '-') {
						result.append('-');
					}
					// 将字符转换为小写添加进result
					result.append(Character.toLowerCase(ch));
				}
			}
		}
		return result.toString();
	}

}
