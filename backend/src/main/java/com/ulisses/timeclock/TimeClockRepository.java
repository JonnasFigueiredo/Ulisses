package com.ulisses.timeclock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimeClockRepository extends JpaRepository<TimeClockEntry, Long> {

	Optional<TimeClockEntry> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

	List<TimeClockEntry> findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(Long userId, Instant since);
}
