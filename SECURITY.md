# Política de Segurança

## Reportando vulnerabilidades

Encontrou uma falha de segurança? **Não abra uma issue pública.** Envie um e-mail para
**jonnas.figueiredo01@hotmail.com** com a descrição, o impacto e, se possível, um passo a passo de
reprodução. Retornaremos assim que possível.

## Escopo e avisos importantes

O Ulisses processa **dados biométricos**, considerados sensíveis pela LGPD (art. 11) e pelo GDPR.
Quem for adotar o projeto é responsável por:

- **Base legal e consentimento** para coletar e tratar biometria facial.
- **Criptografia dos templates em repouso** — habilite `ULISSES_CRYPTO_KEY` (perfil `prod`).
- **Segredos fortes** — defina `ULISSES_JWT_SECRET` e restrinja `ULISSES_CORS_ORIGINS`.
- **Retenção e exclusão** — exponha ao titular a remoção da biometria (a API já oferece
  `DELETE /api/face/enrollment`).

## Prova de vida (liveness) — limite de garantia

A camada de **liveness passiva (anti-spoofing) NÃO é certificada ISO/IEC 30107-3**. Certificação é
feita por laboratório acreditado (ex.: iBeta) e não pode ser substituída por código. Antes de
confiar nela como proteção antifraude, **calibre e valide contra um dataset real de ataques** com o
harness `PadEvaluationTest` (ver README). Enquanto não validada, trate-a como "melhor esforço".

## Modelos de terceiros

Os modelos de detecção/reconhecimento (InsightFace `buffalo_l`) são de **uso não-comercial** por
padrão — uso comercial exige licença da InsightFace ou substituição por um modelo próprio. Veja a
matriz de licenças no [README](README.md).
