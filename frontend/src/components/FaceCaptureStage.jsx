import { DIRECTIONS } from '../camera.js';
import CameraStage from './CameraStage.jsx';

/**
 * Palco de captura compartilhado: renderiza o video, o oval-guia reativo e os overlays de cada
 * fase (contagem, frontal, movimento do desafio e processamento), a partir do estado do
 * useFaceCapture. `busyLabel` e a mensagem exibida durante o envio.
 */
export default function FaceCaptureStage({ capture, busyLabel = 'Processando...' }) {
  const {
    videoRef,
    ready,
    cameraError,
    step,
    live,
    countdown,
    directions,
    turnIndex,
    idleOrDone,
    livePose,
    positionMsg,
    positioned,
  } = capture;
  const currentDirection = DIRECTIONS[directions[turnIndex]] || DIRECTIONS.LEFT;

  return (
    <CameraStage
      videoRef={videoRef}
      ready={ready}
      error={cameraError}
      guideState={
        idleOrDone && livePose != null
          ? positioned
            ? 'ok'
            : 'bad'
          : step === 'straight' || step === 'turn'
            ? live.ok
              ? 'ok'
              : 'bad'
            : null
      }
      chip={step === 'idle' ? positionMsg || (positioned ? 'Rosto posicionado ✓' : null) : null}
      overlay={
        <>
          {step === 'countdown' && (
            <div className="camera-overlay">
              <span className="countdown">{countdown}</span>
            </div>
          )}
          {step === 'straight' && (
            <div className="instruction-bar">
              <strong>Olhe direto para a camera</strong>
              <span className={live.ok ? 'status-msg ok' : 'status-msg warn'}>{live.message}</span>
              <div className="progress">
                <div className="progress-fill" style={{ width: `${live.progress * 100}%` }} />
              </div>
            </div>
          )}
          {step === 'turn' && (
            <div className="instruction-bar challenge">
              <strong>
                {currentDirection.arrow} {currentDirection.verb} {currentDirection.label}{' '}
                {currentDirection.arrow}
              </strong>
              <span className={live.ok ? 'status-msg ok' : 'status-msg warn'}>{live.message}</span>
              <div className="progress">
                <div className="progress-fill" style={{ width: `${live.progress * 100}%` }} />
              </div>
              <span className="muted">
                Prova de vida — movimento {turnIndex + 1} de {directions.length}
              </span>
            </div>
          )}
          {step === 'upload' && (
            <div className="camera-overlay">
              <div className="spinner" />
              <p>{busyLabel}</p>
            </div>
          )}
        </>
      }
    />
  );
}
