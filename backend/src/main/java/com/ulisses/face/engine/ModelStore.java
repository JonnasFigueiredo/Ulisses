package com.ulisses.face.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Garante a presenca dos modelos ONNX em disco. O download acontece uma unica vez; depois disso o
 * sistema funciona totalmente offline.
 */
public final class ModelStore {

	private static final Logger log = LoggerFactory.getLogger(ModelStore.class);

	private ModelStore() {}

	public static Path ensureModel(Path modelsDir, String fileName, String url) throws IOException {
		Path target = modelsDir.resolve(fileName);
		if (Files.exists(target) && sizeOf(target) > 1_000_000) {
			return target;
		}
		Files.createDirectories(modelsDir);
		log.info("Baixando modelo {} de {} (apenas no primeiro start)...", fileName, url);
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "Ulisses/1.0")
				.timeout(Duration.ofMinutes(30))
				.build();
		Path temp = modelsDir.resolve(fileName + ".download");
		try {
			HttpResponse<InputStream> response =
					client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				throw new IOException("HTTP " + response.statusCode() + " ao baixar " + url);
			}
			try (InputStream in = response.body()) {
				Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
			}
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Download interrompido: " + url, e);
		} finally {
			Files.deleteIfExists(temp);
		}
		log.info("Modelo {} pronto ({} MB)", fileName, sizeOf(target) / (1024 * 1024));
		return target;
	}

	private static long sizeOf(Path path) throws IOException {
		return Files.size(path);
	}
}
