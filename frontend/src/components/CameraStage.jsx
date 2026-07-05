/**
 * Palco da camera: preview espelhado (como um espelho, mais natural para o usuario),
 * oval-guia para posicionamento do rosto e area para instrucoes sobrepostas.
 */
export default function CameraStage({ videoRef, ready, error, overlay, highlight }) {
  return (
    <div className={`camera-stage${highlight ? ' camera-stage--active' : ''}`}>
      <video ref={videoRef} playsInline muted autoPlay className="camera-video" />
      {ready && <div className="face-guide" aria-hidden="true" />}
      {!ready && !error && (
        <div className="camera-overlay">
          <div className="spinner" />
          <p>Abrindo camera...</p>
        </div>
      )}
      {error && (
        <div className="camera-overlay camera-overlay--error">
          <p>{error}</p>
        </div>
      )}
      {overlay}
    </div>
  );
}
