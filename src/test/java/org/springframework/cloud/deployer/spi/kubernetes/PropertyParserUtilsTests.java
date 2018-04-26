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

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

	@Test(expected = IllegalArgumentException.class)
	public void testAnnotationParseInvalidValue() {
		PropertyParserUtils.getAnnotations("annotation1:value1,annotation2,annotation3:value3");
	}
}
