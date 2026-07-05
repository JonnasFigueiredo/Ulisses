# Ulisses · Biometria Facial por Vídeo

Sistema de cadastro e validação de biometria facial por vídeo, com **reconhecimento 100% local** — nenhuma imagem sai da sua infraestrutura.

## Como funciona

| Etapa | Tecnologia |
|---|---|
| Detecção facial + landmarks | **SCRFD (det_10g)** — detector do InsightFace, estado da arte em detecção facial |
| Alinhamento facial | Transformação de similaridade (**Umeyama**) para o template canônico 112×112 |
| Reconhecimento | **ArcFace (w600k_r50)** — embeddings de 512 dimensões, comparados por similaridade de cosseno |
| Prova de vida (liveness) | **Desafio ativo**: o servidor sorteia um movimento de cabeça (esquerda/direita) que precisa ser executado ao vivo; o desafio é de uso único e expira em 2 minutos, bloqueando replay de fotos e vídeos gravados |
| Captura guiada por pose | Durante cadastro e validação, cada frame passa por checagem de pose em tempo real (`/api/face/pose`); a captura **só avança quando o usuário executa a pose pedida**, e o servidor revalida os giros no ato do cadastro |
| Inferência | **ONNX Runtime** rodando em CPU, embarcado no backend Java |

Fluxo do usuário: criar conta / login → **cadastro facial por vídeo** (captura guiada em 3 poses) → **validação por vídeo** com prova de vida.

- Os modelos ONNX (~180 MB) são baixados automaticamente **uma única vez** no primeiro start do backend, para a pasta `backend/models/`. Depois disso o sistema funciona offline.
- Backend: Java 21 + Spring Boot 3.5 (JWT, JPA/H2). Frontend: React 18 + Vite, responsivo (webcam no desktop, câmera frontal no celular).

## Requisitos

- JDK 21
- Node.js 18+

## Rodando

### Backend

```bash
cd backend
./mvnw spring-boot:run          # Linux/macOS/Git Bash
mvnw.cmd spring-boot:run        # Windows (cmd/PowerShell)
```

Sobe em `http://localhost:8080`. No primeiro start, aguarde o log `Engine de reconhecimento facial pronta`.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Sobe em `https://localhost:5173` (HTTPS com certificado autoassinado — aceite o aviso do navegador). As chamadas `/api` são proxeadas para o backend automaticamente.

### Usando no celular

1. Conecte o celular na mesma rede Wi-Fi do computador.
2. O Vite exibe no terminal o endereço de rede (ex.: `https://192.168.0.10:5173`) — abra-o no navegador do celular.
3. Aceite o certificado autoassinado. O HTTPS é obrigatório: navegadores só liberam a câmera em contexto seguro.

## Calibração e padrões globais

O sistema segue os referenciais internacionais de biometria facial:

- **Decisão 1:1 (FIDO Biometrics / NIST FRVT)** — a aceitação usa a **mediana** da similaridade de cosseno dos frames frontais contra os templates (mediana é robusta a frames atípicos). O limiar padrão `0.45` opera com taxa de falsa aceitação (FMR) na ordem de **1:100.000**, com folga sobre o requisito FIDO de 1:10.000. Pontos de operação: `0.38` conveniência (~1:1.000) · `0.42` equilibrado (~1:10.000, requisito FIDO) · `0.45` recomendado · `0.50` alta segurança. Os valores exatos de FMR variam com população e condições de captura.
- **Qualidade de cadastro (ISO/IEC 19794-5)** — os frames frontais do cadastro exigem **distância interocular ≥ 60 px**, garantindo resolução suficiente do template; a interface orienta o usuário a se aproximar da câmera quando necessário.
- **Prova de vida ativa (ISO/IEC 30107)** — desafio dinâmico de uso único sorteado pelo servidor; ≥ 60% dos frames do giro precisam confirmar o movimento na direção sorteada, com verificação de identidade contínua durante o movimento.

## Configuração

Principais chaves em `backend/src/main/resources/application.yml`:

| Chave | Padrão | Descrição |
|---|---|---|
| `ulisses.face.match-threshold` | `0.45` | Limiar de decisão 1:1 (ver tabela de pontos de operação acima) |
| `ulisses.face.min-detection-score` | `0.60` | Confiança mínima da detecção para aproveitar um frame |
| `ulisses.face.min-face-size` | `80` | Tamanho mínimo do rosto em pixels |
| `ulisses.face.min-enroll-interocular` | `60` | Distância interocular mínima (px) nos frames frontais do cadastro |
| `ulisses.jwt.secret` | dev | **Defina `ULISSES_JWT_SECRET` em produção** |

Console do H2 (dev): `http://localhost:8080/h2-console` — JDBC `jdbc:h2:file:./data/ulisses`, usuário `sa`, senha vazia.

## API

| Método | Rota | Descrição |
|---|---|---|
| `POST` | `/api/auth/register` | Cria conta e retorna JWT |
| `POST` | `/api/auth/login` | Login |
| `GET` | `/api/face/status` | Situação da biometria do usuário |
| `POST` | `/api/face/enroll` | Cadastro facial (multipart: `frames[]` + `phases[]`) |
| `POST` | `/api/face/pose` | Checagem de pose em tempo real para guiar a captura (multipart: `frame`) |
| `POST` | `/api/face/challenge` | Sorteia o desafio de prova de vida |
| `POST` | `/api/face/verify` | Validação (multipart: `challengeId`, `frames[]`, `phases[]`) |
| `DELETE` | `/api/face/enrollment` | Remove a biometria |
| `GET` | `/api/face/history` | Últimas validações |
