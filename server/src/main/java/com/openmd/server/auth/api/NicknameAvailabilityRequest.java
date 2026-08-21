package com.openmd.server.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NicknameAvailabilityRequest(
	@NotBlank @Size(max = 32) @Schema(example = "공부왕7") String nickname
) {
}
