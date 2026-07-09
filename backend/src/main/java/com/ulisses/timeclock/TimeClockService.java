package com.ulisses.timeclock;

import com.ulisses.auth.User;
import com.ulisses.face.FaceDtos.VerifyResult;
import com.ulisses.face.FaceService;
import com.ulisses.timeclock.TimeClockDtos.EntryDto;
import com.ulisses.timeclock.TimeClockDtos.PunchResult;
import com.ulisses.timeclock.TimeClockDtos.TodayResponse;
import com.ulisses.timeclock.TimeClockEntry.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Demonstracao pratica: ponto eletronico anti-"buddy punching". O registro so acontece se a
 * validacao facial COMPLETA passar — reaproveita integralmente {@link FaceService#verify}, ou seja,
 * a mesma prova de vida ativa (movimentos) e o anti-spoofing. Assim, ninguem bate o ponto por outro.
 */
@Service
public class TimeClockService {

	private final FaceService faceService;
	private final TimeClockRepository repository;
	private final ZoneId zone = ZoneId.systemDefault();

	public TimeClockService(FaceService faceService, TimeClockRepository repository) {
		this.faceService = faceService;
		this.repository = repository;
	}

	@Transactional
	public PunchResult punch(
			User user, String challengeId, List<MultipartFile> frames, List<String> phases) {
		VerifyResult verification = faceService.verify(user, challengeId, frames, phases);
		if (!verification.success()) {
			// Validacao facial nao passou: nao registra e devolve o motivo para a UI.
			return new PunchResult(false, null, null, verification, todayEntries(user));
		}
		Type type = nextType(user);
		TimeClockEntry entry = new TimeClockEntry();
		entry.setUserId(user.getId());
		entry.setType(type);
		entry.setSimilarity(verification.similarity());
		repository.save(entry);
		return new PunchResult(
				true, type.name(), entry.getCreatedAt(), verification, todayEntries(user));
	}

	public TodayResponse today(User user) {
		return new TodayResponse(nextType(user).name(), todayEntries(user));
	}

	/** Alterna entrada/saida a partir do ultimo registro (primeiro do dia e sempre ENTRADA). */
	private Type nextType(User user) {
		return repository
				.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
				.map(last -> last.getType() == Type.ENTRADA ? Type.SAIDA : Type.ENTRADA)
				.orElse(Type.ENTRADA);
	}

	private List<EntryDto> todayEntries(User user) {
		Instant startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant();
		return repository
				.findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(user.getId(), startOfDay)
				.stream()
				.map(EntryDto::from)
				.toList();
	}
}
