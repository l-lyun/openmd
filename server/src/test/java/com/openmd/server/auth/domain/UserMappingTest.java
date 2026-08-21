package com.openmd.server.auth.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class UserMappingTest {

	@Test
	void normalizedEmailIsUniqueAndPasswordHashIsRequired() throws Exception {
		Table table = User.class.getAnnotation(Table.class);
		assertNotNull(table);
		assertEquals("users", table.name());
		assertFalse(Arrays.stream(table.uniqueConstraints())
			.map(UniqueConstraint::columnNames)
			.noneMatch(columns -> Arrays.equals(columns, new String[]{"normalized_email"})));
		assertFalse(Arrays.stream(table.uniqueConstraints())
			.map(UniqueConstraint::columnNames)
			.noneMatch(columns -> Arrays.equals(columns, new String[]{"nickname"})));

		Field passwordHash = User.class.getDeclaredField("passwordHash");
		assertFalse(passwordHash.getAnnotation(Column.class).nullable());
		assertEquals(255, passwordHash.getAnnotation(Column.class).length());
		Field nickname = User.class.getDeclaredField("nickname");
		assertEquals(10, nickname.getAnnotation(Column.class).length());
		assertThrows(NoSuchFieldException.class, () -> User.class.getDeclaredField("normalizedNickname"));
	}
}
