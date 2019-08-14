/*
 * Copyright 2019 the original author or authors.
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
package org.springframework.cloud.deployer.spi.kubernetes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link EntryPointStyle}
 *
 * @author Chris Schaefer
 */
public class EntryPointStyleTests {
	@Test
	public void testInvalidEntryPointStyleDefaulting() {
		EntryPointStyle entryPointStyle = EntryPointStyle
				.relaxedValueOf("unknown");
		assertEquals(EntryPointStyle.exec, entryPointStyle);
	}

	@Test
	public void testMatchEntryPointStyle() {
		EntryPointStyle entryPointStyle = EntryPointStyle
				.relaxedValueOf("shell");
		assertEquals(EntryPointStyle.shell, entryPointStyle);
	}

	@Test
	public void testMixedCaseEntryPointStyle() {
		EntryPointStyle entryPointStyle = EntryPointStyle
				.relaxedValueOf("bOOt");
		assertEquals(EntryPointStyle.boot, entryPointStyle);
	}
}
