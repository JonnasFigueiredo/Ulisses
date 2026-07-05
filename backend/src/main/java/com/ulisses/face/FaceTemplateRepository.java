package com.ulisses.face;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaceTemplateRepository extends JpaRepository<FaceTemplate, Long> {

	List<FaceTemplate> findByUserId(Long userId);

	void deleteByUserId(Long userId);
}
