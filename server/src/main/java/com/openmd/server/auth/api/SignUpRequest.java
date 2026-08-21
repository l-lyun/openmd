package com.openmd.server.auth.api;

import com.openmd.server.auth.application.SignUpCommand;
import com.openmd.server.auth.application.TermsAgreement;
import com.openmd.server.auth.domain.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SignUpRequest(
	@NotBlank
	@Pattern(
		regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
		message = "유효한 가입 계속 자격이 필요합니다."
	)
	@Schema(example = "61d67fa8-1a2b-4f35-94fc-16ec63551b15") String signUpToken,
	@NotBlank @Pattern(regexp = PasswordPolicy.REGEX, message = "8~64자이며 영문자와 숫자를 포함하고 공백이 없어야 합니다.")
	@Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, example = "<password>") String password,
	@NotBlank @Size(max = 32) @Schema(example = "공부왕7") String nickname,
	@NotEmpty @Size(max = 4) List<@Valid AgreementRequest> agreements
) {
	public SignUpCommand toCommand() {
		return new SignUpCommand(
			signUpToken,
			password,
			nickname,
			agreements.stream().map(agreement -> new TermsAgreement(
				agreement.termsId(), agreement.version()
			)).toList()
		);
	}
}
