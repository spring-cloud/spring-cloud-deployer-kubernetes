package org.springframework.cloud.deployer.scheduler.spi.kubernetes;

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
		EntryPointStyle entryPointStyle = EntryPointStyle.relaxedValueOf("unknown");
		assertEquals(EntryPointStyle.exec, entryPointStyle);
	}

	@Test
	public void testMatchEntryPointStyle() {
		EntryPointStyle entryPointStyle = EntryPointStyle.relaxedValueOf("shell");
		assertEquals(EntryPointStyle.shell, entryPointStyle);
	}

	@Test
	public void testMixedCaseEntryPointStyle() {
		EntryPointStyle entryPointStyle = EntryPointStyle.relaxedValueOf("bOOt");
		assertEquals(EntryPointStyle.boot, entryPointStyle);
	}
}
