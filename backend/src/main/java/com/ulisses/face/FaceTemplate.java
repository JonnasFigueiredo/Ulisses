package com.ulisses.face;

import com.ulisses.crypto.EncryptedBytesConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.ByteBuffer;
import java.time.Instant;

/** Embedding facial (512 floats) de um usuario para uma pose de captura. */
@Entity
@Table(name = "face_template")
public class FaceTemplate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long userId;

	@Column(nullable = false, length = 20)
	private String phase;

	/** Bytes do embedding, cifrados em repouso (AES-256-GCM) quando ULISSES_CRYPTO_KEY esta definido. */
	@Convert(converter = EncryptedBytesConverter.class)
	@Column(nullable = false, length = 4096)
	private byte[] embedding;

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

	public String getPhase() {
		return phase;
	}

	public void setPhase(String phase) {
		this.phase = phase;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setEmbeddingVector(float[] vector) {
		ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
		buffer.asFloatBuffer().put(vector);
		this.embedding = buffer.array();
	}

	public float[] getEmbeddingVector() {
		float[] vector = new float[embedding.length / Float.BYTES];
		ByteBuffer.wrap(embedding).asFloatBuffer().get(vector);
		return vector;
	}
}
