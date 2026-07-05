package com.ulisses.face;

import com.ulisses.auth.User;
import com.ulisses.face.ChallengeService.Challenge;
import com.ulisses.face.FaceDtos.ChallengeResponse;
import com.ulisses.face.FaceDtos.EnrollResult;
import com.ulisses.face.FaceDtos.EnrollmentStatus;
import com.ulisses.face.FaceDtos.HistoryResponse;
import com.ulisses.face.FaceDtos.VerifyResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/face")
public class FaceController {

	private final FaceService faceService;
	private final ChallengeService challengeService;

	public FaceController(FaceService faceService, ChallengeService challengeService) {
		this.faceService = faceService;
		this.challengeService = challengeService;
	}

	@GetMapping("/status")
	public EnrollmentStatus status(Authentication authentication) {
		return faceService.status(currentUser(authentication));
	}

	@PostMapping("/enroll")
	public EnrollResult enroll(
			Authentication authentication,
			@RequestParam("frames") List<MultipartFile> frames,
			@RequestParam("phases") List<String> phases) {
		return faceService.enroll(currentUser(authentication), frames, phases);
	}

	@PostMapping("/pose")
	public FaceDtos.PoseResponse pose(@RequestParam("frame") MultipartFile frame) {
		return faceService.pose(frame);
	}

	@PostMapping("/challenge")
	public ChallengeResponse challenge(Authentication authentication) {
		Challenge challenge = challengeService.create(currentUser(authentication).getId());
		return new ChallengeResponse(
				challenge.id(),
				challenge.sequence().stream().map(Enum::name).toList(),
				Duration.between(Instant.now(), challenge.expiresAt()).toSeconds());
	}

	@PostMapping("/verify")
	public VerifyResult verify(
			Authentication authentication,
			@RequestParam("challengeId") String challengeId,
			@RequestParam("frames") List<MultipartFile> frames,
			@RequestParam("phases") List<String> phases) {
		return faceService.verify(currentUser(authentication), challengeId, frames, phases);
	}

	@DeleteMapping("/enrollment")
	public EnrollmentStatus deleteEnrollment(Authentication authentication) {
		User user = currentUser(authentication);
		faceService.deleteEnrollment(user);
		return faceService.status(user);
	}

	@GetMapping("/history")
	public HistoryResponse history(Authentication authentication) {
		return faceService.history(currentUser(authentication));
	}

	private static User currentUser(Authentication authentication) {
		return (User) authentication.getPrincipal();
	}
}
