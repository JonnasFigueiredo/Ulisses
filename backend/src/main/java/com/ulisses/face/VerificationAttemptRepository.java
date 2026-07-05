package com.ulisses.face;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationAttemptRepository extends JpaRepository<VerificationAttempt, Long> {

	List<VerificationAttempt> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);
}
