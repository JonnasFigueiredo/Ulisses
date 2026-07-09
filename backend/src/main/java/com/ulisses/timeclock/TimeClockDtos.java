package com.ulisses.timeclock;

import com.ulisses.face.FaceDtos.VerifyResult;
import java.time.Instant;
import java.util.List;

public final class TimeClockDtos {

	private TimeClockDtos() {}

	public record EntryDto(String type, Instant at, double similarity) {
		public static EntryDto from(TimeClockEntry entry) {
			return new EntryDto(entry.getType().name(), entry.getCreatedAt(), entry.getSimilarity());
		}
	}

	/**
	 * Resultado de uma tentativa de bater ponto. Quando a validacao facial passa, {@code registered}
	 * e true e {@code type}/{@code at} descrevem o registro; caso contrario, {@code verification}
	 * traz o detalhe do que falhou (nao reconhecido / prova de vida).
	 */
	public record PunchResult(
			boolean registered,
			String type,
			Instant at,
			VerifyResult verification,
			List<EntryDto> today) {}

	/** Situacao atual do ponto do usuario: proximo tipo esperado e registros de hoje. */
	public record TodayResponse(String nextType, List<EntryDto> today) {}
}
