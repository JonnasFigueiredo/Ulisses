# Contribuindo com o Ulisses

Obrigado pelo interesse! Este guia cobre o essencial para contribuir.

## Ambiente

- **Backend:** JDK 21. Rode `sh mvnw verify` dentro de `backend/` (Windows: `.\mvnw.cmd verify`).
- **Frontend:** Node 22+. Dentro de `frontend/`, use `npm ci`, `npm test` e `npm run dev`.
- **Tudo junto:** configure os segredos em `.env` antes de `docker compose up --build` (ver [README](README.md)).

## Fluxo

1. Abra uma issue descrevendo o problema/proposta antes de um PR grande.
2. Crie um branch a partir de `main`.
3. Garanta que os testes do backend e do frontend passam e que `npm run build` gera o frontend.
4. Descreva claramente a mudança no PR. O CI (GitHub Actions) roda testes e builds automaticamente.

## Padrões

- Mantenha o estilo do código existente (nomes, comentários explicando o "porquê", não o "o quê").
- Mudanças no pipeline facial ou na prova de vida devem vir acompanhadas de testes.
- Rode `npm audit` ao atualizar dependências. Separe riscos do aplicativo dos riscos das ferramentas de desenvolvimento; não use `--force` sem revisar a migração.
- Consulte a [revisão técnica e o plano de evolução](docs/REVISAO_TECNICA.md) antes de iniciar uma mudança estrutural.
- **Segurança/biometria:** não faça afirmações de eficácia sem medição. Se mexer na liveness passiva, valide com o harness (`PadEvaluationTest`) e reporte APCER/BPCER.

## Escopo sensível

Este projeto processa **dado biométrico (sensível pela LGPD/GDPR)**. Contribuições que afetem armazenamento, retenção ou exposição desses dados exigem atenção redobrada — descreva o impacto de privacidade no PR.
