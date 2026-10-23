package com.ulisses.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<Map<String, String>> handleApi(ApiException ex) {
		return ResponseEntity.status(ex.getStatus()).body(Map.of("error", ex.getMessage()));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(
			MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(f -> f.getField() + ": " + f.getDefaultMessage())
				.findFirst()
				.orElse("Dados invalidos");
		return new ResponseEntity<>(Map.of("error", message), headers, status);
	}

	@Override
	protected ResponseEntity<Object> createResponseEntity(
			Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		String message = switch (status.value()) {
			case 400 -> "Requisicao invalida";
			case 404 -> "Recurso nao encontrado";
			case 405 -> "Metodo HTTP nao permitido";
			case 406 -> "Formato de resposta nao suportado";
			case 413 -> "Envio excede o tamanho maximo permitido";
			case 415 -> "Formato de envio nao suportado";
			case 503 -> "Servico temporariamente indisponivel";
			default -> status.is5xxServerError() ? "Erro interno do servidor" : "Nao foi possivel processar a requisicao";
		};
		return new ResponseEntity<>(Map.of("error", message), headers, status);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
		log.error("Erro inesperado", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("error", "Erro interno do servidor"));
	}
}
