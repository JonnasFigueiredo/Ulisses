package com.ulisses.timeclock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Registro de ponto batido por reconhecimento facial (entrada/saida). */
@Entity
@Table(name = "time_clock_entry")
public class TimeClockEntry {

	public enum Type {
		ENTRADA,
		SAIDA
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Type type;

	/** Similaridade facial no momento do registro (auditoria). */
	@Column(nullable = false)
	private double similarity;

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

	public Type getType() {
		return type;
	}

	public void setType(Type type) {
		this.type = type;
	}

	public double getSimilarity() {
		return similarity;
	}

	public void setSimilarity(double similarity) {
		this.similarity = similarity;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
