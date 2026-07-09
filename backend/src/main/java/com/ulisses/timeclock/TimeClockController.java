package com.ulisses.timeclock;

import com.ulisses.auth.User;
import com.ulisses.timeclock.TimeClockDtos.PunchResult;
import com.ulisses.timeclock.TimeClockDtos.TodayResponse;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/timeclock")
public class TimeClockController {

	private final TimeClockService timeClockService;

	public TimeClockController(TimeClockService timeClockService) {
		this.timeClockService = timeClockService;
	}

	@GetMapping("/today")
	public TodayResponse today(Authentication authentication) {
		return timeClockService.today(currentUser(authentication));
	}

	@PostMapping("/punch")
	public PunchResult punch(
			Authentication authentication,
			@RequestParam("challengeId") String challengeId,
			@RequestParam("frames") List<MultipartFile> frames,
			@RequestParam("phases") List<String> phases) {
		return timeClockService.punch(currentUser(authentication), challengeId, frames, phases);
	}

	private static User currentUser(Authentication authentication) {
		return (User) authentication.getPrincipal();
	}
}
