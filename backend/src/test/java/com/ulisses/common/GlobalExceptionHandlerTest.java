package com.ulisses.common;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ulisses.auth.AuthController;
import com.ulisses.auth.AuthService;
import com.ulisses.face.ChallengeService;
import com.ulisses.face.FaceController;
import com.ulisses.face.FaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class GlobalExceptionHandlerTest {

	private AuthService authService;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		authService = mock(AuthService.class);
		mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService),
				new FaceController(mock(FaceService.class), mock(ChallengeService.class)))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void malformedJsonIsBadRequest() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Requisicao invalida"));
	}

	@Test
	void missingMultipartFrameIsBadRequest() throws Exception {
		mvc.perform(multipart("/api/face/verify").param("challengeId", "challenge").param("phases", "STRAIGHT"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Requisicao invalida"));
	}

	@Test
	void unsupportedMethodPreservesStatusAndAllowHeader() throws Exception {
		mvc.perform(get("/api/auth/login"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string(HttpHeaders.ALLOW, "POST"))
				.andExpect(jsonPath("$.error").value("Metodo HTTP nao permitido"));
	}

	@Test
	void unsupportedContentTypeIsNotServerError() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("user:password"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(header().exists(HttpHeaders.ACCEPT))
				.andExpect(jsonPath("$.error").value("Formato de envio nao suportado"));
	}

	@Test
	void beanValidationKeepsTheExistingErrorContract() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"User\",\"username\":\"x\",\"password\":\"password\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("username: minimo de 3 caracteres"));
	}

	@Test
	void uploadLimitKeepsPayloadTooLargeStatus() throws Exception {
		when(authService.login(any())).thenThrow(new MaxUploadSizeExceededException(100));
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"user\",\"password\":\"password\"}"))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.error").value("Envio excede o tamanho maximo permitido"));
	}

	@Test
	void unexpectedFailuresRemainGenericServerErrors() throws Exception {
		when(authService.login(any())).thenThrow(new IllegalStateException("database credentials"));
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"user\",\"password\":\"password\"}"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("Erro interno do servidor"));
	}
}
