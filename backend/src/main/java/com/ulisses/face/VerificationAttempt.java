package com.ulisses.face;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Historico de tentativas de validacao facial. */
@Entity
@Table(name = "verification_attempt")
public class VerificationAttempt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false)
	private double similarity;

	@Column(nullable = false)
	private boolean livenessPassed;

	@Column(nullable = false)
	private boolean success;

	@Column(nullable = false)
	private Instant createdAt = Instant.now();

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public double getSimilarity() {
		return similarity;
	}

	public void setSimilarity(double similarity) {
		this.similarity = similarity;
	}

	public boolean isLivenessPassed() {
		return livenessPassed;
	}

	public void setLivenessPassed(boolean livenessPassed) {
		this.livenessPassed = livenessPassed;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
