/**
 * Palco da camera: preview espelhado (como um espelho, mais natural para o usuario),
 * oval-guia REATIVO (fica verde quando o rosto esta bem posicionado — o posicionamento
 * e verificado de verdade pelo backend, nao e so decoracao) e area para instrucoes.
 */
export default function CameraStage({ videoRef, ready, error, overlay, guideState, chip }) {
  return (
    <div className={`camera-stage${guideState === 'ok' ? ' camera-stage--active' : ''}`}>
      <video ref={videoRef} playsInline muted autoPlay className="camera-video" />
      {ready && (
        <div
          className={`face-guide${guideState ? ` face-guide--${guideState}` : ''}`}
          aria-hidden="true"
        />
      )}
      {ready && chip && (
        <div className={`position-chip${guideState === 'ok' ? ' ok' : ''}`}>{chip}</div>
      )}
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
