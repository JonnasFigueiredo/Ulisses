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

## Configuração

Principais chaves em `backend/src/main/resources/application.yml`:

| Chave | Padrão | Descrição |
|---|---|---|
| `ulisses.face.match-threshold` | `0.42` | Similaridade de cosseno mínima para aceitar o rosto (aumente para mais rigor) |
| `ulisses.face.min-detection-score` | `0.60` | Confiança mínima da detecção para aproveitar um frame |
| `ulisses.face.min-face-size` | `80` | Tamanho mínimo do rosto em pixels (aproximação da câmera) |
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
