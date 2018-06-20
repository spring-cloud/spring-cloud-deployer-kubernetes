/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.kubernetes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * Tests for PropertyParserUtils
 *
 * @author Chris Schaefer
 */
public class PropertyParserUtilsTests {
	@Test
	public void testAnnotationParseSingle() {
		Map<String, String> annotations = PropertyParserUtils.getAnnotations("annotation:value");
		assertFalse(annotations.isEmpty());
		assertTrue(annotations.size() == 1);
		assertTrue(annotations.containsKey("annotation"));
		assertTrue(annotations.get("annotation").equals("value"));
	}

	@Test
	public void testAnnotationParseMultiple() {
		Map<String, String> annotations = PropertyParserUtils.getAnnotations("annotation1:value1,annotation2:value2");
		assertFalse(annotations.isEmpty());
		assertTrue(annotations.size() == 2);
		assertTrue(annotations.containsKey("annotation1"));
		assertTrue(annotations.get("annotation1").equals("value1"));
		assertTrue(annotations.containsKey("annotation2"));
		assertTrue(annotations.get("annotation2").equals("value2"));
	}

	@Test
	public void testAnnotationMultipleColon() {
		String annotation = "iam.amazonaws.com/role:arn:aws:iam::12345678:role/role-name,key1:val1:val2:val3," +
				"key2:val4::val5:val6::val7:val8";
		Map<String, String> annotations = PropertyParserUtils.getAnnotations(annotation);
		assertFalse(annotations.isEmpty());
		assertTrue(annotations.size() == 3);
		assertTrue(annotations.containsKey("iam.amazonaws.com/role"));
		assertTrue(annotations.get("iam.amazonaws.com/role").equals("arn:aws:iam::12345678:role/role-name"));
		assertTrue(annotations.containsKey("key1"));
		assertTrue(annotations.get("key1").equals("val1:val2:val3"));
		assertTrue(annotations.containsKey("key2"));
		assertTrue(annotations.get("key2").equals("val4::val5:val6::val7:val8"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testAnnotationParseInvalidValue() {
		PropertyParserUtils.getAnnotations("annotation1:value1,annotation2,annotation3:value3");
	}
}
