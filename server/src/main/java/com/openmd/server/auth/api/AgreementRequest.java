package com.openmd.server.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgreementRequest(
	@NotBlank @Size(max = 64) @Schema(example = "SERVICE_TERMS") String termsId,
	@NotBlank @Size(max = 64) @Schema(example = "TEMP-2026-08-20") String version
) {
}
