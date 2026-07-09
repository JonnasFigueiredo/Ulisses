package com.ulisses.timeclock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ulisses.auth.User;
import com.ulisses.face.FaceDtos.VerifyResult;
import com.ulisses.face.FaceService;
import com.ulisses.timeclock.TimeClockDtos.PunchResult;
import com.ulisses.timeclock.TimeClockEntry.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O ponto reaproveita FaceService.verify (mockado aqui); testamos a logica de registro/alternancia. */
class TimeClockServiceTest {

	private FaceService faceService;
	private TimeClockService service;
	private User user;
	private final List<TimeClockEntry> store = new ArrayList<>();

	@BeforeEach
	void setUp() {
		faceService = mock(FaceService.class);
		TimeClockRepository repo = mock(TimeClockRepository.class);
		service = new TimeClockService(faceService, repo);
		user = mock(User.class);
		lenient().when(user.getId()).thenReturn(1L);
		lenient().when(user.getName()).thenReturn("Teste");

		when(repo.save(any())).thenAnswer(inv -> {
			store.add(inv.getArgument(0));
			return inv.getArgument(0);
		});
		when(repo.findFirstByUserIdOrderByCreatedAtDesc(any())).thenAnswer(inv ->
				store.isEmpty() ? Optional.empty() : Optional.of(store.get(store.size() - 1)));
		when(repo.findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(any(), any()))
				.thenAnswer(inv -> new ArrayList<>(store));
	}

	private VerifyResult result(boolean success) {
		return new VerifyResult(success, success, 0.9, 0.45, success, "ok", true, success, 0.9, 0.55, 12);
	}

	@Test
	@DisplayName("validacao facial passou: registra e alterna ENTRADA -> SAIDA")
	void punchTogglesEntradaSaida() {
		when(faceService.verify(any(), any(), any(), any())).thenReturn(result(true));

		PunchResult first = service.punch(user, "c1", List.of(), List.of());
		assertTrue(first.registered());
		assertEquals("ENTRADA", first.type()); // primeiro do dia

		PunchResult second = service.punch(user, "c2", List.of(), List.of());
		assertTrue(second.registered());
		assertEquals("SAIDA", second.type()); // alterna

		assertEquals(2, second.today().size());
	}

	@Test
	@DisplayName("validacao facial NAO passou: nao registra e devolve o motivo")
	void punchRejectedWhenVerificationFails() {
		when(faceService.verify(any(), any(), any(), any())).thenReturn(result(false));

		PunchResult punch = service.punch(user, "c1", List.of(), List.of());

		assertFalse(punch.registered());
		assertTrue(store.isEmpty());
		assertFalse(punch.verification().success());
	}

	@Test
	@DisplayName("today() indica ENTRADA como proximo registro quando nao ha registros")
	void todayNextTypeIsEntradaWhenEmpty() {
		assertEquals("ENTRADA", service.today(user).nextType());
	}
}
